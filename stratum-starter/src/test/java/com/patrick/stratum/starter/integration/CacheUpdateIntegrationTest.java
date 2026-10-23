package com.patrick.stratum.starter.integration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;

import com.patrick.stratum.application.cache.CacheUpdateCoordinator;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.base.cache.CacheInvalidation;
import com.patrick.stratum.common.base.cache.VersionedCache;
import com.patrick.stratum.common.base.cache.VersionedCache.Policy;
import com.patrick.stratum.common.base.cache.VersionedCache.Value;
import com.patrick.stratum.common.trace.TraceIdContext;

import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实 MariaDB 事务配合可控缓存故障，验证提交时机及补偿记录的耐久性。 */
@ExtendWith(OutputCaptureExtension.class)
class CacheUpdateIntegrationTest extends MariaDbIntegrationTest {
    private static final Policy POLICY = new Policy(Duration.ofMinutes(5), Duration.ofSeconds(30));
    private static final Value VALUE = new Value(2, "业务数据不进入补偿消息");
    @MockitoBean
    private VersionedCache cache;
    private final UnitOfWork work;
    private final CacheUpdateCoordinator coordinator;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final List<String> ids = new ArrayList<>();
    private final List<String> events = new ArrayList<>();

    /**
     * 注入真实工作单元、代理协调器和数据库断言客户端。
     * @param work 工作单元
     * @param coordinator 提交协调器
     * @param jdbc 数据库客户端
     * @param json 补偿载荷解析器
     */
    @Autowired
    CacheUpdateIntegrationTest(UnitOfWork work, CacheUpdateCoordinator coordinator, JdbcTemplate jdbc, JsonMapper json) {
        this.work = work;
        this.coordinator = coordinator;
        this.jdbc = jdbc;
        this.json = json;
    }

    /** 缓存只在提交后更新，缓存回调执行时独立连接已经可见业务行与补偿行。 */
    @Test
    void commitsIntentBeforeUpdatingCache() throws Exception {
        String id = id();
        String key = key(id);
        doAnswer(invocation -> {
            try (var connection = jdbc.getDataSource().getConnection();
                    var statement = connection.prepareStatement("SELECT COUNT(*) FROM stratum_it_transaction_probe WHERE id = ?")) {
                statement.setString(1, id);
                try (var rows = statement.executeQuery()) { rows.next(); assertEquals(1, rows.getInt(1)); }
            }
            assertEquals(1, eventCount(events.getFirst()));
            return true;
        }).when(cache).putIfNewer(key, VALUE, POLICY);
        String eventId = work.execute(() -> {
            insert(id);
            String event = register(key);
            verifyNoInteractions(cache);
            return event;
        });
        verify(cache).putIfNewer(key, VALUE, POLICY);
        var row = jdbc.queryForMap("SELECT status, event_type, payload FROM outbox_event WHERE event_id = ?", eventId);
        assertEquals("PENDING", row.get("status"));
        assertEquals(CacheInvalidation.EVENT_TYPE, row.get("event_type"));
        var event = json.readValue(row.get("payload").toString(), CacheInvalidation.class);
        assertEquals(key, event.key());
        assertEquals(2, event.minimumVersion());
        assertEquals(POLICY, event.policy());
        assertFalse(row.get("payload").toString().contains(VALUE.payload()));
    }

    /** 事务整体回滚时无缓存副作用，也不留下可被消费的补偿意图。 */
    @Test
    void rollbackDoesNotTouchCacheOrKeepIntent() {
        String id = id();
        assertThrows(IllegalStateException.class, () -> work.execute(() -> {
            insert(id);
            register(key(id));
            throw new IllegalStateException("业务失败");
        }));
        verifyNoInteractions(cache);
        assertEquals(0, eventCount(events.getFirst()));
        assertEquals(0, rowCount(id));
    }

    /** 更新失败后降级失效，数据库提交成功且补偿消息保留。 */
    @Test
    void updateFailureFallsBackToInvalidation() {
        String id = id();
        String key = key(id);
        when(cache.putIfNewer(key, VALUE, POLICY)).thenThrow(new IllegalStateException("Redis 更新故障"));
        work.execute(() -> { insert(id); return register(key); });
        var order = inOrder(cache);
        order.verify(cache).putIfNewer(key, VALUE, POLICY);
        order.verify(cache).invalidate(key, 2, POLICY);
        assertEquals(1, rowCount(id));
        assertEquals(1, eventCount(events.getFirst()));
    }

