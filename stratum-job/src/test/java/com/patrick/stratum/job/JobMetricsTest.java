package com.patrick.stratum.job;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.patrick.stratum.application.job.JobHandler;
import com.patrick.stratum.domain.job.JobExecutionRepository.Submission;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证固定任务指标的边界、耗时与有限标签。 */
class JobMetricsTest {
    /** 成功与失败分别记录耗时，只有跨阈值的实际工作累计慢计数。 */
    @Test
    void recordsActualOutcomesAndSlowThreshold() {
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = metrics(registry);
            assertFalse(metrics.record("job.probe", JobExecutionResult.SUCCEEDED, TimeUnit.MILLISECONDS.toNanos(499)));
            assertTrue(metrics.record("job.probe", JobExecutionResult.FAILED, TimeUnit.MILLISECONDS.toNanos(500)));
            assertTrue(metrics.record("job.probe", JobExecutionResult.TIMED_OUT, TimeUnit.SECONDS.toNanos(1)));
            assertFalse(metrics.record("job.probe", JobExecutionResult.STATE_UNCERTAIN, 0));
            assertEquals(1, registry.get("stratum.job.duration").tags("job", "job.probe", "result", "succeeded").timer().count());
            assertEquals(1, registry.get("stratum.job.slow").tags("job", "job.probe", "result", "failed").counter().count());
            assertEquals(1, registry.get("stratum.job.slow").tags("job", "job.probe", "result", "timed_out").counter().count());
            assertNull(registry.find("stratum.job.slow").tags("result", "succeeded").counter());
            assertTrue(registry.getMeters().stream().allMatch(meter -> meter.getId().getTags().stream()
                    .allMatch(tag -> List.of("job", "result").contains(tag.getKey()))));
        } finally { registry.close(); }
    }

    /** 非实际结束结果、未注册代码及无效耗时不能产生任意高基数指标。 */
    @Test
    void rejectsUnregisteredAndNonterminalOutcomes() {
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = metrics(registry);
            for (var result : List.of(JobExecutionResult.SKIPPED, JobExecutionResult.STILL_RUNNING,
                    JobExecutionResult.CAPACITY_REJECTED, JobExecutionResult.CLOSED)) {
                assertThrows(IllegalArgumentException.class, () -> metrics.record("job.probe", result, 1));
            }
            assertThrows(IllegalArgumentException.class, () -> metrics.record(UUID.randomUUID().toString(), JobExecutionResult.SUCCEEDED, 1));
            assertThrows(IllegalArgumentException.class, () -> metrics.record(null, JobExecutionResult.SUCCEEDED, 1));
            assertThrows(IllegalArgumentException.class, () -> metrics.record("job.probe", JobExecutionResult.SUCCEEDED, -1));
            assertTrue(registry.getMeters().isEmpty());
        } finally { registry.close(); }
    }

    /** 阈值必须在有限范围，边界本身可用。 */
    @Test
    void validatesThresholdBounds() {
        assertEquals(1, new JobMetrics.Settings(1).slowThresholdMs());
        assertEquals(60_000, new JobMetrics.Settings(60_000).slowThresholdMs());
        assertThrows(IllegalArgumentException.class, () -> new JobMetrics.Settings(0));
        assertThrows(IllegalArgumentException.class, () -> new JobMetrics.Settings(60_001));
    }

    /**
     * 使用固定处理器创建独立指标组件。
     * @param registry 本用例注册表
     * @return 500毫秒阈值的组件
     */
    private JobMetrics metrics(SimpleMeterRegistry registry) {
        return new JobMetrics(registry, new JobHandlerRegistry(List.of(new Probe())), new JobMetrics.Settings(500));
    }

    /** 无业务副作用的固定任务处理器。 */
    private static final class Probe implements JobHandler {
        /** {@inheritDoc} */
        @Override public String jobCode() { return "job.probe"; }
        /** {@inheritDoc} */
        @Override public void execute(Submission submission) { }
    }
}
