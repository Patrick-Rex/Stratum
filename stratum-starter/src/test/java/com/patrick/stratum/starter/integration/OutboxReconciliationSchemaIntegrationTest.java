package com.patrick.stratum.starter.integration;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 在真实MariaDB上只读核对对账游标和索引；约束负例使用连接私有临时表。 */
class OutboxReconciliationSchemaIntegrationTest extends MariaDbIntegrationTest {
    private final JdbcTemplate jdbc;

    /**
     * 接收真实数据库元数据客户端。
     * @param jdbc 已迁移测试数据源
     */
    @Autowired
    OutboxReconciliationSchemaIntegrationTest(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 索引列顺序支持稳定消费凭据分页，生产单例存在且迁移已成功。 */
    @Test
    void createsOrderedIndexAndSingletonCursor() {
        assertEquals(List.of("consumed_at", "event_id", "consumer_group"), jdbc.queryForList("""
                SELECT COLUMN_NAME FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='outbox_consumption'
                    AND INDEX_NAME='idx_outbox_consumption_reconcile' ORDER BY SEQ_IN_INDEX
                """, String.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_reconciliation_cursor WHERE cursor_id=1", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='202610270002' AND success=1", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='202610270003' AND success=1", Integer.class));
    }

    /** 临时表允许完整或全空位置，拒绝半截位置、未知消费组和非单例身份。 */
    @Test
    void validatesCursorPositionWithoutMutatingSharedRow() throws Exception {
        String table = "test_cursor_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = jdbc.getDataSource().getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TEMPORARY TABLE `" + table + "` LIKE outbox_reconciliation_cursor");
            statement.executeUpdate("INSERT INTO `" + table + "` (cursor_id) VALUES (1)");
            assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE `" + table + "` SET event_id='00000000-0000-0000-0000-000000000001' WHERE cursor_id=1"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO `" + table + "` (cursor_id) VALUES (2)"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE `" + table + "` SET consumed_at=UTC_TIMESTAMP(6), event_id='00000000-0000-0000-0000-000000000001', consumer_group='UNKNOWN', cycle_upper_bound=UTC_TIMESTAMP(6) WHERE cursor_id=1"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE `" + table + "` SET consumed_at=UTC_TIMESTAMP(6), event_id='00000000-0000-0000-0000-000000000001', consumer_group='ERP_AUDIT', cycle_upper_bound=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE cursor_id=1"));
            assertEquals(1, statement.executeUpdate("UPDATE `" + table + "` SET consumed_at=UTC_TIMESTAMP(6), event_id='00000000-0000-0000-0000-000000000001', consumer_group='ERP_AUDIT', cycle_upper_bound=UTC_TIMESTAMP(6) WHERE cursor_id=1"));
            assertEquals(1, statement.executeUpdate("UPDATE `" + table + "` SET consumed_at=NULL, event_id=NULL, consumer_group=NULL, cycle_upper_bound=NULL WHERE cursor_id=1"));
        }
    }
}
