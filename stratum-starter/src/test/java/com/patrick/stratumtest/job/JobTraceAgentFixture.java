package com.patrick.stratumtest.job;

import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.patrick.stratum.application.job.JobExecutionApplicationService;
import com.patrick.stratum.application.job.JobHandler;
import com.patrick.stratum.common.trace.StandardTraceContext;
import com.patrick.stratum.domain.job.JobDefinition;
import com.patrick.stratum.domain.job.JobExecutionRepository.Submission;
import com.patrick.stratum.domain.job.JobRecoveryRepository.ReplayRequest;
import com.patrick.stratum.starter.StratumStarterApplication;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.awaitility.Awaitility.await;

/**
 * 独立JVM中以真实Agent、Quartz及MariaDB验证任务标准追踪。
 * <p>示例：由测试启动本类的{@link #main(String[])}，并提供隔离测试环境。</p>
 */
public final class JobTraceAgentFixture {
    private static final String NORMAL_PARENT = "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01";
    private static final String RETRY_PARENT = "00-cccccccccccccccccccccccccccccccc-dddddddddddddddd-01";
    private static final String MANUAL_PARENT = "00-eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee-ffffffffffffffff-01";

    /** 禁止实例化独立进程入口。 */
    private JobTraceAgentFixture() { }

    /**
     * 使用独立数据库运行实际固定Quartz任务，并在退出前删除自身随机命名空间。
     * @param args 不使用的命令行参数
     * @throws Exception 数据库、进程或调度失败
     * @apiNote 示例：仅由Agent集成测试以显式本机数据库启动。
     */
    public static void main(String[] args) throws Exception {
        String url = required("STRATUM_IT_DB_URL");
        if (!url.matches("jdbc:mariadb://(127\\.0\\.0\\.1|localhost):[0-9]+/stratum_it")) {
            throw new IllegalArgumentException("任务追踪夹具仅允许本机独立测试数据库");
        }
        String name = required("STRATUM_IT_JOB_NAME"); String code = required("STRATUM_IT_JOB_CODE");
        if (!name.matches("jobtrace-[a-f0-9]{32}") || !code.matches("jobtrace\\.[a-f0-9]{32}")) {
            throw new IllegalArgumentException("任务追踪夹具必须使用随机测试命名空间");
        }
        System.setProperty("spring.datasource.url", url);
        System.setProperty("spring.datasource.username", required("STRATUM_IT_DB_USERNAME"));
        System.setProperty("spring.datasource.password", required("STRATUM_IT_DB_PASSWORD"));
        System.setProperty("spring.data.redis.host", "127.0.0.1");
        System.setProperty("spring.data.redis.port", required("STRATUM_IT_REDIS_PORT"));
        System.setProperty("spring.data.redis.password", System.getenv().getOrDefault("STRATUM_IT_REDIS_PASSWORD", ""));
        var ids = new ArrayList<UUID>();
        try {
            try (var context = new SpringApplicationBuilder(StratumStarterApplication.class, ProbeConfiguration.class).run(
                    "--spring.main.web-application-type=none", "--spring.main.banner-mode=off", "--stratum.jobs.enabled=true",
                    "--stratum.jobs.workers=1", "--stratum.jobs.name=" + name,
                    "--spring.flyway.locations=classpath:db/migration,classpath:db/testmigration")) {
                var jobs = context.getBean(JobExecutionApplicationService.class); var jdbc = context.getBean(JdbcTemplate.class);
                var trace = context.getBean(StandardTraceContext.class);
                UUID normal;
                try (var restored = trace.restore(trace.normalize(NORMAL_PARENT, "vendor=normal"));
                     var baggage = Context.current().with(Baggage.builder().put("fixture", "private-baggage").build()).makeCurrent()) {
                    normal = register(jobs, code, "normal", 1); ids.add(normal);
                }
                awaitSuccess(jdbc, normal);
                UUID historical;
                try (var restored = trace.restore(Optional.empty())) { historical = register(jobs, code, "historical", 1); ids.add(historical); }
                awaitSuccess(jdbc, historical);
                UUID retry;
                try (var restored = trace.restore(trace.normalize(RETRY_PARENT, "vendor=retry"))) {
                    retry = register(jobs, code, "retry", 2); ids.add(retry);
                }
                awaitSuccess(jdbc, retry);
                var attempts = jdbc.queryForList("SELECT result FROM job_attempt WHERE execution_id=? ORDER BY attempt_no", String.class, retry.toString());
                if (!attempts.equals(List.of("FAILED", "SUCCEEDED"))) throw new IllegalStateException("任务重试结果不匹配");
                verifyManualRecovery(jobs, jdbc, trace, code, ids, "manual", true);
                verifyManualRecovery(jobs, jdbc, trace, code, ids, "manualroot", false);
                System.out.println("STRATUM_JOB_DONE|normal|historical|retry|manual|manualroot");
            }
        } finally { cleanup(url, name, code, ids); }
    }

