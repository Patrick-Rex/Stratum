package com.patrick.stratum.infrastructure.job;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.common.trace.StandardTraceContext;
import com.patrick.stratum.domain.job.JobCancellationRepository;
import com.patrick.stratum.domain.job.JobDefinition;
import com.patrick.stratum.domain.job.JobExecutionRepository;
import com.patrick.stratum.domain.job.JobLeaseAuthority;
import com.patrick.stratum.domain.job.JobRecoveryRepository.Outcome;
import com.patrick.stratum.infrastructure.tracing.OpenTelemetryTraceContext;
import org.quartz.CronExpression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 在已有应用工作单元内幂等登记及竞争领取任务；数据库时间控制到期，执行仍在事务外。
 * <p>示例：由Application调用register和claim，不从Job直接操作本适配。</p>
 */
@Repository
public class JobExecutionRepositoryImpl implements JobExecutionRepository, JobCancellationRepository, JobLeaseAuthority {
    private static final Logger LOG = LoggerFactory.getLogger(JobExecutionRepositoryImpl.class);
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final StandardTraceContext standardTrace;

    /**
     * 使用同一数据源参与应用事务，只为任务SQL设置五秒语句预算，不改变共享JdbcTemplate。
     * @param dataSource 应用工作单元使用的数据源
     * @param json 项目JSON映射器
     * @param standardTrace 标准追踪边界
     * @throws NullPointerException 必需依赖为空
     * @apiNote 示例：由Spring注入数据源和映射器创建组件，无新连接池。
     */
    @Autowired
    public JobExecutionRepositoryImpl(DataSource dataSource, JsonMapper json, StandardTraceContext standardTrace) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource));
        this.jdbc.setQueryTimeout(5);
        this.json = Objects.requireNonNull(json); this.standardTrace = Objects.requireNonNull(standardTrace);
    }

    /**
     * 保留已有手动构造方式，使用无状态标准追踪适配器。
     * @param dataSource 应用工作单元使用的数据源
     * @param json 项目JSON映射器
     * @throws NullPointerException 必需依赖为空
     * @apiNote 示例：{@code new JobExecutionRepositoryImpl(dataSource, mapper)}。
     */
    public JobExecutionRepositoryImpl(DataSource dataSource, JsonMapper json) {
        this(dataSource, json, new OpenTelemetryTraceContext());
    }

    /** {@inheritDoc} */
    @Override
    public UUID register(Submission submission) {
        requireWrite();
        Objects.requireNonNull(submission);
        var arguments = arguments(submission.arguments());
        var definition = submission.definition();
        if (definition.cron().isPresent() && !CronExpression.isValidExpression(definition.cron().orElseThrow())) {
            throw new StratumException(CommonErrorCode.BAD_REQUEST, "任务Cron表达式不合法");
        }
        var trace = captureStandardTrace();
        String parent = trace.map(StandardTraceContext.Carrier::traceparent).orElse(null);
        String state = trace.map(StandardTraceContext.Carrier::tracestate).filter(value -> !value.isEmpty()).orElse(null);
        try {
            jdbc.update("""
                    INSERT INTO job_execution(execution_id,job_code,job_type,cron_expression,trigger_at,
                    timeout_sec,max_attempts,initial_delay_ms,max_delay_ms,idempotency_rule,schedule_time,
                    idempotency_key,arguments,initial_trace_id,trace_id,next_attempt_at,
                    initial_standard_traceparent,initial_standard_tracestate,standard_traceparent,standard_tracestate)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    ON DUPLICATE KEY UPDATE execution_id=job_execution.execution_id
                    """, submission.executionId().toString(), definition.jobCode(), definition.jobType().name(),
                    definition.cron().orElse(null), definition.triggerAt().map(JobExecutionRepositoryImpl::time).orElse(null),
                    definition.timeoutSec(), definition.retryPolicy().maxAttempts(), definition.retryPolicy().initialDelay().toMillis(),
                    definition.retryPolicy().maxDelay().toMillis(), definition.idempotencyKeyRule().name(), time(submission.scheduleTime()),
                    submission.idempotencyKey(), submission.arguments(), submission.traceId(), submission.traceId(), time(submission.scheduleTime()),
                    parent, state, parent, state);
            var sameId = load(submission.executionId(), "FOR UPDATE");
            if (sameId != null && (!sameId.submission().definition().jobCode().equals(definition.jobCode())
                    || !sameId.submission().idempotencyKey().equals(submission.idempotencyKey()))) {
                throw conflict("任务执行身份已用于其他幂等键");
            }
            var rows = jdbc.query("SELECT * FROM job_execution WHERE job_code=? AND idempotency_key=? FOR UPDATE",
                    JobExecutionRepositoryImpl::map, definition.jobCode(), submission.idempotencyKey());
            if (rows.size() != 1) throw conflict("任务登记结果已变更");
            var original = rows.getFirst().submission();
            if (!original.definition().equals(definition) || !original.scheduleTime().equals(submission.scheduleTime())
                    || !arguments(original.arguments()).equals(arguments)) {
                throw conflict("任务幂等键对应的输入不一致");
            }
            return original.executionId();
        } catch (DataAccessException failure) {
            throw translate(failure);
        }
    }

    /**
     * 捕获并规范化任务登记上下文；追踪组件故障时安全告警并保持空值。
     * @return 可持久化的标准上下文，缺失或故障时为空
     */
    private Optional<StandardTraceContext.Carrier> captureStandardTrace() {
        try {
            return Objects.requireNonNull(standardTrace.capture()).flatMap(value ->
                    Objects.requireNonNull(standardTrace.normalize(value.traceparent(), value.tracestate())));
        } catch (RuntimeException failure) {
            try { LOG.atWarn().addKeyValue("errorType", failure.getClass().getSimpleName()).log("任务标准追踪上下文捕获失败"); }
            catch (RuntimeException ignored) { /* 告警故障不能阻止任务登记。 */ }
            return Optional.empty();
        }
    }

    /** {@inheritDoc} */
    @Override
    public List<UUID> due(String jobCode, int limit) {
        requireWrite();
        if (jobCode == null || !jobCode.matches("[a-z][a-z0-9.-]{0,63}") || limit < 1 || limit > 128) {
            throw new IllegalArgumentException("任务候选代码或数量不合法");
        }
        return List.copyOf(jdbc.query("""
                (SELECT execution_id,next_attempt_at AS ready_at FROM job_execution
                 WHERE job_code=? AND state IN ('WAITING','RETRY_WAIT') AND next_attempt_at<=UTC_TIMESTAMP(6)
                 ORDER BY next_attempt_at,execution_id LIMIT ?)
                UNION ALL
                (SELECT execution_id,lease_until AS ready_at FROM job_execution
                 WHERE job_code=? AND state='RUNNING' AND lease_until<=UTC_TIMESTAMP(6)
                 ORDER BY lease_until,execution_id LIMIT ?)
                ORDER BY ready_at,execution_id LIMIT ?
                """, (rs, row) -> UUID.fromString(rs.getString("execution_id")), jobCode, limit, jobCode, limit, limit));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Lease> claim(UUID executionId) {
        requireWrite();
        Objects.requireNonNull(executionId);
        try {
            var row = load(executionId, "FOR UPDATE SKIP LOCKED");
            if (row == null || row.state() == State.SUCCEEDED || row.state() == State.DEAD || row.state() == State.CANCELLED) return Optional.empty();
            Instant now = databaseNow();
            if (row.state() == State.RUNNING) {
                if (!row.expiresAt().isAfter(now)) complete(row, Outcome.LEASE_EXPIRED, now);
                return Optional.empty();
            }
            if (row.nextAttempt().isAfter(now)) return Optional.empty();
            if (row.attempt() == Long.MAX_VALUE || row.version() == Long.MAX_VALUE) throw conflict("任务累计序号达到上限");
            UUID token = UUID.randomUUID();
            long attempt = row.attempt() + 1;
            Instant expires = now.plusSeconds(row.submission().definition().timeoutSec() + 30L);
            jdbc.update("""
                    UPDATE job_execution SET state='RUNNING',attempt_no=?,lease_token=?,start_time=?,lease_until=?,
                    updated_at=?,version=version+1 WHERE execution_id=?
                    """, attempt, token.toString(), time(now), time(expires), time(now), executionId.toString());
            jdbc.update("""
                    INSERT INTO job_attempt(execution_id,attempt_no,cycle_no,lease_token,schedule_time,start_time,result,trace_id)
                    VALUES (?,?,?,?,?,?,'RUNNING',?)
                    """, executionId.toString(), attempt, row.cycle(), token.toString(), time(row.submission().scheduleTime()),
                    time(now), row.submission().traceId());
            return Optional.of(new Lease(row.submission(), token, now, expires, attempt, row.failures(), row.cycle()));
        } catch (DataAccessException failure) {
            throw translate(failure);
        }
    }

    /** {@inheritDoc} */
    @Override
    public JobCancellationRepository.Result cancelUnstarted(UUID executionId, String jobCode) {
        requireWrite();
        Objects.requireNonNull(executionId, "任务执行身份不能为空");
        if (jobCode == null || !jobCode.matches("[a-z][a-z0-9.-]{0,63}")) throw new IllegalArgumentException("任务代码不合法");
        try {
            var row = load(executionId, "FOR UPDATE");
            if (row == null || !row.submission().definition().jobCode().equals(jobCode)) return JobCancellationRepository.Result.NOT_FOUND;
            if (row.state() == State.CANCELLED) return JobCancellationRepository.Result.CANCELLED;
            if (row.state() != State.WAITING || row.attempt() != 0 || row.cycle() != 0) return JobCancellationRepository.Result.ALREADY_STARTED;
            if (row.version() == Long.MAX_VALUE) throw conflict("任务版本达到上限");
            var now = databaseNow();
            int changed = jdbc.update("""
                    UPDATE job_execution SET state='CANCELLED',finished_at=?,updated_at=?,version=version+1
                    WHERE execution_id=? AND state='WAITING' AND attempt_no=0 AND cycle_no=0
                    """, time(now), time(now), executionId.toString());
            if (changed != 1) throw conflict("任务取消资格已变更");
            return JobCancellationRepository.Result.CANCELLED;
        } catch (DataAccessException failure) {
            throw translate(failure);
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean lockCurrent(Lease lease) {
        requireWrite(); Objects.requireNonNull(lease, "任务领取不能为空");
        try {
            var row = load(lease.submission().executionId(), "FOR UPDATE SKIP LOCKED");
            if (!matchesLease(row, lease)) return false;
            var now = databaseNow();
            return row.expiresAt().isAfter(now) && !now.isBefore(row.startTime());
        } catch (DataAccessException failure) { throw translate(failure); }
    }

    /**
     * 比较受锁定的执行行和完整领取证据；数据库时钟需由调用方另行核验。
     * @param row 当前行或锁忙/不存在时为空
     * @param lease 已验证非空的领取证据
     * @return 所有固定字段和令牌匹配时为true
     */
    static boolean matchesLease(Row row, Lease lease) {
        return row != null && row.state() == State.RUNNING && lease.token().equals(row.token())
                && row.attempt() == lease.attempt() && row.failures() == lease.failures() && row.cycle() == lease.cycle()
                && lease.submission().equals(row.submission()) && lease.startTime().equals(row.startTime())
                && lease.expiresAt().equals(row.expiresAt());
    }

    /**
     * 在持有执行行锁的工作单元中写回固定结果，历史与退避/死亡/恢复状态原子更新。
     * @param row 已由调用者当前读锁定的任务
     * @param outcome 固定结果，不含异常正文
     * @param now 锁定后重新取得的数据库时刻
     */
    void complete(Row row, Outcome outcome, Instant now) {
        requireWrite();
        if (row.version() == Long.MAX_VALUE) throw conflict("任务版本达到上限");
        String id = row.submission().executionId().toString();
        int changed = jdbc.update("""
                UPDATE job_attempt SET result=?,end_time=?,duration_ms=?
                WHERE execution_id=? AND attempt_no=? AND cycle_no=? AND lease_token=? AND result='RUNNING'
                """, outcome.name(), time(now), Duration.between(row.startTime(), now).toMillis(), id,
                row.attempt(), row.cycle(), row.token().toString());
        if (changed != 1) throw conflict("任务缺少对应运行尝试");
        boolean success = outcome == Outcome.SUCCEEDED;
        int failures = success ? row.failures() : row.failures() + 1;
        var policy = row.submission().definition().retryPolicy();
        boolean dead = !success && failures >= policy.maxAttempts();
        Instant next = success || dead ? now : now.plus(policy.delayForFailure(failures));
        changed = jdbc.update("""
                UPDATE job_execution SET state=?,failure_count=?,lease_token=NULL,start_time=NULL,lease_until=NULL,
                next_attempt_at=?,finished_at=?,updated_at=?,version=version+1 WHERE execution_id=? AND lease_token=?
                """, success ? "SUCCEEDED" : dead ? "DEAD" : "RETRY_WAIT", failures, time(next),
                success || dead ? time(now) : null, time(now), id, row.token().toString());
        if (changed != 1) throw conflict("任务领取状态已变更");
        if (dead) {
            jdbc.update("""
                    INSERT INTO dead_job(dead_id,execution_id,cycle_no,last_attempt,failure_count,reason,failed_at)
                    VALUES (?,?,?,?,?,?,?)
                    """, UUID.randomUUID().toString(), id, row.cycle(), row.attempt(), failures, outcome.name(), time(now));
        } else if (success) {
            jdbc.update("""
                    UPDATE dead_job SET state='RESOLVED',resolved_at=?
                    WHERE execution_id=? AND state='REPLAY_REQUESTED'
                    """, time(now), id);
        }
    }

    /**
     * 按调用者选择的固定锁方式读取一条技术快照，禁止外部输入拼接SQL。
     * @param executionId 执行UUID
     * @param lock 本类常量锁后缀
     * @return 技术快照，未找到或被跳过为null
     */
    Row load(UUID executionId, String lock) {
        var rows = jdbc.query("SELECT * FROM job_execution WHERE execution_id=? " + lock,
                JobExecutionRepositoryImpl::map, executionId.toString());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * 将DATETIME字段作为UTC本地时间读取，避免依赖JVM或JDBC默认时区。
     * @param rs 当前行
     * @param index JDBC行号
     * @return 包含不可变输入及当前协调字段的快照
     * @throws SQLException 读取失败
     */
    private static Row map(ResultSet rs, int index) throws SQLException {
        var definition = new JobDefinition(rs.getString("job_code"), JobDefinition.Type.valueOf(rs.getString("job_type")),
                Optional.ofNullable(rs.getString("cron_expression")), Optional.ofNullable(instant(rs, "trigger_at")),
                rs.getInt("timeout_sec"), new JobDefinition.RetryPolicy(rs.getInt("max_attempts"),
                    Duration.ofMillis(rs.getLong("initial_delay_ms")), Duration.ofMillis(rs.getLong("max_delay_ms"))),
                JobDefinition.IdempotencyRule.valueOf(rs.getString("idempotency_rule")));
        var submission = new Submission(UUID.fromString(rs.getString("execution_id")), definition,
                instant(rs, "schedule_time"), rs.getString("idempotency_key"), rs.getString("arguments"), rs.getString("trace_id"));
        String token = rs.getString("lease_token");
        return new Row(submission, State.valueOf(rs.getString("state")), rs.getLong("attempt_no"), rs.getInt("failure_count"),
                rs.getLong("cycle_no"), token == null ? null : UUID.fromString(token), instant(rs, "start_time"),
                instant(rs, "lease_until"), instant(rs, "next_attempt_at"), rs.getLong("version"));
    }

    /**
     * 在已取得行锁之后执行独立时钟查询，不使用可能等待前的语句开始时间。
     * @return 数据库UTC微秒时刻
     */
    Instant databaseNow() {
        return jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }

    /**
     * 读取可空UTC技术时间字段。
     * @param rs 当前行
     * @param field 固定列名
     * @return 可空时刻
     * @throws SQLException 驱动读取失败
     */
    private static Instant instant(ResultSet rs, String field) throws SQLException {
        LocalDateTime value = rs.getObject(field, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    /**
     * 将UTC时刻绑定为DATETIME，不通过默认时区的Timestamp转换。
     * @param instant UTC时刻
     * @return UTC本地时间字段值
     */
    static LocalDateTime time(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * 严格解析有界JSON，保留大整数和小数，不接受重复键或尾随内容。
     * @param raw 参数原文，不写入异常或日志
     * @return 无损JSON值
     * @throws StratumException 输入不合法时返回固定40000
     */
    private JsonNode arguments(String raw) {
        try {
            return json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(raw);
        } catch (RuntimeException failure) {
            throw new StratumException(CommonErrorCode.BAD_REQUEST, "任务参数不是合法JSON");
        }
    }

    /**
     * 不允许仓储自行开启事务或进入只读工作单元。
     * @throws IllegalStateException 没有活动写工作单元
     */
    static void requireWrite() {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("任务存储必须参与已有应用写工作单元");
        }
    }

    /**
     * 生成不含原输入的统一冲突。
     * @param message 固定安全说明
     * @return 可由新工作单元重试的40900
     */
    static StratumException conflict(String message) {
        return new StratumException(CommonErrorCode.CONFLICT, message);
    }

    /**
     * 唯一键、快照、锁等待及串行化冲突必须回滚完整工作单元，仓储不内联重试。
     * @param failure JDBC异常
     * @return 固定冲突或原异常
     */
    static RuntimeException translate(DataAccessException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && (sql.getErrorCode() == 1062 || sql.getErrorCode() == 1020
                    || sql.getErrorCode() == 1205 || "40001".equals(sql.getSQLState()))) {
                return conflict("任务存储状态发生竞争，请在新工作单元重试");
            }
        }
        return failure;
    }

    /**
     * 值快照：技术协调字段只在本适配使用，默认摘要隐藏输入和令牌。
     * @param submission 已登记输入
     * @param state 当前状态
     * @param attempt 累计尝试
     * @param failures 当前周期失败次数
     * @param cycle 恢复周期
     * @param token 可空令牌
     * @param startTime 可空运行开始时间
     * @param expiresAt 可空租期
     * @param nextAttempt 下次到期时刻
     * @param version 乐观版本
     */
    record Row(Submission submission, State state, long attempt, int failures, long cycle,
            UUID token, Instant startTime, Instant expiresAt, Instant nextAttempt, long version) {
        /**
         * 只输出安全执行身份与状态。
         * @return 安全技术摘要
         */
        @Override
        public String toString() { return "JobRow[executionId=" + submission.executionId() + ", state=" + state + "]"; }
    }
}
