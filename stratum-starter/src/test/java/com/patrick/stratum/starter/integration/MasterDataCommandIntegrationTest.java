package com.patrick.stratum.starter.integration;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.patrick.stratum.application.command.CommandGateway;
import com.patrick.stratum.application.masterdata.CreateMasterDataCommand;
import com.patrick.stratum.application.masterdata.MasterDataApplicationService;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.base.cache.VersionedCache;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.domain.masterdata.MasterData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** 通过实际CommandGateway验证主数据创建与幂等、Outbox、Redis缓存的协作。 */
class MasterDataCommandIntegrationTest extends MariaDbIntegrationTest {
    private final String subject = UUID.randomUUID().toString();
    private final List<String> ids = new ArrayList<>();
    private final CommandGateway gateway;
    private final UnitOfWork unitOfWork;
    private final VersionedCache cache;
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final JsonMapper json;

    /**
     * 装配完整应用入口及真实数据服务。
     * @param gateway 命令入口
     * @param unitOfWork 外层回滚测试入口
     * @param cache 版本缓存
     * @param jdbc 独立持久化断言
     * @param redis 清理测试缓存
     * @param json JSON断言
     */
    @Autowired
    MasterDataCommandIntegrationTest(CommandGateway gateway, UnitOfWork unitOfWork, VersionedCache cache,
            JdbcTemplate jdbc, StringRedisTemplate redis, JsonMapper json) {
        this.gateway = gateway; this.unitOfWork = unitOfWork; this.cache = cache;
        this.jdbc = jdbc; this.redis = redis; this.json = json;
    }

    /** 将本测试产生的全部事件关联到独立trace，便于精确清理。 */
    @BeforeEach
    void bindTrace() { TraceIdContext.setTraceId(subject); }

    /** 四类主数据均经真实命令路由，提交业务事件与缓存补偿并更新Redis。 */
    @Test
    void createsAllKindsAndCommitsEventsAndCache() {
        for (var kind : MasterData.Kind.values()) {
            var result = gateway.execute(command(kind, UUID.randomUUID().toString(), "原名称")).data();
            ids.add(result.getId());
            assertEquals(kind, result.getKind());
            assertEquals(0, result.getVersion());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM erp_master_data WHERE id = ?", Integer.class, result.getId()));
            var cached = cache.get(MasterDataApplicationService.cacheKey(result.getId()), MasterDataApplicationService.CACHE_POLICY,
                    () -> fail("已提交的主数据应已写入缓存")).orElseThrow();
            assertEquals("原名称", json.readTree(cached.payload()).path("name").asString());
            assertEquals(0, cached.version());
        }
        assertEquals(8, eventCount());
        assertEquals(4, receiptCount());
    }

    /** 同键重试重放同一UUID，既不重复创建主数据，也不重复登记事件。 */
    @Test
    void replaysCreateAndRejectsChangedPayload() {
        String key = UUID.randomUUID().toString();
        var command = command(MasterData.Kind.MATERIAL, key, "原名称");
        var first = gateway.execute(command).data();
        ids.add(first.getId());
        var repeated = gateway.execute(command).data();
        assertEquals(first.getId(), repeated.getId());
        assertEquals(first.getBaseUnit(), repeated.getBaseUnit());
        var conflict = assertThrows(StratumException.class, () -> gateway.execute(command(MasterData.Kind.MATERIAL, key, "其他名称")));
        assertEquals(CommonErrorCode.CONFLICT, conflict.getErrorCode());
        assertEquals(2, eventCount());
        assertEquals(1, receiptCount());
    }

    /** 不同幂等键不能绕过编码唯一性，冲突请求不保存成功回执或事件。 */
    @Test
    void duplicateCodeRollsBackWithoutAReceipt() {
        var firstCommand = command(MasterData.Kind.CUSTOMER, "first", "客户");
        var first = gateway.execute(firstCommand).data();
        ids.add(first.getId());
        var duplicate = CreateMasterDataCommand.builder().subjectId(subject).idempotencyKey("second")
                .kind(firstCommand.getKind()).code(firstCommand.getCode()).name("重复客户").build();
        assertEquals(CommonErrorCode.CONFLICT, assertThrows(StratumException.class, () -> gateway.execute(duplicate)).getErrorCode());
        assertEquals(1, receiptCount());
        assertEquals(2, eventCount());
    }

    /** 外层工作单元回滚时，业务、事件、回执和缓存均无已提交副作用。 */
    @Test
    void outerRollbackLeavesNoBusinessOrCache() {
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            var result = gateway.execute(command(MasterData.Kind.WAREHOUSE, "rollback", "仓库")).data();
            ids.add(result.getId());
            assertFalse(redis.hasKey(MasterDataApplicationService.cacheKey(result.getId())));
            throw new IllegalStateException("外层业务回滚");
        }));
        assertEquals(0, eventCount());
        assertEquals(0, receiptCount());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM erp_master_data WHERE id = ?", Integer.class, ids.getFirst()));
        assertTrue(cache.get(MasterDataApplicationService.cacheKey(ids.getFirst()), MasterDataApplicationService.CACHE_POLICY, Optional::empty).isEmpty());
    }

    /** 清理本测试主数据、回执、事件及关联缓存，不清空共享Redis。 */
    @AfterEach
    void cleanOwnRows() {
        try {
            unitOfWork.execute(() -> {
                jdbc.update("DELETE FROM outbox_event WHERE trace_id = ?", subject);
                jdbc.update("DELETE FROM idempotency_receipt WHERE scope LIKE ?", "master:%:" + subject);
                for (String id : ids) jdbc.update("DELETE FROM erp_master_data WHERE id = ?", id);
                return null;
            });
            for (String id : ids) {
                String key = MasterDataApplicationService.cacheKey(id);
                cache.invalidate(key, 100, MasterDataApplicationService.CACHE_POLICY);
                redis.delete(List.of(key, "null:" + key.substring(4), "version:" + key.substring(4)));
            }
        } finally { TraceIdContext.clear(); }
    }

    /**
     * 构造独立主体下的创建命令。
     * @param kind 主数据类别
     * @param key 幂等键，同时参与测试编码生成
     * @param name 名称
     * @return 创建命令
     */
    private CreateMasterDataCommand command(MasterData.Kind kind, String key, String name) {
        String code = UUID.nameUUIDFromBytes((subject + key).getBytes(StandardCharsets.UTF_8)).toString();
        return CreateMasterDataCommand.builder().subjectId(subject).idempotencyKey(key).kind(kind).code(code)
                .name(name).baseUnit(kind == MasterData.Kind.MATERIAL ? "千克" : null).build();
    }

    /**
     * 查询本测试提交的事件与缓存补偿总数。
     * @return 事件数量
     */
    private int eventCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE trace_id = ?", Integer.class, subject); }

    /**
     * 查询本主体完成回执数量。
     * @return 回执数量
     */
    private int receiptCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_receipt WHERE scope LIKE ?", Integer.class, "master:%:" + subject); }
}
