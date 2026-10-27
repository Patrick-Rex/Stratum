package com.patrick.stratum.job;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.patrick.stratum.application.job.JobHandler;
import com.patrick.stratum.domain.job.JobDefinition;
import org.quartz.CronExpression;

/**
 * 固定、有界的处理器注册表，保存启动时快照，不根据数据库类名或HTTP参数创建可执行代码。
 * <p>示例：new JobHandlerRegistry(List.of(relayHandler)).handler("outbox.relay")。</p>
 */
public final class JobHandlerRegistry {
    /** 固定Relay代码，只允许在Relay专用调度器登记。 */
    public static final String RELAY_JOB_CODE = "outbox.relay";
    private final Map<String, JobHandler> handlers;
    private final Map<String, JobDefinition> periodic;

    /**
     * 在任何任务线程启动前核验代码唯一性、资源上限及Cron定义的一致性。
     * @param source Spring显式登记的最多64个处理器；允许为空
     * @throws NullPointerException 列表、处理器或可选定义为null
     * @throws IllegalArgumentException 代码、数量、重复或定时定义不合法
     * @apiNote 示例：new JobHandlerRegistry(List.of(handler))；本构造不启动调度或执行处理器。
     */
    public JobHandlerRegistry(List<JobHandler> source) {
        Objects.requireNonNull(source, "任务处理器列表不能为空");
        if (source.size() > 64) throw new IllegalArgumentException("任务处理器数量超过上限");
        var registered = new HashMap<String, JobHandler>();
        var schedules = new HashMap<String, JobDefinition>();
        for (var handler : List.copyOf(source)) {
            String code = handler.jobCode();
            if (code == null || !code.matches("[a-z][a-z0-9.-]{0,63}")) throw new IllegalArgumentException("任务处理器代码不合法");
            if (registered.putIfAbsent(code, handler) != null) throw new IllegalArgumentException("任务处理器代码重复");
            var definition = Objects.requireNonNull(handler.periodicDefinition(), "任务周期定义可选值不能为空");
            definition.ifPresent(value -> {
                if (!value.jobCode().equals(code) || value.jobType() != JobDefinition.Type.CRON
                        || value.idempotencyKeyRule() != JobDefinition.IdempotencyRule.SCHEDULE_TIME
                        || !CronExpression.isValidExpression(value.cron().orElseThrow())) {
                    throw new IllegalArgumentException("任务处理器周期定义不一致或Cron不合法");
                }
                schedules.put(code, value);
            });
        }
        this.handlers = Map.copyOf(registered);
        this.periodic = Map.copyOf(schedules);
    }

    /**
     * 返回有限、排序且不可变的固定代码集，便于幂等登记Quartz定义。
     * @return 最多64个处理器代码
     * @apiNote 示例：registry.codes().forEach(registrar::register)。
     */
    public List<String> codes() { return handlers.keySet().stream().sorted().toList(); }

    /**
     * 返回不属于Relay专用调度器的固定任务代码。
     * @return 有界、排序且不可变的普通任务代码
     * @apiNote 示例：{@code registry.ordinaryCodes()}用于普通调度器登记。
     */
    public List<String> ordinaryCodes() { return codes().stream().filter(code -> !RELAY_JOB_CODE.equals(code)).toList(); }

    /**
     * 返回已登记的固定Relay代码，未登记时不创造默认任务。
     * @return 空列表或只含固定Relay代码的不可变列表
     * @apiNote 示例：{@code registry.relayCodes()}用于专用调度器登记。
     */
    public List<String> relayCodes() { return handlers.containsKey(RELAY_JOB_CODE) ? List.of(RELAY_JOB_CODE) : List.of(); }

    /**
     * 只选择启动时已经登记的处理器，不执行反射或动态装载。
     * @param code 任务代码
     * @return 唯一处理器
     * @throws IllegalArgumentException 代码没有登记
     * @apiNote 示例：registry.handler("outbox.relay")。
     */
    public JobHandler handler(String code) {
        var handler = code == null ? null : handlers.get(code);
        if (handler == null) throw new IllegalArgumentException("任务处理器未登记");
        return handler;
    }

    /**
     * 返回启动时核验过的周期定义；没有周期定义的处理器只处理显式登记执行。
     * @param code 已登记代码
     * @return 不可变Cron定义或空可选值
     * @throws IllegalArgumentException 代码未登记
     * @apiNote 示例：registry.periodicDefinition("outbox.relay")。
     */
    public Optional<JobDefinition> periodicDefinition(String code) {
        handler(code);
        return Optional.ofNullable(periodic.get(code));
    }
}
