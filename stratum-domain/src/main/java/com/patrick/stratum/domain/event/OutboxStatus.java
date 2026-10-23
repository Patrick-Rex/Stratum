package com.patrick.stratum.domain.event;

/** 值对象枚举：Outbox 投递状态；状态持久化与竞争控制由仓储执行。 */
public enum OutboxStatus {
    PENDING, PROCESSING, SENT, FAILED;

    /**
     * 判断合法状态迁移；已发送为终态，失败事件仅允许显式重放到待发送。
     * @param target 目标状态
     * @return 是否允许迁移
     * @apiNote 示例：OutboxStatus.PENDING.canTransitionTo(OutboxStatus.PROCESSING)。
     */
    public boolean canTransitionTo(OutboxStatus target) {
        return switch (this) {
            case PENDING -> target == PROCESSING;
            case PROCESSING -> target == SENT || target == PENDING || target == FAILED;
            case FAILED -> target == PENDING;
            case SENT -> false;
        };
    }
}
