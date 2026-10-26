package com.patrick.stratum.domain.event;

/** 消费技术存储端口；以持久化凭据和聚合行锁保护当前工作单元内的副作用。 */
public interface OutboxConsumptionRepository {
    /**
     * 核验原消息并锁定聚合头；READY时调用方可在同一工作单元执行副作用。
     * @param delivery Broker提供的原事件及序号
     * @param group 唯一匹配事件类型的消费组
     * @return 可执行、已消费或等待前序/竞争事务
     * @throws IllegalArgumentException 信封不符或消费组不匹配
     * @throws IllegalStateException 没有活动写工作单元
     * @apiNote 示例：unitOfWork.execute(() -> repository.begin(delivery, Group.ERP_AUDIT))。
     */
    Result begin(OutboxPublisher.Delivery delivery, Group group);

    /**
     * 同事务保存原始信封及消费凭据并推进顺序；调用前应已完成对应副作用。
     * @param delivery 已核验的事件
     * @param group 消费组
     * @throws IllegalStateException 不是可完成的聚合头或没有写工作单元
     * @throws IllegalArgumentException 信封不符
     * @apiNote 示例：在begin返回READY的同一工作单元中调用complete(delivery, group)。
     */
    void complete(OutboxPublisher.Delivery delivery, Group group);

    /** 消费资格：重复可直接确认，等待不应增加业务失败次数。 */
    enum Result { READY, DUPLICATE, WAITING }

    /** 首期固定订阅组；每类事件只有一个负责推进顺序的订阅。 */
    enum Group {
        ERP_AUDIT, CACHE_INVALIDATION;

        /**
         * 验证首期事件与订阅的唯一关系。
         * @param eventType 事件主题
         * @return 是否属于该订阅
         * @apiNote 示例：Group.ERP_AUDIT.accepts("erp.material.created")为true。
         */
        public boolean accepts(String eventType) {
            return eventType != null && switch (this) {
                case ERP_AUDIT -> eventType.startsWith("erp.");
                case CACHE_INVALIDATION -> eventType.equals("stratum.cache.invalidate");
            };
        }
    }
}
