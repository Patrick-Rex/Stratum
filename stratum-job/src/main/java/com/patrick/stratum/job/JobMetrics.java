package com.patrick.stratum.job;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 固定任务代码与有限结果标签的实际工作耗时观测，不保存业务参数或执行身份。 */
public final class JobMetrics {
    private final MeterRegistry registry;
    private final Set<String> codes;
    private final long slowNanos;

    /**
     * 固定启动时允许的处理器代码及慢任务阈值。
     * @param registry 应用指标注册表
     * @param handlers 已验证的固定处理器注册表
     * @param settings 有界慢任务配置
     * @throws NullPointerException 必需依赖缺失
     * @apiNote 示例：由JobRuntimeConfiguration根据启动时注册表装配。
     */
    public JobMetrics(MeterRegistry registry, JobHandlerRegistry handlers, Settings settings) {
        this.registry = Objects.requireNonNull(registry, "指标注册表不能为空");
        this.codes = Set.copyOf(Objects.requireNonNull(handlers, "任务注册表不能为空").codes());
        this.slowNanos = TimeUnit.MILLISECONDS.toNanos(Objects.requireNonNull(settings, "任务观测配置不能为空").slowThresholdMs());
    }

    /**
     * 记录已结束的实际工作；状态未能持久化时使用不确定结果，不冒充完成。
     * @param code 启动时已登记的任务代码
     * @param result 已完成结果或状态不确定
     * @param elapsedNanos 单调时钟记录的非负实际耗时
     * @return 是否达到慢任务阈值
     * @throws IllegalArgumentException 代码、结果或耗时不属于可观察范围
     * @apiNote 示例：{@code metrics.record("outbox.relay", JobExecutionResult.SUCCEEDED, elapsedNanos)}。
     */
    public boolean record(String code, JobExecutionResult result, long elapsedNanos) {
        if (code == null || !codes.contains(code) || elapsedNanos < 0 || result == null) throw new IllegalArgumentException("任务指标输入无效");
        if (!(result == JobExecutionResult.SUCCEEDED || result == JobExecutionResult.FAILED || result == JobExecutionResult.TIMED_OUT
                || result == JobExecutionResult.INTERRUPTED || result == JobExecutionResult.STATE_UNCERTAIN)) {
            throw new IllegalArgumentException("非实际结束结果不能计入任务耗时");
        }
        String outcome = result.name().toLowerCase(Locale.ROOT);
        registry.timer("stratum.job.duration", "job", code, "result", outcome).record(elapsedNanos, TimeUnit.NANOSECONDS);
        boolean slow = elapsedNanos >= slowNanos;
        if (slow) registry.counter("stratum.job.slow", "job", code, "result", outcome).increment();
        return slow;
    }

    /**
     * 任务慢调用阈值配置；默认沿用现有用例慢调用基线500毫秒。
     * @param slowThresholdMs 1至60000毫秒
     */
    @ConfigurationProperties("observability.job")
    public record Settings(@DefaultValue("500") long slowThresholdMs) {
        /**
         * 限制阈值范围并避免时间换算溢出。
         * @param slowThresholdMs 慢任务阈值
         * @throws IllegalArgumentException 阈值不在允许范围内
         * @apiNote 示例：{@code new Settings(500)}。
         */
        public Settings {
            if (slowThresholdMs < 1 || slowThresholdMs > 60_000) throw new IllegalArgumentException("慢任务阈值越界");
        }
    }
}