    /**
     * Redis 更新与失效都失败也不会丢失补偿，已提交业务不能向调用方假报回滚。
     * @param output 当前默认格式的日志输出
     */
    @Test
    void completeCacheOutageKeepsDurableIntent(CapturedOutput output) {
        String id = id();
        String key = key(id);
        when(cache.putIfNewer(key, VALUE, POLICY)).thenThrow(new IllegalStateException("不可用"));
        doThrow(new IllegalStateException("仍不可用")).when(cache).invalidate(key, 2, POLICY);
        TraceIdContext.setTraceId("cache-compensation-test");
        assertDoesNotThrow(() -> work.execute(() -> { insert(id); return register(key); }));
        verify(cache).invalidate(key, 2, POLICY);
        assertEquals(1, rowCount(id));
        assertEquals(1, eventCount(events.getFirst()));
        assertTrue(output.getOut().contains("cacheKey=" + key));
        assertTrue(output.getOut().contains("eventId=" + events.getFirst()));
        assertTrue(output.getOut().contains("traceId=cache-compensation-test"));
    }

    /** 删除或未携带完整值的更新通过提交后失效入口执行。 */
    @Test
    void schedulesInvalidationOnlyAfterCommit() {
        String key = key(id());
        work.execute(() -> {
            events.add(coordinator.invalidateAfterCommit(key, 3, POLICY));
            verifyNoInteractions(cache);
            return null;
        });
        verify(cache).invalidate(key, 3, POLICY);
        verifyNoMoreInteractions(cache);
        assertEquals(1, eventCount(events.getFirst()));
    }

    /** 事务外调用被拒绝，非法补偿参数在事务提交前失败并回滚业务写入。 */
    @Test
    void rejectsMissingTransactionAndInvalidIntent() {
        String id = id();
        assertThrows(IllegalTransactionStateException.class,
                () -> coordinator.updateAfterCommit(key(id), VALUE, POLICY));
        assertThrows(IllegalArgumentException.class, () -> work.execute(() -> {
            insert(id);
            return coordinator.updateAfterCommit("invalid:key", VALUE, POLICY);
        }));
        assertEquals(0, rowCount(id));
        verifyNoInteractions(cache);
    }

    /** 只清理当前测试成功登记的事件和随机业务行。 */
    @AfterEach
    void cleanOwnRows() {
        TraceIdContext.clear();
        work.execute(() -> {
            for (String event : events) { jdbc.update("DELETE FROM outbox_event WHERE event_id = ?", event); }
            for (String id : ids) { jdbc.update("DELETE FROM stratum_it_transaction_probe WHERE id = ?", id); }
            return null;
        });
    }

    /**
     * 登记事件并记录用于清理的标识。
     * @param key 缓存键
     * @return 事件标识
     */
    private String register(String key) {
        String event = coordinator.updateAfterCommit(key, VALUE, POLICY);
        events.add(event);
        return event;
    }

    /**
     * 生成归本测试所有的业务标识。
     * @return 随机 UUID
     */
    private String id() {
        String id = UUID.randomUUID().toString();
        ids.add(id);
        return id;
    }

    /**
     * 转换测试标识为缓存键。
     * @param id 测试标识
     * @return 缓存键
     */
    private static String key(String id) { return "biz:stratum:it:" + id; }

    /**
     * 在当前工作单元中插入测试业务行。
     * @param id 测试标识
     */
    private void insert(String id) {
        jdbc.update("INSERT INTO stratum_it_transaction_probe(id, payload) VALUES (?, ?)", id, "缓存事务测试");
    }

    /**
     * 查询测试业务行数量。
     * @param id 测试标识
     * @return 行数
     */
    private int rowCount(String id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM stratum_it_transaction_probe WHERE id = ?", Integer.class, id);
    }

    /**
     * 查询持久化补偿意图数量。
     * @param eventId 事件标识
     * @return 行数
     */
    private int eventCount(String eventId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_id = ?", Integer.class, eventId);
    }
}
