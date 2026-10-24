package com.patrick.stratum.starter.integration;

import java.sql.SQLException;
import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** 验证管理协调行迁移及真实数据库排他锁；不把夹具SQL当作生产管理仓储。 */
class AuthorizationGuardIntegrationTest extends MariaDbIntegrationTest {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    /**
     * 注入独立测试库连接。
     * @param jdbc 约束验证客户端
     * @param dataSource 独立连接来源
     */
    @Autowired
    AuthorizationGuardIntegrationTest(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc; this.dataSource = dataSource;
    }

    /** 协调行只有固定主键且初始化标志只能为布尔值，重启迁移不重复创建。 */
    @Test
    void migrationCreatesOneConstrainedCoordinationRow() {
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM auth_administration_guard", Integer.class));
        assertTrue(SetHolder.BOOLEAN_VALUES.contains(jdbc.queryForObject(
                "SELECT initialized FROM auth_administration_guard WHERE singleton_id=1", Integer.class)));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO auth_administration_guard(singleton_id,initialized) VALUES(2,0)"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE auth_administration_guard SET initialized=2 WHERE singleton_id=1"));
    }

    /**
     * 两个独立事务不能同时持有协调行排他锁；回滚释放后另一事务立即可获取。
     * @throws SQLException 测试连接或清理失败时抛出
     */
    @Test
    void exclusiveRowLockSerializesIndependentTransactions() throws SQLException {
        try (var first = dataSource.getConnection(); var second = dataSource.getConnection()) {
            first.setAutoCommit(false); second.setAutoCommit(false);
            try (var owner = first.createStatement(); var contender = second.createStatement()) {
                try (var result = owner.executeQuery("SELECT initialized FROM auth_administration_guard WHERE singleton_id=1 FOR UPDATE")) {
                    assertTrue(result.next());
                }
                var conflict = assertThrows(SQLException.class, () -> contender.executeQuery(
                        "SELECT initialized FROM auth_administration_guard WHERE singleton_id=1 FOR UPDATE NOWAIT"));
                assertEquals(1205, conflict.getErrorCode());
                first.rollback(); second.rollback();
                try (var result = contender.executeQuery("SELECT initialized FROM auth_administration_guard WHERE singleton_id=1 FOR UPDATE NOWAIT")) {
                    assertTrue(result.next());
                }
            } finally { first.rollback(); second.rollback(); }
        }
    }

    /** 固定数据库布尔值集合，兼容后续初始化已完成的数据库。 */
    private static final class SetHolder {
        private static final java.util.Set<Integer> BOOLEAN_VALUES = java.util.Set.of(0, 1);
    }
}
