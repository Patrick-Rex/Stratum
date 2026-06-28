package com.patrick.stratum.domain.event;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * 领域事件基类。
 *
 * <p>领域事件表示领域中已发生的有业务含义的事实，是聚合间协作与
 * 最终一致性的核心机制。事件一旦创建即不可变。</p>
 *
 * <p>所有领域事件均需继承本类，并携带发生时刻、事件标识和聚合标识。</p>
 */
public abstract class DomainEvent implements Serializable {

    /**
     * 事件全局唯一标识符。
     */
    private final String eventId;

    /**
     * 事件发生时刻（UTC）。
     */
    private final Instant occurredAt;

    /**
     * 触发该事件的聚合根标识符。
     */
    private final String aggregateId;

    /**
     * 构造领域事件，自动生成事件 ID 与发生时刻。
     *
     * @param aggregateId 触发该事件的聚合根标识符，不可为 null
     */
    protected DomainEvent(String aggregateId) {
        this.eventId = UUID.randomUUID().toString();
        this.occurredAt = Instant.now();
        this.aggregateId = aggregateId;
    }

    /**
     * 返回事件全局唯一标识符。
     *
     * @return 事件 ID
     */
    public String getEventId() {
        return eventId;
    }

    /**
     * 返回事件发生时刻（UTC）。
     *
     * @return 发生时刻
     */
    public Instant getOccurredAt() {
        return occurredAt;
    }

    /**
     * 返回触发该事件的聚合根标识符。
     *
     * @return 聚合根标识符
     */
    public String getAggregateId() {
        return aggregateId;
    }
}
