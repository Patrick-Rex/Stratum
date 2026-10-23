package com.patrick.stratum.starter.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.starter.integration.fixture.domain.PersistenceProbe;
import com.patrick.stratum.starter.integration.fixture.persistence.ProbeRecord;
import com.patrick.stratum.starter.integration.fixture.persistence.ProbeRepositoryImpl;

import static org.junit.jupiter.api.Assertions.*;

/** 用真实 MariaDB 验证仓储模板、生成代理及 ORM 与 Spring 工作单元的一致性。 */
class EasyQueryRepositoryIntegrationTest extends MariaDbIntegrationTest {
    private final EasyEntityQuery query;
    private final UnitOfWork unitOfWork;
    private final ProbeRepositoryImpl repository;
    private final List<String> createdIds = new ArrayList<>();

    /**
     * 注入应用实际 ORM 和事务组件。
     * @param query ORM 客户端
     * @param unitOfWork 事务代理
     */
    @Autowired
    EasyQueryRepositoryIntegrationTest(EasyEntityQuery query, UnitOfWork unitOfWork) {
        this.query = query;
        this.unitOfWork = unitOfWork;
        this.repository = new ProbeRepositoryImpl(query);
    }

    /** 完整保存、加载、更新和删除，数据库版本随成功写入递增。 */
    @Test
    void createsUpdatesAndDeletesAggregate() {
        var probe = create("初值");
        assertEquals(0L, repository.findById(probe.getId()).orElseThrow().getVersion());
        probe.changePayload("新值");
        unitOfWork.execute(() -> { repository.save(probe); return null; });
        var reloaded = repository.findById(probe.getId()).orElseThrow();
        assertEquals("新值", reloaded.payload());
        assertEquals(1L, reloaded.getVersion());
        unitOfWork.execute(() -> { repository.delete(reloaded); return null; });
        assertTrue(repository.findById(probe.getId()).isEmpty());
    }

    /** Spring 事务异常必须撤销 Easy-Query 已执行写入。 */
    @Test
    void ormWriteRollsBackWithWorkUnit() {
        var probe = PersistenceProbe.create("回滚值");
        createdIds.add(probe.getId());
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            repository.save(probe);
            throw new IllegalStateException("回滚");
        }));
        assertTrue(repository.findById(probe.getId()).isEmpty());
    }

    /** 不允许绕过 Application 工作单元执行写入。 */
    @Test
    void rejectsWriteOutsideTransaction() {
        var probe = PersistenceProbe.create("非法写入");
        assertThrows(IllegalStateException.class, () -> repository.save(probe));
        assertTrue(repository.findById(probe.getId()).isEmpty());
    }

    /** 过期版本的更新与删除均返回统一 40900，不覆盖成功写入。 */
    @Test
    void rejectsStaleUpdateAndDelete() {
        var winner = create("初值");
        var stale = repository.findById(winner.getId()).orElseThrow();
        winner.changePayload("赢家");
        unitOfWork.execute(() -> { repository.save(winner); return null; });
        var updateFailure = assertThrows(StratumException.class,
                () -> unitOfWork.execute(() -> { repository.save(stale); return null; }));
        var deleteFailure = assertThrows(StratumException.class,
                () -> unitOfWork.execute(() -> { repository.delete(stale); return null; }));
        assertEquals(CommonErrorCode.CONFLICT, updateFailure.getErrorCode());
        assertEquals(CommonErrorCode.CONFLICT, deleteFailure.getErrorCode());
        assertEquals("赢家", repository.findById(winner.getId()).orElseThrow().payload());
    }

    /** 两个独立事务读取相同版本后竞争写入，只能一个成功。
     * @throws Exception 并发任务超时或执行失败时抛出
     */
    @Test
    void concurrentWritersCannotLoseUpdates() throws Exception {
        String id = create("初值").getId();
        var ready = new CountDownLatch(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> competingWrite(id, "第一", ready));
            var second = executor.submit(() -> competingWrite(id, "第二", ready));
            assertEquals(1, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1L, repository.findById(id).orElseThrow().getVersion());
    }

    /** 固定排序配合参数化 ID 集合完成真实分页；恶意字符串不能改变条件。 */
    @Test
    void paginatesWithTypedColumnsAndBoundValues() {
        create("分页一");
        create("分页二");
        create("分页三");
        var page = query.queryable(ProbeRecord.class).where(row -> row.id().in(createdIds))
                .orderBy(row -> row.id().asc()).toPageResult(1, 2);
        assertEquals(3, page.getTotal());
        assertEquals(2, page.getData().size());
        assertTrue(repository.findById("' OR '1'='1").isEmpty());
    }

    /** 仅按本测试生成的 ID 清理数据，不执行全表删除。 */
    @AfterEach
    void cleanOwnRows() {
        for (String id : createdIds) {
            unitOfWork.execute(() -> {
                repository.findById(id).ifPresent(repository::delete);
                return null;
            });
        }
    }

    /**
     * 在工作单元内创建测试聚合并登记清理标识。
     * @param payload 测试数据
     * @return 持久化聚合
     */
    private PersistenceProbe create(String payload) {
        var probe = PersistenceProbe.create(payload);
        createdIds.add(probe.getId());
        unitOfWork.execute(() -> { repository.save(probe); return null; });
        return probe;
    }

    /**
     * 用屏障确保双方读取同一版本，再由数据库判定赢家。
     * @param id 聚合标识
     * @param payload 竞争写入的数据
     * @param ready 双方读完成信号
     * @return 成功为 1，版本冲突为 0
     */
    private int competingWrite(String id, String payload, CountDownLatch ready) {
        try {
            return unitOfWork.execute(() -> {
                var probe = repository.findById(id).orElseThrow();
                ready.countDown();
                assertDoesNotThrow(() -> assertTrue(ready.await(5, TimeUnit.SECONDS)));
                probe.changePayload(payload);
                repository.save(probe);
                return 1;
            });
        } catch (StratumException conflict) {
            assertEquals(CommonErrorCode.CONFLICT, conflict.getErrorCode());
            return 0;
        }
    }
}
