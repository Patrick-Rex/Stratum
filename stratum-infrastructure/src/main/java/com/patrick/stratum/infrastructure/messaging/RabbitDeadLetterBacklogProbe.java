package com.patrick.stratum.infrastructure.messaging;

import java.util.Objects;
import java.util.OptionalLong;

import com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

/**
 * 按固定订阅组读取Broker死信队列的可见数量；不触发调度、写入或消息确认。
 * <p>示例：{@code new RabbitDeadLetterBacklogProbe(admin).ready(Group.ERP_AUDIT)}。</p>
 */
public final class RabbitDeadLetterBacklogProbe {
    private final RabbitAdmin admin;

    /**
     * 使用已有有界连接配置的Rabbit管理员。
     * @param admin 对应已隔离虚拟主机的管理员
     * @throws NullPointerException 管理员为空
     * @apiNote 示例：{@code new RabbitDeadLetterBacklogProbe(admin)}。
     */
    public RabbitDeadLetterBacklogProbe(RabbitAdmin admin) { this.admin = Objects.requireNonNull(admin, "Rabbit管理员不能为空"); }

    /**
     * 执行一次被动队列查询，不可用、缺失或异常时返回空值；可见数不含未确认及延迟消息。
     * @param group 首期固定订阅组
     * @return 非负可见数，空值表示不可用
     * @throws NullPointerException 订阅组为空
     * @apiNote 示例：{@code probe.ready(Group.CACHE_INVALIDATION)}。
     */
    public OptionalLong ready(Group group) {
        String queue = switch (Objects.requireNonNull(group, "订阅组不能为空")) {
            case ERP_AUDIT -> RabbitTopologyConfiguration.ERP_QUEUE + ".dlq";
            case CACHE_INVALIDATION -> RabbitTopologyConfiguration.CACHE_QUEUE + ".dlq";
        };
        try {
            var info = admin.getQueueInfo(queue);
            if (info == null || info.getMessageCount() < 0) return OptionalLong.empty();
            return OptionalLong.of(info.getMessageCount());
        } catch (RuntimeException unavailable) { return OptionalLong.empty(); }
    }
}
