package com.patrick.stratum.starter.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.patrick.stratum.application.transaction.UnitOfWork;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 真实MariaDB验证任务历史迁移及约束，SQL夹具不代替后续仓储或实际执行器验收。 */
class JobHistorySchemaIntegrationTest extends MariaDbIntegrationTest {
    private final JdbcTemplate jdbc;
    private final UnitOfWork unitOfWork;
    private final List<String> executions = new ArrayList<>();

    /**
     * 注入技术表探针及真实应用工作单元。
     * @param jdbc 技术表断言与随机数据清理客户端
     * @param unitOfWork 用于确认全部历史表参与同一事务回滚
     */
    @Autowired
    JobHistorySchemaIntegrationTest(JdbcTemplate jdbc, UnitOfWork unitOfWork) {
        this.jdbc = jdbc;
        this.unitOfWork = unitOfWork;
    }

    /** 清理自己创建的恢复、死亡、尝试和执行记录，不触及其他任务历史。 */
    @AfterEach
    void cleanup() {
        for (String execution : executions) {
            jdbc.update("DELETE FROM job_replay_request WHERE execution_id=?", execution);
            jdbc.update("DELETE FROM dead_job WHERE execution_id=?", execution);
            jdbc.update("DELETE FROM job_attempt WHERE execution_id=?", execution);
            jdbc.update("DELETE FROM job_execution WHERE execution_id=?", execution);
        }
    }

