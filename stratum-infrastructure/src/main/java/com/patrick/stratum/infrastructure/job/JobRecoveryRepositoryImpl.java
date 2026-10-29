package com.patrick.stratum.infrastructure.job;

import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.common.trace.StandardTraceContext;
import com.patrick.stratum.domain.job.JobExecutionRepository.Lease;
import com.patrick.stratum.domain.job.JobExecutionRepository.State;
import com.patrick.stratum.domain.job.JobRecoveryRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import static com.patrick.stratum.infrastructure.job.JobExecutionRepositoryImpl.conflict;
import static com.patrick.stratum.infrastructure.job.JobExecutionRepositoryImpl.requireWrite;
import static com.patrick.stratum.infrastructure.job.JobExecutionRepositoryImpl.time;
import static com.patrick.stratum.infrastructure.job.JobExecutionRepositoryImpl.translate;

/**
 * 共享执行存储的当前读与结果写回，负责令牌完成和幂等人工恢复，不执行任务副作用。
 * <p>示例：由Application工作单元调用finish或replay；只访问相同技术表。</p>
 */
@Repository
public class JobRecoveryRepositoryImpl implements JobRecoveryRepository {
    private final JdbcTemplate jdbc;
    private final JobExecutionRepositoryImpl executions;

    /**
     * 参与相同数据源的应用事务，为恢复SQL使用独立五秒语句预算。
     * @param dataSource 已有应用数据源
     * @param executions 同技术存储的行映射与原子结果操作
     * @apiNote 示例：由Spring装配，不创建额外连接池或工作单元。
     */
    public JobRecoveryRepositoryImpl(DataSource dataSource, JobExecutionRepositoryImpl executions) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setQueryTimeout(5);
        this.executions = executions;
    }

    /** {@inheritDoc} */
    @Override
    public boolean finish(Lease lease, Outcome outcome) {
        requireWrite();
        Objects.requireNonNull(lease);
        Objects.requireNonNull(outcome);
        if (outcome == Outcome.LEASE_EXPIRED) throw new IllegalArgumentException("租约过期仅由数据库接管路径登记");
        try {
            var row = executions.load(lease.submission().executionId(), "FOR UPDATE SKIP LOCKED");
            if (!JobExecutionRepositoryImpl.matchesLease(row, lease)) return false;
            var now = executions.databaseNow();
            if (!row.expiresAt().isAfter(now) || now.isBefore(row.startTime())) return false;
            executions.complete(row, outcome, now);
            return true;
        } catch (DataAccessException failure) {
            throw translate(failure);
        }
    }

    /** {@inheritDoc} */
    @Override
    public UUID replay(ReplayRequest request) {
        requireWrite();
        Objects.requireNonNull(request);
        try {
            var known = registration(request.requestId());
            if (known != null) return registeredTarget(request, known);
            var initial = death(request.deadId(), false);
            if (initial == null) throw new StratumException(CommonErrorCode.NOT_FOUND, "任务死亡记录不存在");
            var row = executions.load(initial.executionId(), "FOR UPDATE SKIP LOCKED");
            if (row == null) throw conflict("任务执行记录正在变更");
            var dead = death(request.deadId(), true);
            if (dead == null || !dead.executionId().equals(row.submission().executionId())
                    || row.state() != State.DEAD || !dead.state().equals("DEAD") || dead.cycle() != row.cycle()
                    || dead.lastAttempt() != row.attempt() || dead.failures() != row.failures()) {
                throw conflict("只有当前任务死亡周期可以恢复");
            }
            if (row.cycle() == Long.MAX_VALUE || row.attempt() == Long.MAX_VALUE || row.version() == Long.MAX_VALUE) throw conflict("任务周期或版本达到上限");
            var now = executions.databaseNow();
            long cycle = row.cycle() + 1;
            UUID execution = row.submission().executionId();
            var trace = executions.captureStandardTrace();
            String parent = trace.map(StandardTraceContext.Carrier::traceparent).orElse(null);
            String state = trace.map(StandardTraceContext.Carrier::tracestate).filter(value -> !value.isEmpty()).orElse(null);
            jdbc.update("""
                    UPDATE job_execution SET state='WAITING',cycle_no=?,failure_count=0,trace_id=?,next_attempt_at=?,
                    standard_traceparent=?,standard_tracestate=?,finished_at=NULL,updated_at=?,version=version+1 WHERE execution_id=?
                    """, cycle, request.traceId(), time(now), parent, state, time(now), execution.toString());
            jdbc.update("UPDATE dead_job SET state='REPLAY_REQUESTED' WHERE dead_id=?", request.deadId().toString());
            jdbc.update("""
                    INSERT INTO job_replay_request(request_id,dead_id,execution_id,resumed_cycle,trace_id)
                    VALUES (?,?,?,?,?)
                    """, request.requestId().toString(), request.deadId().toString(), execution.toString(), cycle, request.traceId());
            return execution;
        } catch (DataAccessException failure) {
            throw translate(failure);
        }
    }

    /**
     * 读取已提交且不可变的恢复请求，不对不存在的主键先加间隙锁。
     * @param requestId 恢复请求UUID
     * @return 首次登记目标，不存在为null
     */
    private Registration registration(UUID requestId) {
        var rows = jdbc.query("SELECT dead_id,execution_id FROM job_replay_request WHERE request_id=?",
                (rs, index) -> new Registration(UUID.fromString(rs.getString("dead_id")), UUID.fromString(rs.getString("execution_id"))),
                requestId.toString());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * 已登记请求仅可重放同一死亡目标，历史回执不随当前执行状态变动。
     * @param request 本次请求
     * @param registration 不可变登记结果
     * @return 首次执行UUID
     * @throws StratumException 请求UUID已用于另一目标
     */
    private UUID registeredTarget(ReplayRequest request, Registration registration) {
        if (!request.deadId().equals(registration.deadId())) throw conflict("任务恢复请求身份已用于另一目标");
        return registration.executionId();
    }

    /**
     * 先用非锁定目标查找执行身份，取得执行锁之后再按统一顺序锁定死亡记录。
     * @param deadId 死亡UUID
     * @param locked 是否执行当前读并跳过忙行
     * @return 死亡快照，不存在或忙时为null
     */
    private Death death(UUID deadId, boolean locked) {
        var rows = jdbc.query("SELECT execution_id,cycle_no,last_attempt,failure_count,state FROM dead_job WHERE dead_id=?"
                        + (locked ? " FOR UPDATE SKIP LOCKED" : ""),
                (rs, index) -> new Death(UUID.fromString(rs.getString("execution_id")), rs.getLong("cycle_no"),
                        rs.getLong("last_attempt"), rs.getInt("failure_count"), rs.getString("state")), deadId.toString());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * 值快照：不可变恢复请求关联，不含正文或竞争令牌。
     * @param deadId 原死亡UUID
     * @param executionId 原执行UUID
     */
    private record Registration(UUID deadId, UUID executionId) { }

    /**
     * 值快照：死亡周期资格，不能作为新的可执行任务输入。
     * @param executionId 原执行UUID
     * @param cycle 失败周期
     * @param lastAttempt 最后失败尝试
     * @param failures 周期失败次数
     * @param state 死亡状态
     */
    private record Death(UUID executionId, long cycle, long lastAttempt, int failures, String state) { }
}