    /**
     * 从当前标准上下文登记单次任务。
     * @param jobs 实际任务登记入口
     * @param code 随机固定代码
     * @param scenario 受限场景名称
     * @param attempts 最大尝试次数
     * @return 执行UUID
     */
    private static UUID register(JobExecutionApplicationService jobs, String code, String scenario, int attempts) {
        UUID id = UUID.randomUUID(); Instant when = Instant.parse("2026-01-01T00:00:00Z");
        var definition = new JobDefinition(code, JobDefinition.Type.ONCE, Optional.empty(), Optional.of(when), 15,
                new JobDefinition.RetryPolicy(attempts, Duration.ofMillis(100), Duration.ofMillis(100)), JobDefinition.IdempotencyRule.EXPLICIT_KEY);
        return jobs.register(new Submission(id, definition, when, id.toString(), "{\"scenario\":\"" + scenario + "\"}", "job-flow-" + scenario));
    }

    /**
     * 等待真实Quartz处理和数据库成功记录，不直接调用任务执行器。
     * @param jdbc 测试数据库客户端
     * @param id 当前用例任务UUID
     * @throws org.awaitility.core.ConditionTimeoutException 任务未在限定时间内成功
     */
    private static void awaitSuccess(JdbcTemplate jdbc, UUID id) {
        awaitState(jdbc, id, "SUCCEEDED");
    }

    /**
     * 等待当前用例的实际数据库状态。
     * @param jdbc 测试数据库客户端
     * @param id 当前用例任务UUID
     * @param state 固定预期状态
     * @throws org.awaitility.core.ConditionTimeoutException 状态未在限定时间内出现
     */
    private static void awaitState(JdbcTemplate jdbc, UUID id, String state) {
        await().atMost(Duration.ofSeconds(30)).until(() -> state.equals(jdbc.queryForObject(
                "SELECT state FROM job_execution WHERE execution_id=?", String.class, id.toString())));
    }

    /**
     * 首周期失败后从指定恢复上下文启动新周期，只验证恢复后的首次执行。
     * @param jobs 实际任务状态入口
     * @param jdbc 测试数据库客户端
     * @param trace 标准追踪边界
     * @param code 随机固定代码
     * @param ids 本次拥有的执行身份
     * @param scenario 固定测试场景
     * @param hasParent 恢复操作是否携带有效上下文
     */
    private static void verifyManualRecovery(JobExecutionApplicationService jobs, JdbcTemplate jdbc, StandardTraceContext trace,
            String code, List<UUID> ids, String scenario, boolean hasParent) {
        UUID id;
        try (var restored = trace.restore(trace.normalize(NORMAL_PARENT, "vendor=initial"))) {
            id = register(jobs, code, scenario, 1); ids.add(id);
        }
        awaitState(jdbc, id, "DEAD");
        String dead = jdbc.queryForObject("SELECT dead_id FROM dead_job WHERE execution_id=? AND cycle_no=0", String.class, id.toString());
        var parent = hasParent ? trace.normalize(MANUAL_PARENT, "vendor=manual") : Optional.<StandardTraceContext.Carrier>empty();
        try (var restored = trace.restore(parent)) {
            jobs.replay(new ReplayRequest(UUID.randomUUID(), UUID.fromString(dead), "job-flow-recovery-" + scenario));
        }
        awaitSuccess(jdbc, id);
        var row = jdbc.queryForMap("SELECT initial_standard_traceparent,standard_traceparent,cycle_no FROM job_execution WHERE execution_id=?", id.toString());
        if (!NORMAL_PARENT.equals(row.get("initial_standard_traceparent"))
                || !java.util.Objects.equals(hasParent ? MANUAL_PARENT : null, row.get("standard_traceparent"))
                || ((Number) row.get("cycle_no")).longValue() != 1) throw new IllegalStateException("恢复上下文或周期不匹配");
        var attempts = jdbc.queryForList("SELECT result FROM job_attempt WHERE execution_id=? ORDER BY attempt_no", String.class, id.toString());
        if (!attempts.equals(List.of("FAILED", "SUCCEEDED"))) throw new IllegalStateException("恢复周期尝试不匹配");
    }