    /** 四张历史表均由Flyway创建为InnoDB，新迁移成功且不修改原迁移。 */
    @Test
    void migratesTransactionalHistoryTables() {
        assertEquals(4, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
                AND table_name IN ('job_execution','job_attempt','dead_job','job_replay_request') AND engine='InnoDB'
                """, Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history WHERE version='202610260007' AND success=1
                """, Integer.class));
    }

    /** 执行UUID和任务代码内的幂等键分别唯一，不允许重复插入改变身份。 */
    @Test
    void enforcesExecutionAndScopedIdempotencyUniqueness() {
        String first = id();
        String key = UUID.randomUUID().toString();
        job(first, "schema.probe", key);
        assertThrows(DataIntegrityViolationException.class, () -> job(first, "schema.other", key));
        assertThrows(DataIntegrityViolationException.class, () -> job(id(), "schema.probe", key));
        job(id(), "schema.other", key);
        assertEquals("schema.probe", jdbc.queryForObject("SELECT job_code FROM job_execution WHERE execution_id=?", String.class, first));
    }

    /** 不完整租约、未知状态、错误失败次数和终态缺失完成时间均被拒绝。 */
    @Test
    void enforcesLeaseAndStateConsistency() {
        String execution = id();
        job(execution, "schema.probe", execution);
        for (String fields : List.of("state='RUNNING'", "lease_token='partial'", "state='UNKNOWN'",
                "state='DEAD',failure_count=3", "state='RETRY_WAIT'", "failure_count=-1",
                "state='SUCCEEDED'", "cycle_no=1", "version=-1", "state='WAITING '")) {
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update("UPDATE job_execution SET " + fields + " WHERE execution_id=?", execution));
        }
        jdbc.update("""
                UPDATE job_execution SET state='RUNNING',attempt_no=1,lease_token=?,start_time=UTC_TIMESTAMP(6),
                lease_until=UTC_TIMESTAMP(6)+INTERVAL 60 SECOND WHERE execution_id=?
                """, UUID.randomUUID().toString(), execution);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                UPDATE job_execution SET lease_until=start_time+INTERVAL 331 SECOND WHERE execution_id=?
                """, execution));
        assertEquals("RUNNING", jdbc.queryForObject("SELECT state FROM job_execution WHERE execution_id=?", String.class, execution));
    }

    /** JSON参数、计划互斥、时限和安全trace/键边界受到数据库保护。 */
    @Test
    void enforcesDefinitionAndPayloadBounds() {
        String execution = id();
        job(execution, "schema.probe", execution);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE job_execution SET arguments=? WHERE execution_id=?", "{", execution));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE job_execution SET arguments=? WHERE execution_id=?", "\"" + "x".repeat(16_384) + "\"", execution));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE job_execution SET trace_id=? WHERE execution_id=?", "bad\ntrace", execution));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE job_execution SET idempotency_key=? WHERE execution_id=?", "bad key", execution));
        for (String fields : List.of("timeout_sec=301", "max_attempts=11", "max_delay_ms=99", "job_type='ONCE'", "trigger_at=UTC_TIMESTAMP(6)", "idempotency_rule='EXPLICIT_KEY '")) {
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update("UPDATE job_execution SET " + fields + " WHERE execution_id=?", execution));
        }
    }

    /** 死亡记录只能引用同周期真实失败尝试，恢复请求不能换成另一个执行身份。 */
    @Test
    void linksAttemptsDeathsAndReplayIdentity() {
        String execution = id();
        String other = id();
        String dead = UUID.randomUUID().toString();
        job(execution, "schema.probe", execution);
        job(other, "schema.probe", other);
        assertThrows(DataIntegrityViolationException.class, () -> death(execution, dead, "TIMED_OUT"));
        attempt(execution);
        death(execution, dead, "TIMED_OUT");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE dead_job SET reason='FAILED' WHERE dead_id=?", dead));
        assertThrows(DataIntegrityViolationException.class, () -> death(execution, UUID.randomUUID().toString(), "TIMED_OUT"));
        assertThrows(DataIntegrityViolationException.class, () -> death(execution, UUID.randomUUID().toString(), "FAILED"));
        assertThrows(DataIntegrityViolationException.class, () -> replay(other, dead));
        for (String fields : List.of("reason='TIMED_OUT '", "state='DEAD '")) {
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update("UPDATE dead_job SET " + fields + " WHERE dead_id=?", dead));
        }
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE job_attempt SET result='TIMED_OUT ' WHERE execution_id=?", execution));
        replay(execution, dead);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM dead_job WHERE dead_id=?", dead));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM job_attempt WHERE execution_id=?", execution));
    }

    /** 四张表的关联夹具在同一Application事务失败后全部不可见。 */
    @Test
    void rollsBackAllHistoryTablesTogether() {
        String execution = id();
        String dead = UUID.randomUUID().toString();
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            job(execution, "schema.probe", execution);
            attempt(execution);
            death(execution, dead, "TIMED_OUT");
            replay(execution, dead);
            throw new IllegalStateException("rollback-probe");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM job_execution WHERE execution_id=?", Integer.class, execution));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM job_attempt WHERE execution_id=?", Integer.class, execution));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dead_job WHERE dead_id=?", Integer.class, dead));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM job_replay_request WHERE dead_id=?", Integer.class, dead));
    }

    /**
     * 构造最小合法Cron任务状态，生产登记适配尚未实现。
     * @param execution 执行UUID
     * @param code 任务代码
     * @param key 幂等键
     */
    private void job(String execution, String code, String key) {
        jdbc.update("""
                INSERT INTO job_execution(execution_id,job_code,job_type,cron_expression,timeout_sec,max_attempts,
                initial_delay_ms,max_delay_ms,idempotency_rule,schedule_time,idempotency_key,arguments,
                initial_trace_id,trace_id,next_attempt_at)
                VALUES (?,?,'CRON','0/5 * * * * ?',30,3,100,1000,'EXPLICIT_KEY',UTC_TIMESTAMP(6),?,'{}',
                'schema-trace','schema-trace',UTC_TIMESTAMP(6))
                """, execution, code, key);
    }

    /**
     * 构造已结束的固定超时尝试夹具。
     * @param execution 已登记执行UUID
     */
    private void attempt(String execution) {
        jdbc.update("""
                INSERT INTO job_attempt(execution_id,attempt_no,cycle_no,lease_token,schedule_time,start_time,
                end_time,duration_ms,result,trace_id)
                VALUES (?,3,0,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0,'TIMED_OUT','schema-trace')
                """, execution, UUID.randomUUID().toString());
    }

    /**
     * 构造指定原因的死亡夹具，由复合外键核对尝试/周期/原因。
     * @param execution 执行UUID
     * @param dead 死亡UUID
     * @param reason 固定失败原因
     */
    private void death(String execution, String dead, String reason) {
        jdbc.update("""
                INSERT INTO dead_job(dead_id,execution_id,cycle_no,last_attempt,failure_count,reason)
                VALUES (?,?,0,3,3,?)
                """, dead, execution, reason);
    }

    /**
     * 构造恢复请求夹具，由复合外键禁止死亡目标与执行身份不一致。
     * @param execution 关联执行UUID
     * @param dead 死亡UUID
     */
    private void replay(String execution, String dead) {
        jdbc.update("""
                INSERT INTO job_replay_request(request_id,dead_id,execution_id,resumed_cycle,trace_id)
                VALUES (?,?,?,1,'schema-replay')
                """, UUID.randomUUID().toString(), dead, execution);
    }

    /**
     * 登记本用例负责清理的随机执行UUID。
     * @return 新执行UUID
     */
    private String id() {
        String id = UUID.randomUUID().toString();
        executions.add(id);
        return id;
    }
}
