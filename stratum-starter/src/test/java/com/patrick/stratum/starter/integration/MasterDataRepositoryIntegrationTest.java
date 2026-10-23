package com.patrick.stratum.starter.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.masterdata.MasterData;
import com.patrick.stratum.domain.masterdata.MasterDataRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 真实MariaDB验证主数据CRUD、唯一编码、乐观锁及业务引用保护。 */
class MasterDataRepositoryIntegrationTest extends MariaDbIntegrationTest {
    private final MasterDataRepository repository;
    private final UnitOfWork unitOfWork;
    private final JdbcTemplate jdbc;
    private final List<String> ids = new ArrayList<>();

    /**
     * 装配实际仓储与工作单元。
     * @param repository 主数据仓储
     * @param unitOfWork 事务入口
     * @param jdbc 断言与引用夹具客户端
     */
    @Autowired
    MasterDataRepositoryIntegrationTest(MasterDataRepository repository, UnitOfWork unitOfWork, JdbcTemplate jdbc) {
        this.repository = repository; this.unitOfWork = unitOfWork; this.jdbc = jdbc;
    }

    /** 四类主数据完整CRUD，固定属性保留且版本随更新递增。 */
    @Test
    void persistsAllKindsAndUpdatesOnlyNames() {
        for (var kind : MasterData.Kind.values()) {
            var value = create(kind, UUID.randomUUID().toString());
            assertEquals(0L, value.getVersion());
            value.rename("新名称");
            unitOfWork.execute(() -> { repository.save(value); return null; });
            var loaded = repository.findById(value.getId()).orElseThrow();
            assertEquals("新名称", loaded.getName());
            assertEquals(value.getCode(), loaded.getCode());
            assertEquals(kind, loaded.getKind());
            assertEquals(kind == MasterData.Kind.MATERIAL ? "千克" : null, loaded.getBaseUnit());
            assertEquals(1L, loaded.getVersion());
            unitOfWork.execute(() -> { repository.delete(loaded); return null; });
            assertTrue(repository.findById(value.getId()).isEmpty());
        }
    }

    /** 同类编码必须唯一，不同类别允许使用相同编码。 */
    @Test
    void enforcesUniquenessWithinKind() {
        String code = UUID.randomUUID().toString();
        create(MasterData.Kind.MATERIAL, code);
        create(MasterData.Kind.CUSTOMER, code);
        var conflict = assertThrows(StratumException.class, () -> create(MasterData.Kind.MATERIAL, code));
        assertEquals(CommonErrorCode.CONFLICT, conflict.getErrorCode());
        assertTrue(repository.findById("' OR '1'='1").isEmpty());
    }

    /** 过期更新与删除不能覆盖已经提交的新版本。 */
    @Test
    void rejectsStaleUpdatesAndDeletes() {
        var value = create(MasterData.Kind.MATERIAL, UUID.randomUUID().toString());
        var stale = repository.findById(value.getId()).orElseThrow();
        value.rename("已提交名称");
        unitOfWork.execute(() -> { repository.save(value); return null; });
        assertEquals(CommonErrorCode.CONFLICT, assertThrows(StratumException.class,
                () -> unitOfWork.execute(() -> { repository.save(stale); return null; })).getErrorCode());
        assertEquals(CommonErrorCode.CONFLICT, assertThrows(StratumException.class,
                () -> unitOfWork.execute(() -> { repository.delete(stale); return null; })).getErrorCode());
        assertEquals("已提交名称", repository.findById(value.getId()).orElseThrow().getName());
    }

    /** 即使仓储收到重建快照，ORM也不会更新固定字段。 */
    @Test
    void excludesImmutableFieldsFromUpdate() {
        var original = create(MasterData.Kind.MATERIAL, UUID.randomUUID().toString());
        var altered = MasterData.restore(original.getId(), MasterData.Kind.CUSTOMER, "另一编码", "更新名称", null, 0);
        unitOfWork.execute(() -> { repository.save(altered); return null; });
        var actual = repository.findById(original.getId()).orElseThrow();
        assertEquals(MasterData.Kind.MATERIAL, actual.getKind());
        assertEquals(original.getCode(), actual.getCode());
        assertEquals("千克", actual.getBaseUnit());
        assertEquals("更新名称", actual.getName());
    }