    /**
     * 清理仅由本次随机身份创建的任务、调度与锁记录。
     * @param url 已验证的本机专用数据库地址
     * @param name 本次随机调度命名空间
     * @param code 本次随机任务代码
     * @param ids 本次生成的执行UUID
     * @throws Exception 数据库清理失败
     */
    private static void cleanup(String url, String name, String code, List<UUID> ids) throws Exception {
        try (var connection = DriverManager.getConnection(url, required("STRATUM_IT_DB_USERNAME"), required("STRATUM_IT_DB_PASSWORD"))) {
            for (UUID id : ids) {
                for (String table : List.of("job_replay_request", "dead_job", "job_attempt", "job_execution")) {
                    try (var statement = connection.prepareStatement("DELETE FROM " + table + " WHERE execution_id=?")) {
                        statement.setString(1, id.toString()); statement.executeUpdate();
                    }
                }
            }
            for (String table : List.of("STRATUM_QRTZ_SIMPLE_TRIGGERS", "STRATUM_QRTZ_CRON_TRIGGERS", "STRATUM_QRTZ_TRIGGERS",
                    "STRATUM_QRTZ_JOB_DETAILS", "STRATUM_QRTZ_FIRED_TRIGGERS", "STRATUM_QRTZ_LOCKS", "STRATUM_QRTZ_SCHEDULER_STATE")) {
                try (var statement = connection.prepareStatement("DELETE FROM " + table + " WHERE SCHED_NAME=?")) {
                    statement.setString(1, name); statement.executeUpdate();
                }
            }
            for (String prefix : List.of("lock:job:", "lock:job:trigger:run.", "lock:job:trigger:schedule.")) {
                try (var statement = connection.prepareStatement("DELETE FROM stratum_shedlock WHERE name=?")) {
                    statement.setString(1, prefix + code); statement.executeUpdate();
                }
            }
        }
    }

    /**
     * 获取必需测试配置，不输出配置值。
     * @param name 环境变量名称
     * @return 非空值
     * @throws IllegalStateException 环境配置缺失
     */
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("任务追踪夹具缺少配置：" + name);
        return value;
    }

    /**
     * 测试专用固定处理器配置，不进入生产组件扫描。
     * <p>示例：由{@link SpringApplicationBuilder}显式注册本配置。</p>
     */
    @Configuration(proxyBeanMethods = false)
    public static class ProbeConfiguration {
        /**
         * 创建无业务副作用的固定测试处理器。
         * @return 当前进程唯一处理器
         */
        @Bean
        JobHandler traceProbe() { return new Probe(); }
    }

    /** 为每次实际执行创建可导出的测试跨度，并在首个重试场景模拟失败。 */
    private static final class Probe implements JobHandler {
        private static final Logger LOG = LoggerFactory.getLogger(Probe.class);
        private final ConcurrentHashMap<UUID, AtomicInteger> attempts = new ConcurrentHashMap<>();

        /** {@inheritDoc} */
        @Override public String jobCode() { return required("STRATUM_IT_JOB_CODE"); }

        /** {@inheritDoc} */
        @Override public void execute(Submission input) {
            String scenario = switch (input.arguments()) {
                case "{\"scenario\":\"normal\"}" -> "normal";
                case "{\"scenario\":\"historical\"}" -> "historical";
                case "{\"scenario\":\"retry\"}" -> "retry";
                case "{\"scenario\":\"manual\"}" -> "manual";
                case "{\"scenario\":\"manualroot\"}" -> "manualroot";
                default -> throw new IllegalArgumentException("未知测试场景");
            };
            int attempt = attempts.computeIfAbsent(input.executionId(), ignored -> new AtomicInteger()).incrementAndGet();
            var upstream = Span.current().getSpanContext();
            String before = upstream.isValid() ? upstream.getTraceId() + ":" + upstream.getSpanId() + ":" + upstream.isRemote() : "none";
            String state = upstream.isValid() ? String.valueOf(upstream.getTraceState().get("vendor")) : "none";
            boolean baggage = Baggage.current().getEntryValue("fixture") != null;
            var span = GlobalOpenTelemetry.getTracer("stratum-test-job").spanBuilder("stratum.test.job-work." + scenario).startSpan();
            try (var ignored = span.makeCurrent()) {
                var active = span.getSpanContext();
                if (!active.isValid()) throw new IllegalStateException("Agent未提供任务测试跨度");
                LOG.info("job-trace-fixture {} {}", scenario, attempt);
                System.out.println("STRATUM_JOB_TRACE|" + scenario + "|" + attempt + "|" + before + "|"
                        + active.getTraceId() + ":" + active.getSpanId() + "|" + baggage + "|" + state);
                if (!scenario.equals("normal") && !scenario.equals("historical") && attempt == 1) {
                    throw new IllegalStateException("test-failure");
                }
            } finally { span.end(); }
        }
    }
}
