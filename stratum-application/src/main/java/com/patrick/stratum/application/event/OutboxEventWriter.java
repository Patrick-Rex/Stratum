package com.patrick.stratum.application.event;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.domain.event.DomainEvent;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/** 将领域事件转换为可靠信封，只允许在 Application 已有工作单元内写入 Outbox。 */
@Component
@RequiredArgsConstructor
public class OutboxEventWriter {
    private final OutboxRepository outbox;
    private final JsonMapper json;

    /**
     * 序列化事件并同事务落盘，序列化或持久化失败会使整个工作单元回滚。
     * @param event 聚合产生的领域事件
     * @param eventType 由用例定义的三段式事件主题
     * @return 已登记的事件标识
     * @throws org.springframework.transaction.IllegalTransactionStateException 缺少事务时抛出
     * @apiNote 示例：unitOfWork.execute(() -> writer.append(event, "erp.material.created"))。
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public String append(DomainEvent event, String eventType) {
        String traceId = TraceIdContext.getTraceId().orElseGet(() -> UUID.randomUUID().toString());
        var message = new OutboxMessage(event.getEventId(), eventType, event.getAggregateId(),
                event.getOccurredAt(), json.writeValueAsString(event), traceId);
        outbox.append(message);
        return message.eventId();
    }
}
