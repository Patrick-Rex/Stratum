package com.patrick.stratum.starter.integration;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 在真实MariaDB核对调度技术表、集群命名空间和外键，不提前启动调度线程。 */
class SchedulerSchemaIntegrationTest extends MariaDbIntegrationTest {
    private final JdbcTemplate jdbc;

    /**
     * 注入仅供技术表断言与随机夹具清理使用的数据库客户端。
     * @param jdbc 数据库客户端
     */
    @Autowired
    SchedulerSchemaIntegrationTest(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Flyway一次创建全部Quartz表及ShedLock表，未启用调度时没有调度器心跳。 */
    @Test
    void migratesCompleteSchemaWithoutStartingScheduler() {
        assertEquals(11, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema=DATABASE() AND LEFT(table_name,13)='STRATUM_QRTZ_'
                """, Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE version='202610260006' AND success=1
                """, Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM STRATUM_QRTZ_SCHEDULER_STATE", Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name='stratum_shedlock' AND engine='InnoDB'
                """, Integer.class));
    }

    /** 触发器必须引用同调度命名空间的作业，删除仍被引用的作业受数据库阻止。 */
    @Test
    void enforcesQuartzJobAndTriggerReferences() {
        String name = UUID.randomUUID().toString();
        try {
            assertThrows(DataIntegrityViolationException.class, () -> trigger(name));
            jdbc.update("""
                    INSERT INTO STRATUM_QRTZ_JOB_DETAILS
                    (SCHED_NAME,JOB_NAME,JOB_GROUP,JOB_CLASS_NAME,IS_DURABLE,IS_NONCONCURRENT,IS_UPDATE_DATA,REQUESTS_RECOVERY)
                    VALUES (?,'probe','test','example.Probe','1','1','0','0')
                    """, name);
            trigger(name);
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                    "DELETE FROM STRATUM_QRTZ_JOB_DETAILS WHERE SCHED_NAME=?", name));
            jdbc.update("""
                    INSERT INTO STRATUM_QRTZ_SIMPLE_TRIGGERS
                    (SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP,REPEAT_COUNT,REPEAT_INTERVAL,TIMES_TRIGGERED)
                    VALUES (?,'probe','test',0,1000,0)
                    """, name);
            assertThrows(DataIntegrityViolationException.class, () -> trigger(name));
        } finally {
            jdbc.update("DELETE FROM STRATUM_QRTZ_SIMPLE_TRIGGERS WHERE SCHED_NAME=?", name);
            jdbc.update("DELETE FROM STRATUM_QRTZ_TRIGGERS WHERE SCHED_NAME=?", name);
            jdbc.update("DELETE FROM STRATUM_QRTZ_JOB_DETAILS WHERE SCHED_NAME=?", name);
        }
    }

    /** ShedLock名称唯一且保留毫秒租期；不同锁独立，同名不能插入第二持有者。 */
    @Test
    void persistsUniqueLockAndMillisecondExpiry() {
        String name = "lock:job:test:" + UUID.randomUUID();
        try {
            lock(name);
            assertThrows(DataIntegrityViolationException.class, () -> lock(name));
            assertEquals(123000, jdbc.queryForObject(
                    "SELECT MICROSECOND(lock_until) FROM stratum_shedlock WHERE name=?", Integer.class, name));
            assertEquals("holder", jdbc.queryForObject(
                    "SELECT locked_by FROM stratum_shedlock WHERE name=?", String.class, name));
        } finally {
            jdbc.update("DELETE FROM stratum_shedlock WHERE name=?", name);
        }
    }

    /**
     * 插入有明确命名空间的简单触发器夹具。
     * @param scheduler 随机调度器命名空间
     */
    private void trigger(String scheduler) {
        jdbc.update("""
                INSERT INTO STRATUM_QRTZ_TRIGGERS
                (SCHED_NAME,TRIGGER_NAME,TRIGGER_GROUP,JOB_NAME,JOB_GROUP,TRIGGER_STATE,TRIGGER_TYPE,START_TIME)
                VALUES (?,'probe','test','probe','test','WAITING','SIMPLE',0)
                """, scheduler);
    }

    /**
     * 插入带固定毫秒部分的随机名称锁夹具。
     * @param name 本用例唯一锁名
     */
    private void lock(String name) {
        jdbc.update("""
                INSERT INTO stratum_shedlock(name,lock_until,locked_at,locked_by)
                VALUES (?,'2026-10-26 12:00:00.123','2026-10-26 11:59:00.123','holder')
                """, name);
    }
}
