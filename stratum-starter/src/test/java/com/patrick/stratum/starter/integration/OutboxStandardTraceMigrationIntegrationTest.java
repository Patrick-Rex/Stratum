package com.patrick.stratum.starter.integration;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 独占MariaDB验证历史Outbox迁移及旧写入兼容，不启动应用。 */
@Tag("integration")
class OutboxStandardTraceMigrationIntegrationTest {
    /**
     * 先迁移到旧版本并写入历史事件，再升级并核对旧字段、空上下文及旧式插入。
     * @throws Exception 数据库迁移或SQL失败
     */
    @Test void preservesHistoricalRowsAndAcceptsOldWriterAfterUpgrade() throws Exception {
        try (var database = IsolatedMariaDbFixture.start()) {
            var previous = Flyway.configure().dataSource(database.jdbcUrl(), database.username(), database.password())
                    .locations("classpath:db/migration").target("202610280002").load();
            previous.migrate();
            String aggregate = "migration-" + UUID.randomUUID(); String oldId = UUID.randomUUID().toString();
            String newId = UUID.randomUUID().toString();
            try (var connection = DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password())) {
                try (var statement = connection.prepareStatement("INSERT INTO outbox_aggregate_progress (aggregate_id,last_sequence) VALUES (?,2)")) {
                    statement.setString(1, aggregate); statement.executeUpdate();
                }
                insertLegacy(connection, oldId, aggregate, 1);
                Flyway.configure().dataSource(database.jdbcUrl(), database.username(), database.password())
                        .locations("classpath:db/migration").load().migrate();
                verifyLegacy(connection, oldId, aggregate, 1);
                insertLegacy(connection, newId, aggregate, 2); verifyLegacy(connection, newId, aggregate, 2);
                try (var update = connection.prepareStatement("UPDATE outbox_event SET standard_tracestate=? WHERE event_id=?")) {
                    update.setString(1, "vendor=value"); update.setString(2, oldId);
                    assertThrows(SQLException.class, update::executeUpdate);
                }
                try (var update = connection.prepareStatement("UPDATE outbox_event SET standard_traceparent=?,standard_tracestate=? WHERE event_id=?")) {
                    update.setString(1, "00-1234567890abcdef1234567890abcdef-1234567890abcdef-01");
                    update.setString(2, "vendor=value"); update.setString(3, oldId); assertEquals(1, update.executeUpdate());
                }
            }
        }
    }

    /**
     * 通过旧版本列集合写入事件，模拟历史和迁移后仍未升级的写入方。
     * @param connection 独占数据库连接
     * @param id 事件UUID
     * @param aggregate 测试聚合
     * @param sequence 测试序号
     * @throws SQLException 写入失败
     */
    private void insertLegacy(java.sql.Connection connection, String id, String aggregate, long sequence) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO outbox_event (event_id,event_type,aggregate_id,event_sequence,occurred_at,payload,trace_id,status,retry_count,next_retry_at,created_at,version)
                VALUES (?,'erp.probe.created',?,?,UTC_TIMESTAMP(6),'{"value":"original"}','business-id','PENDING',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0)
                """)) {
            statement.setString(1, id); statement.setString(2, aggregate); statement.setLong(3, sequence);
            assertEquals(1, statement.executeUpdate());
        }
    }

    /**
     * 核对原事件身份、顺序、正文与业务关联保持一致，新增上下文为空。
     * @param connection 独占数据库连接
     * @param id 事件UUID
     * @param aggregate 原聚合
     * @param sequence 原序号
     * @throws SQLException 读取失败
     */
    private void verifyLegacy(java.sql.Connection connection, String id, String aggregate, long sequence) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT aggregate_id,event_sequence,payload,trace_id,standard_traceparent,standard_tracestate FROM outbox_event WHERE event_id=?")) {
            statement.setString(1, id);
            try (var row = statement.executeQuery()) {
                assertTrue(row.next()); assertEquals(aggregate, row.getString(1)); assertEquals(sequence, row.getLong(2));
                assertEquals("{\"value\":\"original\"}", row.getString(3)); assertEquals("business-id", row.getString(4));
                assertNull(row.getString(5)); assertNull(row.getString(6)); assertFalse(row.next());
            }
        }
    }
}
