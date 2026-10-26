package com.patrick.stratum.domain.job;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 值对象：框架无关的任务定义，不解析Quartz表达式或决定业务副作用。
 * <p>示例：new JobDefinition("outbox.relay", Type.CRON, Optional.of("0/5 * * * * ?"),
 * Optional.empty(), 30, policy, IdempotencyRule.SCHEDULE_TIME)。</p>
 * @param jobCode 稳定任务代码
 * @param jobType Cron或一次性任务
 * @param cron Cron表达式；具体语法由调度适配校验
 * @param triggerAt 一次性计划时刻，UTC微秒精度
 * @param timeoutSec 单次执行中断预算，秒
 * @param retryPolicy 每个失败周期的有限重试策略
 * @param idempotencyKeyRule 调用者显式键或计划时刻键
 */
public record JobDefinition(String jobCode, Type jobType, Optional<String> cron, Optional<Instant> triggerAt,
        int timeoutSec, RetryPolicy retryPolicy, IdempotencyRule idempotencyKeyRule) {
    private static final Instant MINIMUM_TIME = Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant MAXIMUM_TIME = Instant.parse("+10000-01-01T00:00:00Z");
    /**
     * 校验互斥计划字段、时限和规则；不在构造时访问外部设施。
     * @param jobCode 1至64个小写字母数字、点或连字符
     * @param jobType 调度类型
     * @param cron Cron任务必填，其他任务为空
     * @param triggerAt 一次性任务必填，其他任务为空
     * @param timeoutSec 1至300秒
     * @param retryPolicy 重试策略
     * @param idempotencyKeyRule 稳定键规则
     * @throws NullPointerException 必需字段缺失
     * @throws IllegalArgumentException 计划不完整、时间或资源越界
     * @apiNote 示例见类说明；一次性任务使用Type.ONCE和Optional.of(triggerAt)。
     */
    public JobDefinition {
        requireCode(jobCode);
        Objects.requireNonNull(jobType, "任务类型不能为空");
        Objects.requireNonNull(cron, "Cron可选值不能为空");
        Objects.requireNonNull(triggerAt, "计划时刻可选值不能为空");
        Objects.requireNonNull(retryPolicy, "重试策略不能为空");
        Objects.requireNonNull(idempotencyKeyRule, "幂等键规则不能为空");
        if (timeoutSec < 1 || timeoutSec > 300
                || (jobType == Type.CRON && (cron.isEmpty() || triggerAt.isPresent()))
                || (jobType == Type.ONCE && (cron.isPresent() || triggerAt.isEmpty()))) {
            throw new IllegalArgumentException("任务计划或执行时限不合法");
        }
        cron.ifPresent(value -> {
            if (value.isBlank() || value.length() > 120 || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("任务Cron文本不合法");
            }
        });
        triggerAt.ifPresent(JobDefinition::requireTime);
    }

    /** 值枚举：任务计划类型，具体调度由Quartz承担。 */
    public enum Type { CRON, ONCE }

    /** 值枚举：显式键由调用用例提供；计划时刻键按任务代码和UTC时刻确定。 */
    public enum IdempotencyRule { EXPLICIT_KEY, SCHEDULE_TIME }

    /**
     * 值对象：同一失败周期最多执行maxAttempts次，退避计算有界且不溢出。
     * @param maxAttempts 每周期最大执行次数
     * @param initialDelay 首次失败后的重试延迟
     * @param maxDelay 指数退避上限
     */
    public record RetryPolicy(int maxAttempts, Duration initialDelay, Duration maxDelay) {
        /**
         * 校验毫秒精度、次数及延迟边界。
         * @param maxAttempts 1至10次
         * @param initialDelay 100毫秒至1分钟
         * @param maxDelay 不小于首次延迟，最多1小时
         * @throws IllegalArgumentException 参数缺失或超限
         * @apiNote 示例：new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(30))。
         */
        public RetryPolicy {
            if (maxAttempts < 1 || maxAttempts > 10 || initialDelay == null || maxDelay == null
                    || initialDelay.compareTo(Duration.ofMillis(100)) < 0
                    || initialDelay.compareTo(Duration.ofMinutes(1)) > 0
                    || maxDelay.compareTo(initialDelay) < 0 || maxDelay.compareTo(Duration.ofHours(1)) > 0
                    || initialDelay.getNano() % 1_000_000 != 0 || maxDelay.getNano() % 1_000_000 != 0) {
                throw new IllegalArgumentException("任务重试策略不合法");
            }
        }

        /**
         * 计算指数退避，到达上限即停止倍增，不随极端次数线性循环。
         * @param failures 本周期已失败次数，必须为正
         * @return 不超过maxDelay的延迟
         * @throws IllegalArgumentException 失败次数不是正数
         * @apiNote 示例：policy.delayForFailure(2)为首次延迟的两倍或上限。
         */
        public Duration delayForFailure(int failures) {
            if (failures < 1) throw new IllegalArgumentException("失败次数必须为正数");
            long delay = initialDelay.toMillis();
            long maximum = maxDelay.toMillis();
            for (int count = 1; count < failures && delay < maximum; count++) {
                delay = Math.min(maximum, delay * 2);
            }
            return Duration.ofMillis(delay);
        }
    }

    /**
     * 核对所有任务存储端口共用的固定代码边界。
     * @param code 任务代码
     * @throws IllegalArgumentException 代码非法
     */
    static void requireCode(String code) {
        if (code == null || !code.matches("[a-z][a-z0-9.-]{0,63}")) {
            throw new IllegalArgumentException("任务代码不合法");
        }
    }

    /**
     * 避免数据库DATETIME截断、舍入或范围差异改变幂等身份。
     * @param time 待保存UTC时刻
     * @throws IllegalArgumentException 缺失、超出数据库日期范围或不是微秒精度
     */
    static void requireTime(Instant time) {
        if (time == null || time.isBefore(MINIMUM_TIME)
                || !time.isBefore(MAXIMUM_TIME) || time.getNano() % 1000 != 0) {
            throw new IllegalArgumentException("任务时刻必须在数据库范围内且为微秒精度");
        }
    }
}
