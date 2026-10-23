package com.patrick.stratum.infrastructure.lock;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 分布式锁调用结果、等待和持有时长指标；锁键与 traceId 不作为指标标签。 */
@Component
@RequiredArgsConstructor
@Slf4j
@EnableConfigurationProperties(LockMetrics.Settings.class)
public class LockMetrics {
    private final MeterRegistry registry;
    private final Settings settings;

    /** 调用者请求的锁获取方式，内部轮询不重复计算请求数。 */
    public enum Mode { TRY, WAIT }
    /** 有限的加锁结果标签，避免异常类名及业务键引起指标膨胀。 */
    public enum Acquisition { ACQUIRED, CONTENDED, UNAVAILABLE, CANCELLED, ERROR }
    /** 租约释放的有限结果。 */
    public enum Release { RELEASED, STALE, ERROR }

    /**
     * 记录一次调用者加锁请求及其等待时长。
     * @param key 锁键，仅进入日志
     * @param mode 请求方式
     * @param result 结果
     * @param waitNanos 等待纳秒数
     * @param traceId 原调用者 traceId
     * @apiNote 示例：metrics.acquire(key, Mode.WAIT, Acquisition.ACQUIRED, elapsed, traceId)。
     */
    public void acquire(String key, Mode mode, Acquisition result, long waitNanos, String traceId) {
        registry.counter("stratum.lock.acquisitions", "mode", tag(mode), "result", tag(result)).increment();
        registry.timer("stratum.lock.wait", "mode", tag(mode), "result", tag(result))
                .record(waitNanos, TimeUnit.NANOSECONDS);
        if (settings.enabled() || result != Acquisition.ACQUIRED) {
            long waitMs = TimeUnit.NANOSECONDS.toMillis(waitNanos);
            var event = result == Acquisition.ACQUIRED ? log.atInfo() : log.atWarn();
            event.addKeyValue("lockKey", key).addKeyValue("acquireResult", tag(result))
                    .addKeyValue("waitMs", waitMs).addKeyValue("traceId", traceId)
                    .log("锁获取 lockKey={} acquireResult={} waitMs={} traceId={}", key, tag(result), waitMs, traceId);
        }
    }

    /**
     * 记录释放尝试；每个租约只在首次终结时累计一次持有时长。
     * @param key 锁键，仅进入日志
     * @param result 释放结果
     * @param holdNanos 持有时长，上限为租期
     * @param firstTerminal 是否首次成功释放或确认已失效
     * @param traceId 获取租约的原调用者链路
     * @apiNote 示例：metrics.release(key, Release.RELEASED, elapsed, true, traceId)。
     */
    public void release(String key, Release result, long holdNanos, boolean firstTerminal, String traceId) {
        registry.counter("stratum.lock.releases", "result", tag(result)).increment();
        if (firstTerminal) { registry.timer("stratum.lock.hold").record(holdNanos, TimeUnit.NANOSECONDS); }
        if ((settings.enabled() && firstTerminal) || result == Release.ERROR) {
            long holdMs = TimeUnit.NANOSECONDS.toMillis(holdNanos);
            var event = result == Release.ERROR ? log.atWarn() : log.atInfo();
            event.addKeyValue("lockKey", key).addKeyValue("releaseResult", tag(result))
                    .addKeyValue("holdMs", holdMs).addKeyValue("traceId", traceId)
                    .log("锁释放 lockKey={} releaseResult={} holdMs={} traceId={}", key, tag(result), holdMs, traceId);
        }
    }

    /**
     * 将有限枚举转为稳定小写标签。
     * @param value 枚举值
     * @return 指标标签
     */
    private static String tag(Enum<?> value) { return value.name().toLowerCase(Locale.ROOT); }

    /**
     * 锁成功获取与释放日志开关，竞争失败始终保留警告。
     * @param enabled 是否记录成功路径日志
     */
    @ConfigurationProperties("observability.lock.trace")
    public record Settings(@DefaultValue("true") boolean enabled) { }
}
