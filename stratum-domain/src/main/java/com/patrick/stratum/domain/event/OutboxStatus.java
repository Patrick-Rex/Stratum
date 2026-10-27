package com.patrick.stratum.domain.event;

/** 值对象枚举：Outbox 投递状态；状态持久化与竞争控制由仓储执行。 */
public enum OutboxStatus {
    PENDING, PROCESSING, SENT, FAILED, CONSUMED;

    /**
     * 判断合法状态迁移；CONSUMED表示消费已确认而发布确认未持久化，具体资格仍由数据库凭据和租约核验。
     * @param target 目标状态
     * @return 是否允许迁移
     * @apiNote 示例：OutboxStatus.PENDING.canTransitionTo(OutboxStatus.PROCESSING)。
     */
    public boolean canTransitionTo(OutboxStatus target) {
        return switch (this) {
            case PENDING -> target == PROCESSING || target == CONSUMED;
            case PROCESSING -> target == SENT || target == PENDING || target == FAILED || target == CONSUMED;
            case FAILED -> target == PENDING || target == CONSUMED;
            case SENT, CONSUMED -> false;
        };
    }
}
