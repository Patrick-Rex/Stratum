package com.patrick.stratum.domain.event;

/** 消费应用入口端口；适配器只能在该调用成功提交后确认Broker消息。 */
public interface OutboxConsumer {
    /**
     * 从事务外处理原始事件，返回前完成凭据及顺序的数据库提交。
     * @param delivery 原事件与持久化序号
     * @param group 已登记的消费组
     * @return 已消费、已存在凭据或暂时等待前序/竞争事务
     * @throws IllegalArgumentException 信封或订阅非法
     * @throws IllegalStateException 在已有事务中调用或工作单元失败
     * @apiNote 示例：consumer.consume(delivery, OutboxConsumptionRepository.Group.ERP_AUDIT)。
     */
    Outcome consume(OutboxPublisher.Delivery delivery, OutboxConsumptionRepository.Group group);

    /** 提交结果；WAITING不得当作成功，也不应增加业务失败次数。 */
    enum Outcome { CONSUMED, DUPLICATE, WAITING }
}