    /** 已被外键引用的主数据禁止删除，删除失败不会破坏原引用。 */
    @Test
    void rejectsDeletionWhileReferenced() {
        var value = create(MasterData.Kind.MATERIAL, UUID.randomUUID().toString());
        jdbc.update("INSERT INTO stratum_it_master_data_reference(id, master_data_id) VALUES (?, ?)", UUID.randomUUID().toString(), value.getId());
        var conflict = assertThrows(StratumException.class,
                () -> unitOfWork.execute(() -> { repository.delete(value); return null; }));
        assertEquals(CommonErrorCode.CONFLICT, conflict.getErrorCode());
        assertTrue(repository.findById(value.getId()).isPresent());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM stratum_it_master_data_reference WHERE master_data_id = ?", Integer.class, value.getId()));
    }

    /** 写操作要求已有工作单元，事务失败后数据库无残留。 */
    @Test
    void requiresTransactionAndRollsBack() {
        var value = MasterData.create(MasterData.Kind.WAREHOUSE, UUID.randomUUID().toString(), "仓库", null);
        ids.add(value.getId());
        assertThrows(IllegalStateException.class, () -> repository.save(value));
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            repository.save(value); throw new IllegalStateException("测试回滚");
        }));
        assertTrue(repository.findById(value.getId()).isEmpty());
    }

    /** 两个真实事务读取同版本后竞争更新，只允许一个提交。
     * @throws Exception 任务执行失败或超时
     */
    @Test
    void concurrentUpdatesCannotOverwriteEachOther() throws Exception {
        var value = create(MasterData.Kind.MATERIAL, UUID.randomUUID().toString());
        var ready = new CountDownLatch(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> competingRename(value.getId(), "第一名称", ready));
            var second = executor.submit(() -> competingRename(value.getId(), "第二名称", ready));
            assertEquals(1, first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS));
        }
        assertEquals(1L, repository.findById(value.getId()).orElseThrow().getVersion());
    }

    /** 先删除测试引用，再清理本测试生成的主数据，不触及共享数据。 */
    @AfterEach
    void cleanOwnRows() {
        unitOfWork.execute(() -> {
            for (String id : ids) {
                jdbc.update("DELETE FROM stratum_it_master_data_reference WHERE master_data_id = ?", id);
                jdbc.update("DELETE FROM erp_master_data WHERE id = ?", id);
            }
            return null;
        });
    }

    /**
     * 创建并登记测试主数据。
     * @param kind 类别
     * @param code 编码
     * @return 持久化后的聚合
     */
    private MasterData create(MasterData.Kind kind, String code) {
        var value = MasterData.create(kind, code, "原名称", kind == MasterData.Kind.MATERIAL ? "千克" : null);
        ids.add(value.getId());
        return unitOfWork.execute(() -> { repository.save(value); return value; });
    }

    /**
     * 在屏障后竞争保存同一主数据版本。
     * @param id 聚合标识
     * @param name 新名称
     * @param ready 双方已读取信号
     * @return 提交为1，冲突回滚为0
     */
    private int competingRename(String id, String name, CountDownLatch ready) {
        try {
            return unitOfWork.execute(() -> {
                var value = repository.findById(id).orElseThrow();
                ready.countDown();
                assertDoesNotThrow(() -> assertTrue(ready.await(5, TimeUnit.SECONDS)));
                value.rename(name); repository.save(value); return 1;
            });
        } catch (StratumException conflict) {
            assertEquals(CommonErrorCode.CONFLICT, conflict.getErrorCode());
            return 0;
        }
    }
}
