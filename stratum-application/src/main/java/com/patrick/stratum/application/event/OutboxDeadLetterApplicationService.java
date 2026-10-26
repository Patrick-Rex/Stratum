package com.patrick.stratum.application.event;

import java.util.Objects;
import java.util.UUID;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.trace.CorrelationIdPolicy;
import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.domain.event.OutboxDeadLetterCollector;
import com.patrick.stratum.domain.event.OutboxDeadLetterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 以独立应用工作单元提交死信证据，保留原事件声明但不执行或重放业务。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxDeadLetterApplicationService implements OutboxDeadLetterCollector {
    private final UnitOfWork unitOfWork;
    private final OutboxDeadLetterRepository repository;

    /** {@inheritDoc} */
    @Override
    public UUID archive(OutboxDeadLetterRepository.Letter letter) {
        Objects.requireNonNull(letter, "死信证据不能为空");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("死信归档必须从事务外进入，以保证返回前提交");
        }
        String trace = CorrelationIdPolicy.resolve(letter.delivery().map(value -> value.message().traceId()).orElse(null));
        var previous = TraceIdContext.getTraceId(); TraceIdContext.setTraceId(trace);
        try {
            UUID id = unitOfWork.execute(() -> Objects.requireNonNull(repository.append(letter), "死信归档结果不能为空"));
            String eventId = letter.delivery().map(value -> value.message().eventId()).orElse("invalid");
            log.atInfo().addKeyValue("archiveId", id.toString()).addKeyValue("eventId", eventId)
                    .addKeyValue("messageId", eventId).addKeyValue("traceId", trace)
                    .addKeyValue("consumerGroup", letter.group().name()).addKeyValue("category", letter.category().name())
                    .log("死信归档完成 archiveId={} eventId={} messageId={} traceId={} consumerGroup={} category={}",
                            id, eventId, eventId, trace, letter.group(), letter.category());
            return id;
        } finally {
            if (previous.isPresent()) TraceIdContext.setTraceId(previous.orElseThrow());
            else TraceIdContext.clear();
        }
    }
}
