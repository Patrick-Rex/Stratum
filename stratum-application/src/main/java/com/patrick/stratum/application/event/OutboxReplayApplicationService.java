package com.patrick.stratum.application.event;

import java.util.Objects;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.domain.event.OutboxReplayRepository;
import com.patrick.stratum.domain.event.OutboxReplayRequester;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 以单一工作单元提交人工重放请求；消息发布仍由事务外Relay完成。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxReplayApplicationService implements OutboxReplayRequester {
    private final UnitOfWork unitOfWork;
    private final OutboxReplayRepository repository;

    /** {@inheritDoc} */
    @Override
    public OutboxReplayRepository.Receipt request(OutboxReplayRepository.Request request) {
        Objects.requireNonNull(request, "重放请求不能为空");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("人工重放入口必须从事务外调用，以保证返回前提交");
        }
        var previous = TraceIdContext.getTraceId(); TraceIdContext.setTraceId(request.traceId());
        try {
            var receipt = unitOfWork.execute(() -> Objects.requireNonNull(repository.enqueue(request), "重放登记结果不能为空"));
            String delivery = receipt.deliveryId().map(Object::toString).orElse("");
            log.atInfo().addKeyValue("requestId", receipt.requestId().toString()).addKeyValue("archiveId", receipt.archiveId().toString())
                    .addKeyValue("deliveryId", delivery).addKeyValue("traceId", request.traceId()).addKeyValue("outcome", receipt.outcome().name())
                    .log("人工重放登记完成 requestId={} archiveId={} deliveryId={} traceId={} outcome={}",
                            receipt.requestId(), receipt.archiveId(), delivery, request.traceId(), receipt.outcome());
            return receipt;
        } finally {
            if (previous.isPresent()) TraceIdContext.setTraceId(previous.orElseThrow());
            else TraceIdContext.clear();
        }
    }
}
