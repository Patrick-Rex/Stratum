package com.patrick.stratum.starter.integration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryException;
import com.patrick.stratum.application.event.OutboxEventWriter;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.domain.event.DomainEvent;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxRepository;
import com.patrick.stratum.starter.integration.fixture.domain.PersistenceProbe;
import com.patrick.stratum.starter.integration.fixture.persistence.ProbeRepositoryImpl;

import static org.junit.jupiter.api.Assertions.*;

/** 通过真实 MariaDB 验证领域写入与 Outbox 的原子性及重复事件保护。 */
class OutboxIntegrationTest extends MariaDbIntegrationTest {
    private final UnitOfWork unitOfWork;
    private final OutboxEventWriter writer;
    private final OutboxRepository outbox;
    private final JdbcTemplate jdbc;
    private final ProbeRepositoryImpl repository;
    private final List<String> aggregateIds = new ArrayList<>();
    private final List<String> eventIds = new ArrayList<>();

    /**
     * 装配应用实际写入链路，不用内存模拟数据库或事件存储。
     * @param unitOfWork 工作单元
     * @param writer 事件落盘入口
     * @param outbox 事件仓储端口
     * @param jdbc 断言查询客户端
     * @param query ORM 客户端
     */
    @Autowired
    OutboxIntegrationTest(UnitOfWork unitOfWork, OutboxEventWriter writer, OutboxRepository outbox,
            JdbcTemplate jdbc, EasyEntityQuery query) {
        this.unitOfWork = unitOfWork;
        this.writer = writer;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.repository = new ProbeRepositoryImpl(query);
    }

    /** 成功工作单元同时提交聚合与事件，保留 trace、类型及原始业务数据。 */
    @Test
    void commitsAggregateAndEventTogether() {
        var probe = probe();
        var event = event(probe);
        TraceIdContext.setTraceId("outbox-integration-trace");
        unitOfWork.execute(() -> {
            repository.save(probe);
            return writer.append(event, "test.probe.created");
        });
        assertTrue(repository.findById(probe.getId()).isPresent());
        var row = jdbc.queryForMap("SELECT * FROM outbox_event WHERE event_id = ?", event.getEventId());
        assertEquals("PENDING", row.get("status"));
        assertEquals(0, ((Number) row.get("retry_count")).intValue());
        assertEquals("outbox-integration-trace", row.get("trace_id"));
        assertEquals("test.probe.created", row.get("event_type"));
        assertTrue(row.get("payload").toString().contains("测试事件数据"));
    }

    /** 事件写入后发生业务失败，两张表均不留记录。 */
    @Test
    void rollsBackAggregateAndEventTogether() {
        var probe = probe();
        var event = event(probe);
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            repository.save(probe);
            writer.append(event, "test.probe.created");
            throw new IllegalStateException("写入后失败");
        }));
        assertTrue(repository.findById(probe.getId()).isEmpty());
        assertEquals(0, eventCount(event.getEventId()));
    }

    /** 重复事件不会覆盖已存在的数据，并撤销本次工作单元的聚合写入。 */
    @Test
    void duplicateEventRollsBackNewWrite() {
        var original = probe();
        var event = event(original);
        unitOfWork.execute(() -> { repository.save(original); return writer.append(event, "test.probe.created"); });
        var next = probe();
        var failure = assertThrows(StratumException.class, () -> unitOfWork.execute(() -> {
            repository.save(next);
            return writer.append(event, "test.probe.created");
        }));
        assertEquals(CommonErrorCode.CONFLICT, failure.getErrorCode());
        assertEquals(1, eventCount(event.getEventId()));
        assertTrue(repository.findById(original.getId()).isPresent());
        assertTrue(repository.findById(next.getId()).isEmpty());
    }

    /** 数据库拒绝非法 JSON 时，同事务聚合写入也必须回滚。 */
    @Test
    void invalidPayloadRollsBackAggregate() {
        var probe = probe();
        String eventId = UUID.randomUUID().toString();
        eventIds.add(eventId);
        var invalid = new OutboxMessage(eventId, "test.probe.created", probe.getId(), Instant.now(), "not-json", "trace");
        assertThrows(EasyQueryException.class, () -> unitOfWork.execute(() -> {
            repository.save(probe);
            outbox.append(invalid);
            return null;
        }));
        assertTrue(repository.findById(probe.getId()).isEmpty());
        assertEquals(0, eventCount(eventId));
    }

    /** Application 入口和仓储端口均拒绝事务外事件，避免事件单独提交。 */
    @Test
    void rejectsEventsOutsideWorkUnit() {
        var probe = probe();
        var event = event(probe);
        assertThrows(IllegalTransactionStateException.class, () -> writer.append(event, "test.probe.created"));
        var message = new OutboxMessage(event.getEventId(), "test.probe.created", probe.getId(), Instant.now(), "{}", "trace");
        assertThrows(IllegalStateException.class, () -> outbox.append(message));
        assertEquals(0, eventCount(event.getEventId()));
    }

    /** 清理当前测试拥有的数据与线程上下文，不清空共享表。 */
    @AfterEach
    void cleanOwnRows() {
        TraceIdContext.clear();
        unitOfWork.execute(() -> {
            for (String eventId : eventIds) { jdbc.update("DELETE FROM outbox_event WHERE event_id = ?", eventId); }
            for (String aggregateId : aggregateIds) { repository.findById(aggregateId).ifPresent(repository::delete); }
            return null;
        });
    }

    /**
     * 新建并登记测试聚合。
     * @return 尚未持久化的聚合
     */
    private PersistenceProbe probe() {
        var probe = PersistenceProbe.create("测试业务数据");
        aggregateIds.add(probe.getId());
        return probe;
    }

    /**
     * 新建并登记领域事件。
     * @param probe 来源聚合
     * @return 新事件
     */
    private ProbeEvent event(PersistenceProbe probe) {
        var event = new ProbeEvent(probe.getId());
        eventIds.add(event.getEventId());
        return event;
    }

    /**
     * 查询事务提交后可见的事件条数。
     * @param eventId 事件标识
     * @return 持久化条数
     */
    private int eventCount(String eventId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_id = ?", Integer.class, eventId);
    }

    /** 测试领域事件，不引入业务模型与框架注解。 */
    public static final class ProbeEvent extends DomainEvent {
        /**
         * 创建测试事件。
         * @param aggregateId 聚合标识
         * @apiNote 示例：new ProbeEvent(probe.getId())。
         */
        public ProbeEvent(String aggregateId) { super(aggregateId); }

        /**
         * 提供序列化的业务数据。
         * @return 测试数据
         * @apiNote 示例：event.getDescription()。
         */
        public String getDescription() { return "测试事件数据"; }
    }
}
