package com.patrick.stratum.application.event;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.trace.CorrelationIdPolicy;
import com.patrick.stratum.domain.event.OutboxPublisher;
import com.patrick.stratum.domain.event.OutboxRelayRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 在事务外发布已领取Outbox，并由独立工作单元记录结果；本组件不启动调度器。
 * <p>示例：由任务执行器调用runBatch(request, retryPolicy)，跨实例资格由数据库租约控制。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRelayApplicationService {
    private final UnitOfWork unitOfWork;
    private final OutboxRelayRepository relay;
    private final ObjectProvider<OutboxPublisher> publishers;
    private final AtomicBoolean running = new AtomicBoolean();

    /**
     * 顺序执行有界批次，每次只领取即将发送的一条，避免预先持有等待发送的租约。
     * @param request 最大尝试条数及单次租期
     * @param retryPolicy 发送失败策略
     * @return 成功记录投递结果的条数，包含SENT及已记录的重试/失败，不表示业务消费成功
     * @throws IllegalStateException 仍处于事务、消息未启用、本实例已有批次执行或存储契约不符
     * @apiNote 示例：relay.runBatch(new OutboxRelayRepository.ClaimRequest(16, Duration.ofSeconds(30)), retryPolicy)。
     * 中断或持久化异常时，尚未完成的领取由数据库租约到期恢复。
     */
    public int runBatch(OutboxRelayRepository.ClaimRequest request, OutboxRelayRepository.RetryPolicy retryPolicy) {
        Objects.requireNonNull(request, "Relay请求不能为空");
        Objects.requireNonNull(retryPolicy, "Relay重试策略不能为空");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Relay批次必须从事务外调用");
        }
        var publisher = publishers.getIfAvailable();
        if (publisher == null) throw new IllegalStateException("Outbox消息发布尚未启用");
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("本实例已有Relay批次执行");
        try {
            int updated = 0;
            var single = new OutboxRelayRepository.ClaimRequest(1, request.leaseDuration());
            for (int attempted = 0; attempted < request.batchSize(); attempted++) {
                if (Thread.currentThread().isInterrupted()) break;
                var claims = unitOfWork.execute(() -> relay.claim(single));
                if (claims.isEmpty()) break;
                if (claims.size() != 1) throw new IllegalStateException("Relay存储返回了超出请求的领取结果");
                var claim = claims.getFirst();
                var result = publish(publisher, claim);
                if (Thread.currentThread().isInterrupted()) break;
                boolean changed = unitOfWork.execute(() -> relay.finish(claim, result, retryPolicy));
                if (changed) updated++;
                var event = claim.delivery().message();
                String trace = CorrelationIdPolicy.isValid(event.traceId()) ? event.traceId() : "invalid";
                log.atInfo().addKeyValue("eventId", event.eventId()).addKeyValue("messageId", event.eventId())
                        .addKeyValue("traceId", trace).addKeyValue("consumerGroup", "")
                        .addKeyValue("outcome", result.name()).addKeyValue("stateUpdated", changed)
                        .log("Outbox投递结果 eventId={} messageId={} traceId={} result={} stateUpdated={}",
                                event.eventId(), event.eventId(), trace, result, changed);
            }
            return updated;
        } finally {
            running.set(false);
        }
    }

    /**
     * 在写事务外调用发布端口，异常分类只写安全类型，不输出消息正文或异常原文。
     * @param publisher 实际发布端口
     * @param claim 原领取证据
     * @return 可持久化的实际结果或安全失败分类
     */
    private static OutboxPublisher.PublishResult publish(OutboxPublisher publisher, OutboxRelayRepository.Claim claim) {
        try {
            return publisher.publish(claim.delivery());
        } catch (IllegalArgumentException failure) {
            logFailure(claim, failure, "invalid-envelope");
            return OutboxPublisher.PublishResult.REJECTED;
        } catch (RuntimeException failure) {
            logFailure(claim, failure, "publisher-unavailable");
            return OutboxPublisher.PublishResult.UNAVAILABLE;
        }
    }

    /**
     * 记录安全身份与失败分类，默认文本日志也能按事件及trace关联。
     * @param claim 原领取
     * @param failure 仅取异常类型
     * @param category 固定内部分类
     */
    private static void logFailure(OutboxRelayRepository.Claim claim, RuntimeException failure, String category) {
        var event = claim.delivery().message();
        String trace = CorrelationIdPolicy.isValid(event.traceId()) ? event.traceId() : "invalid";
        log.atWarn().addKeyValue("eventId", event.eventId()).addKeyValue("messageId", event.eventId())
                .addKeyValue("traceId", trace).addKeyValue("consumerGroup", "")
                .addKeyValue("errorType", failure.getClass().getSimpleName()).addKeyValue("category", category)
                .log("Outbox发布失败 eventId={} messageId={} traceId={} category={} errorType={}",
                        event.eventId(), event.eventId(), trace, category, failure.getClass().getSimpleName());
    }
}
