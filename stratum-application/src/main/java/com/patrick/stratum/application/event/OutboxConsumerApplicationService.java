package com.patrick.stratum.application.event;

import java.util.Objects;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.base.cache.CacheInvalidation;
import com.patrick.stratum.common.base.cache.VersionedCache;
import com.patrick.stratum.common.trace.CorrelationIdPolicy;
import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.domain.event.OutboxConsumer;
import com.patrick.stratum.domain.event.OutboxConsumptionRepository;
import com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group;
import com.patrick.stratum.domain.event.OutboxPublisher.Delivery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 以一个应用工作单元串行核验、缓存补偿和原始事件留痕，不提供Broker直接发送。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxConsumerApplicationService implements OutboxConsumer {
    private final UnitOfWork unitOfWork;
    private final OutboxConsumptionRepository consumption;
    private final VersionedCache cache;
    private final JsonMapper json;

    /** {@inheritDoc} */
    @Override
    public Outcome consume(Delivery delivery, Group group) {
        Objects.requireNonNull(delivery, "消费事件不能为空");
        Objects.requireNonNull(group, "消费组不能为空");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("消费入口必须从事务外调用，以保证返回前提交");
        }
        var event = delivery.message();
        if (!CorrelationIdPolicy.isValid(event.traceId())) throw new IllegalArgumentException("消费traceId非法");
        var previous = TraceIdContext.getTraceId();
        TraceIdContext.setTraceId(event.traceId());
        try {
            Outcome outcome = unitOfWork.execute(() -> apply(delivery, group));
            log.atInfo().addKeyValue("eventId", event.eventId()).addKeyValue("messageId", event.eventId())
                    .addKeyValue("traceId", event.traceId()).addKeyValue("consumerGroup", group.name())
                    .addKeyValue("outcome", outcome.name())
                    .log("Outbox消费结果 eventId={} messageId={} traceId={} consumerGroup={} outcome={}",
                            event.eventId(), event.eventId(), event.traceId(), group, outcome);
            return outcome;
        } finally {
            if (previous.isPresent()) TraceIdContext.setTraceId(previous.orElseThrow());
            else TraceIdContext.clear();
        }
    }

    /**
     * 仅在数据库锁保护的READY分支执行副作用，缓存外部效果依靠版本屏障幂等重试。
     * @param delivery 已登记事件
     * @param group 匹配订阅
     * @return 工作单元待提交结果
     */
    private Outcome apply(Delivery delivery, Group group) {
        var result = consumption.begin(delivery, group);
        if (result == OutboxConsumptionRepository.Result.DUPLICATE) return Outcome.DUPLICATE;
        if (result == OutboxConsumptionRepository.Result.WAITING) return Outcome.WAITING;
        if (group == Group.CACHE_INVALIDATION) {
            var invalidation = json.readerFor(CacheInvalidation.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .<CacheInvalidation>readValue(delivery.message().payload());
            cache.invalidate(invalidation.key(), invalidation.minimumVersion(), invalidation.policy());
        }
        consumption.complete(delivery, group);
        return Outcome.CONSUMED;
    }
}
