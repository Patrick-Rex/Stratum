package com.patrick.stratum.infrastructure.messaging;

import java.util.Locale;
import java.util.Objects;

import com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group;
import com.patrick.stratum.domain.event.OutboxDeadLetterRepository.Category;
import com.patrick.stratum.domain.event.OutboxPublisher.PublishResult;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/** 消息传输的低基数计数，只接受固定结果和订阅组，不影响消息提交与确认。 */
@Slf4j
final class MessageMetrics {
    private final MeterRegistry registry;

    /**
     * 使用应用注册表；独立装配且没有注册表时保持无操作。
     * @param registry 可空指标注册表
     */
    MessageMetrics(MeterRegistry registry) { this.registry = registry; }

    /**
     * 记录一次发布端口返回；确认只代表Broker确认而不代表消费。
     * @param result 固定发布结果
     * @throws NullPointerException 结果为空
     */
    void published(PublishResult result) {
        String outcome = Objects.requireNonNull(result, "发布结果不能为空").name().toLowerCase(Locale.ROOT);
        increment("stratum.messaging.published", "result", outcome);
    }

    /** 发布端口抛出异常时登记独立固定分类，不覆盖调用方异常。 */
    void publishException() { increment("stratum.messaging.published", "result", "exception"); }

    /**
     * 记录一次消费处理及Broker重投事实；等待不等于消费成功或业务失败。
     * @param group 固定订阅组
     * @param result 固定消费分类
     * @param redelivered Broker是否声明为重投
     * @throws NullPointerException 订阅组或结果为空
     */
    void consumed(Group group, ConsumerResult result, boolean redelivered) {
        String subscription = Objects.requireNonNull(group, "订阅组不能为空").name().toLowerCase(Locale.ROOT);
        String outcome = Objects.requireNonNull(result, "消费结果不能为空").name().toLowerCase(Locale.ROOT);
        increment("stratum.messaging.consumed", "group", subscription, "result", outcome);
        if (redelivered) increment("stratum.messaging.redelivered", "group", subscription);
    }

    /**
     * 记录归档入口成功返回的次数，重复归档调用不代表唯一死信数。
     * @param group 固定订阅组
     * @param category 已核验的固定归档类别
     * @throws NullPointerException 订阅组或类别为空
     */
    void archived(Group group, Category category) {
        String subscription = Objects.requireNonNull(group, "订阅组不能为空").name().toLowerCase(Locale.ROOT);
        String type = Objects.requireNonNull(category, "归档类别不能为空").name().toLowerCase(Locale.ROOT);
        increment("stratum.messaging.deadletter.archived", "group", subscription, "category", type);
    }

    /**
     * 记录归档调用或解码准备失败，不推断Broker待处理数。
     * @param group 固定订阅组
     * @throws NullPointerException 订阅组为空
     */
    void archiveFailed(Group group) {
        String subscription = Objects.requireNonNull(group, "订阅组不能为空").name().toLowerCase(Locale.ROOT);
        increment("stratum.messaging.deadletter.archive.failed", "group", subscription);
    }

    /**
     * 指标故障仅输出固定异常类型，不能改变发布或消费结果。
     * @param name 固定指标名
     * @param tags 固定枚举标签
     */
    private void increment(String name, String... tags) {
        if (registry == null) return;
        try { registry.counter(name, tags).increment(); }
        catch (RuntimeException failure) {
            log.atWarn().addKeyValue("errorType", failure.getClass().getSimpleName())
                    .log("消息指标记录失败 errorType={}", failure.getClass().getSimpleName());
        }
    }

    /** 消费提交结果与适配层失败或中断；不包含未受控输入。 */
    enum ConsumerResult { CONSUMED, DUPLICATE, WAITING, FAILED, INTERRUPTED }
}
