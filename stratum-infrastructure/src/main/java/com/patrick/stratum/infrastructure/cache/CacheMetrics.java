package com.patrick.stratum.infrastructure.cache;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.patrick.stratum.common.base.cache.VersionedCache.Value;
import com.patrick.stratum.common.trace.TraceIdContext;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 缓存低基数指标及可关闭的逐请求链路日志，缓存键与 traceId 只进入日志。 */
@RequiredArgsConstructor
@Slf4j
public class CacheMetrics {
    private final MeterRegistry registry;
    private final Settings settings;

    /**
     * 绑定进程内请求合并累计值和在途数量，不为每个键创建指标。
     * @param flight 由 Spring 管理的合并器
     * @apiNote 示例：metrics.bind(flight)，每个应用实例仅装配一次。
     */
    public void bind(SingleFlight<?> flight) {
        FunctionCounter.builder("stratum.cache.singleflight.merged", flight, SingleFlight::mergedCount).register(registry);
        Gauge.builder("stratum.cache.singleflight.inflight", flight, SingleFlight::inFlightCount).register(registry);
    }

    /**
     * 记录一次用户缓存访问，hit 包括已缓存的空值。
     * @param key 缓存键，仅作为日志字段
     * @param hit 是否命中
     * @apiNote 示例：metrics.access(key, lookup.hit())。
     */
    public void access(String key, boolean hit) {
        registry.counter("stratum.cache.access", "result", hit ? "hit" : "miss").increment();
        if (settings.enabled()) {
            String traceId = TraceIdContext.getTraceId().orElse("-");
            log.atInfo().addKeyValue("cacheKey", key).addKeyValue("hit", hit)
                    .addKeyValue("traceId", traceId)
                    .log("缓存访问 cacheKey={} hit={} traceId={}", key, hit, traceId);
        }
    }

    /**
     * 记录缓存访问失败，不将失败纳入空值命中。
     * @apiNote 示例：metrics.failed()。
     */
    public void failed() { registry.counter("stratum.cache.requests.failed").increment(); }

    /**
     * 只计量实际回源行为，合并等待者不重复增加回源次数。
     * @param key 缓存键，仅作为日志字段
     * @param loader 原始加载行为
     * @return 回源数据或已确认不存在
     * @apiNote 示例：metrics.load(key, loader)。
     */
    public Optional<Value> load(String key, Supplier<Optional<Value>> loader) {
        long start = System.nanoTime();
        String result = "error";
        registry.counter("stratum.cache.loads").increment();
        try {
            var value = Objects.requireNonNull(loader.get(), "回源结果不能为 null");
            result = value.isPresent() ? "value" : "empty";
            return value;
        } finally {
            long nanos = System.nanoTime() - start;
            registry.timer("stratum.cache.load.duration", "result", result).record(nanos, TimeUnit.NANOSECONDS);
            if (settings.enabled()) {
                String traceId = TraceIdContext.getTraceId().orElse("-");
                long millis = TimeUnit.NANOSECONDS.toMillis(nanos);
                log.atInfo().addKeyValue("cacheKey", key).addKeyValue("result", result)
                        .addKeyValue("loadDurationMs", millis).addKeyValue("traceId", traceId)
                        .log("缓存回源结束 cacheKey={} result={} loadDurationMs={} traceId={}", key, result, millis, traceId);
            }
        }
    }

    /**
     * 缓存逐请求日志配置；指标不受此开关影响，动态刷新在 Nacos 节点实现。
     * @param enabled 是否记录缓存访问与回源日志
     */
    @ConfigurationProperties("observability.cache.trace")
    public record Settings(@DefaultValue("true") boolean enabled) { }
}
