package com.patrick.stratum.starter.integration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 独占MariaDB验证任务标准追踪迁移、历史数据和旧版本写入兼容。 */
@Tag("integration")
class JobStandardTraceMigrationIntegrationTest {
    /**
     * 迁移旧任务并模拟旧写入，核对原数据、空追踪及两个状态字段的父值约束。
     * @throws Exception 数据库迁移或SQL失败
     */
    @Test
    void preservesHistoricalTasksAndAcceptsOldWriterAfterUpgrade() throws Exception {
        try (var database = IsolatedMariaDbFixture.start()) {
            Flyway.configure().dataSource(database.jdbcUrl(), database.username(), database.password())
                    .locations("classpath:db/migration").target("202610280003").load().migrate();
            String oldId = UUID.randomUUID().toString(); String newId = UUID.randomUUID().toString();
            try (var connection = DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password())) {
                insertLegacy(connection, oldId);
                Flyway.configure().dataSource(database.jdbcUrl(), database.username(), database.password())
                        .locations("classpath:db/migration").load().migrate();
                verifyLegacy(connection, oldId); insertLegacy(connection, newId); verifyLegacy(connection, newId);
                try (var update = connection.prepareStatement("UPDATE job_execution SET initial_standard_tracestate=? WHERE execution_id=?")) {
                    update.setString(1, "vendor=value"); update.setString(2, oldId);
                    assertThrows(SQLException.class, update::executeUpdate);
                }
                try (var update = connection.prepareStatement("UPDATE job_execution SET standard_tracestate=? WHERE execution_id=?")) {
                    update.setString(1, "vendor=value"); update.setString(2, oldId);
                    assertThrows(SQLException.class, update::executeUpdate);
                }
                try (var update = connection.prepareStatement("""
                        UPDATE job_execution SET standard_traceparent=?,standard_tracestate=? WHERE execution_id=?
                        """)) {
                    update.setString(1, "00-cccccccccccccccccccccccccccccccc-dddddddddddddddd-01");
                    update.setString(2, "c=two"); update.setString(3, oldId); assertEquals(1, update.executeUpdate());
                }
                try (var query = connection.prepareStatement("""
                        SELECT initial_standard_traceparent,standard_traceparent FROM job_execution WHERE execution_id=?
                        """)) {
                    query.setString(1, oldId);
                    try (var row = query.executeQuery()) {
                        assertTrue(row.next()); assertNull(row.getString(1));
                        assertEquals("00-cccccccccccccccccccccccccccccccc-dddddddddddddddd-01", row.getString(2));
                        assertFalse(row.next());
                    }
                }
                try (var update = connection.prepareStatement("""
                        UPDATE job_execution SET initial_standard_traceparent=?,initial_standard_tracestate=?,
                        standard_traceparent=?,standard_tracestate=? WHERE execution_id=?
                        """)) {
                    update.setString(1, "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01");
                    update.setString(2, "a=one");
                    update.setString(3, "00-cccccccccccccccccccccccccccccccc-dddddddddddddddd-01");
                    update.setString(4, "c=two"); update.setString(5, oldId); assertEquals(1, update.executeUpdate());
                }
                try (var query = connection.prepareStatement("""
                        SELECT initial_standard_traceparent,initial_standard_tracestate,standard_traceparent,standard_tracestate
                        FROM job_execution WHERE execution_id=?
                        """)) {
                    query.setString(1, oldId);
                    try (var row = query.executeQuery()) {
                        assertTrue(row.next());
                        assertEquals("00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01", row.getString(1));
                        assertEquals("a=one", row.getString(2));
                        assertEquals("00-cccccccccccccccccccccccccccccccc-dddddddddddddddd-01", row.getString(3));
                        assertEquals("c=two", row.getString(4)); assertFalse(row.next());
                    }
                }
            }
        }
    }

    /**
     * 使用旧列集合创建任务，模拟历史或迁移后尚未升级的写入方。
     * @param connection 独占数据库连接
     * @param id 测试执行UUID
     * @throws SQLException 插入失败
     */
    private void insertLegacy(Connection connection, String id) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO job_execution(execution_id,job_code,job_type,trigger_at,timeout_sec,max_attempts,
                initial_delay_ms,max_delay_ms,idempotency_rule,schedule_time,idempotency_key,arguments,
                initial_trace_id,trace_id,next_attempt_at)
                VALUES (?,'migration.probe','ONCE','2026-10-30 00:00:00',30,3,100,1000,'EXPLICIT_KEY',
                '2026-10-30 00:00:00',?,'{"value":"original"}','business-original','business-current','2026-10-30 00:00:00')
                """)) {
            statement.setString(1, id); statement.setString(2, id); assertEquals(1, statement.executeUpdate());
        }
    }

    /**
     * 核对任务原身份、输入和业务关联不变，初始及当前标准上下文均为空。
     * @param connection 独占数据库连接
     * @param id 测试执行UUID
     * @throws SQLException 查询失败
     */
    private void verifyLegacy(Connection connection, String id) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT job_code,idempotency_key,arguments,initial_trace_id,trace_id,
                initial_standard_traceparent,initial_standard_tracestate,standard_traceparent,standard_tracestate
                FROM job_execution WHERE execution_id=?
                """)) {
            statement.setString(1, id);
            try (var row = statement.executeQuery()) {
                assertTrue(row.next()); assertEquals("migration.probe", row.getString(1)); assertEquals(id, row.getString(2));
                assertEquals("{\"value\":\"original\"}", row.getString(3)); assertEquals("business-original", row.getString(4));
                assertEquals("business-current", row.getString(5));
                for (int index = 6; index <= 9; index++) assertNull(row.getString(index));
                assertFalse(row.next());
            }
        }
    }
}
