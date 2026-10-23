package com.patrick.stratum.domain.event;

/** 事件落盘端口，只参与调用方已有工作单元，不直接投递外部消息。 */
public interface OutboxRepository {

    /**
     * 在当前写事务追加待投递事件；重复 eventId 必须拒绝，不能覆盖原事件。
     * @param message 完整事件信封
     * @throws IllegalStateException 未开启工作单元时抛出
     * @apiNote 示例：unitOfWork.execute(() -> { outbox.append(message); return null; })。
     */
    void append(OutboxMessage message);
}
