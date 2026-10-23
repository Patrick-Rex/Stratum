package com.patrick.stratum.starter.integration;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.patrick.stratum.application.transaction.UnitOfWork;

import static org.junit.jupiter.api.Assertions.*;

/** 使用真实 MariaDB 验证 Spring 代理工作单元的提交、异常回滚及 REQUIRED 嵌套语义。 */
class UnitOfWorkIntegrationTest extends MariaDbIntegrationTest {
    private final UnitOfWork unitOfWork;
    private final JdbcTemplate jdbc;
    private final Flyway flyway;
    private final List<String> createdIds = new ArrayList<>();

    /**
     * 注入真实应用上下文中的事务代理和数据源客户端。
     * @param unitOfWork Application 工作单元
     * @param jdbc 同一数据源的 JDBC 客户端
     * @param flyway 迁移执行器
     */
    @Autowired
    UnitOfWorkIntegrationTest(UnitOfWork unitOfWork, JdbcTemplate jdbc, Flyway flyway) {
        this.unitOfWork = unitOfWork;
        this.jdbc = jdbc;
        this.flyway = flyway;
    }

    /** 迁移校验成功，应用基线迁移有成功历史，不把空迁移集视为通过。 */
    @Test
    void appliesAndValidatesMigration() {
        flyway.validate();
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = ? AND success = 1",
                Integer.class, "202610230001"));
    }

    /** 写入在工作单元内不可被独立连接看见，提交后才对其他连接可见。 */
    @Test
    void commitsOnlyAfterCallbackReturns() {
        String id = nextId();
        assertEquals("done", unitOfWork.execute(() -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            insert(id);
            assertEquals(0, assertDoesNotThrow(() -> countOnIndependentConnection(id)));
            return "done";
        }));
        assertEquals(1, count(id));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    /** 业务运行时异常保留原实例，已执行的 SQL 整体回滚。 */
    @Test
    void rollsBackOnRuntimeFailure() {
        String id = nextId();
        var failure = new IllegalStateException("测试业务失败");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            insert(id);
            throw failure;
        })));
        assertEquals(0, count(id));
    }

    /** JVM 错误同样不能遗留已写入的数据。 */
    @Test
    void rollsBackOnError() {
        String id = nextId();
        assertThrows(AssertionError.class, () -> unitOfWork.execute(() -> {
            insert(id);
            throw new AssertionError("测试错误");
        }));
        assertEquals(0, count(id));
    }

    /** 内层工作单元加入外层事务，不会提前提交。 */
    @Test
    void nestedWorkJoinsOuterTransaction() {
        String outerId = nextId();
        String innerId = nextId();
        unitOfWork.execute(() -> {
            insert(outerId);
            unitOfWork.execute(() -> { insert(innerId); return null; });
            assertEquals(0, assertDoesNotThrow(() -> countOnIndependentConnection(innerId)));
            return null;
        });
        assertEquals(1, count(outerId));
        assertEquals(1, count(innerId));
    }

    /** 外层失败会撤销已成功返回的内层 SQL。 */
    @Test
    void outerFailureRollsBackNestedWrite() {
        String id = nextId();
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            unitOfWork.execute(() -> { insert(id); return null; });
            throw new IllegalStateException("外层失败");
        }));
        assertEquals(0, count(id));
    }

    /** 捕获内层异常不能让已标记回滚的事务误提交。 */
    @Test
    void caughtNestedFailureStillRollsBackWholeTransaction() {
        String outerId = nextId();
        String innerId = nextId();
        assertThrows(UnexpectedRollbackException.class, () -> unitOfWork.execute(() -> {
            insert(outerId);
            assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
                insert(innerId);
                throw new IllegalStateException("内层失败");
            }));
            return null;
        }));
        assertEquals(0, count(outerId));
        assertEquals(0, count(innerId));
    }

    /** 数据库唯一约束失败也必须撤销同事务此前的写入。 */
    @Test
    void sqlFailureRollsBackEarlierWrite() {
        String id = nextId();
        assertThrows(DuplicateKeyException.class, () -> unitOfWork.execute(() -> {
            insert(id);
            insert(id);
            return null;
        }));
        assertEquals(0, count(id));
    }

    /** 仅删除本测试生成的 UUID 行，避免清空其他测试或用户数据。 */
    @AfterEach
    void cleanOwnRows() {
        for (String id : createdIds) {
            unitOfWork.execute(() -> jdbc.update("DELETE FROM stratum_it_transaction_probe WHERE id = ?", id));
        }
    }

    /**
     * 生成并登记本测试拥有的记录标识。
     * @return 随机 UUID
     */
    private String nextId() {
        String id = UUID.randomUUID().toString();
        createdIds.add(id);
        return id;
    }

    /**
     * 执行参数化写入，调用方负责开启工作单元。
     * @param id 测试记录标识
     */
    private void insert(String id) {
        jdbc.update("INSERT INTO stratum_it_transaction_probe (id, payload) VALUES (?, ?)", id, "测试值");
    }

    /**
     * 从事务外读取记录数量。
     * @param id 测试记录标识
     * @return 匹配数量
     */
    private int count(String id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM stratum_it_transaction_probe WHERE id = ?", Integer.class, id);
    }

    /**
     * 绕过当前线程绑定的连接读取数据，用于检查未提交写入的可见性。
     * @param id 测试记录标识
     * @return 独立连接可见的记录数量
     * @throws SQLException 连接或查询失败时抛出
     */
    private int countOnIndependentConnection(String id) throws SQLException {
        try (var connection = jdbc.getDataSource().getConnection();
                var statement = connection.prepareStatement("SELECT COUNT(*) FROM stratum_it_transaction_probe WHERE id = ?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }
}
