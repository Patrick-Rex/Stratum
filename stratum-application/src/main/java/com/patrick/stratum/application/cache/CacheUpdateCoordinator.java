package com.patrick.stratum.application.cache;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.patrick.stratum.common.base.cache.CacheInvalidation;
import com.patrick.stratum.common.base.cache.VersionedCache;
import com.patrick.stratum.common.base.cache.VersionedCache.Policy;
import com.patrick.stratum.common.base.cache.VersionedCache.Value;
import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/** 将缓存补偿意图随业务事务落盘，仅在提交后执行旁路更新或失效。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CacheUpdateCoordinator {
    private final VersionedCache cache;
    private final OutboxRepository outbox;
    private final JsonMapper json;

    /**
     * 登记提交后版本更新及持久化失效补偿，回滚时不操作缓存。
     * @param key 业务缓存键
     * @param value 事务即将提交的版本和值
     * @param policy TTL 策略
     * @return 已登记的补偿事件 UUID
     * @throws org.springframework.transaction.IllegalTransactionStateException 缺少事务时抛出
     * @apiNote 示例：unitOfWork.execute(() -> coordinator.updateAfterCommit(key, value, policy))。
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public String updateAfterCommit(String key, Value value, Policy policy) {
        Objects.requireNonNull(value, "value");
        return register(key, value.version(), policy, () -> cache.putIfNewer(key, value, policy));
    }

    /**
     * 登记提交后失效及持久化补偿，适用于删除和无法构造完整缓存值的更新。
     * @param key 业务缓存键
     * @param minimumVersion 已提交状态的最低版本，删除时必须高于被删除对象的旧版本
     * @param policy TTL 策略
     * @return 已登记的补偿事件 UUID
     * @throws org.springframework.transaction.IllegalTransactionStateException 缺少事务时抛出
     * @apiNote 示例：unitOfWork.execute(() -> coordinator.invalidateAfterCommit(key, deletedVersion + 1, policy))。
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public String invalidateAfterCommit(String key, long minimumVersion, Policy policy) {
        return register(key, minimumVersion, policy, () -> cache.invalidate(key, minimumVersion, policy));
    }

    /**
     * 先持久化意图再注册提交回调，消除提交后进程中断导致补偿意图丢失的窗口。
     * @param key 缓存键
     * @param minimumVersion 失效最低版本
     * @param policy 缓存策略
     * @param action 提交后的缓存操作
     * @return 补偿事件标识
     */
    private String register(String key, long minimumVersion, Policy policy, Runnable action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("缓存提交动作必须处于工作单元内");
        }
        var invalidation = new CacheInvalidation(key, minimumVersion, policy.ttl().toMillis(), policy.jitter().toMillis());
        String eventId = UUID.randomUUID().toString();
        String traceId = TraceIdContext.getTraceId().orElseGet(() -> UUID.randomUUID().toString());
        String targetId = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
        outbox.append(new OutboxMessage(eventId, CacheInvalidation.EVENT_TYPE, targetId, Instant.now(),
                json.writeValueAsString(invalidation), traceId));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /** {@inheritDoc} */
            @Override
            public void afterCommit() { applyAfterCommit(action, invalidation, eventId, traceId); }
        });
        return eventId;
    }

    /**
     * 更新失败降级删除，失败信息关联持久化事件；不将已提交事务伪装成回滚。
     * @param action 原缓存动作
     * @param invalidation 持久化补偿载荷
     * @param eventId 补偿事件标识
     * @param traceId 原业务链路标识
     */
    private void applyAfterCommit(Runnable action, CacheInvalidation invalidation, String eventId, String traceId) {
        try { action.run(); }
        catch (RuntimeException failure) {
            log.atWarn().addKeyValue("traceId", traceId).addKeyValue("cacheKey", invalidation.key())
                    .addKeyValue("eventId", eventId).addKeyValue("errorType", failure.getClass().getSimpleName())
                    .log("提交后缓存操作失败，尝试失效，补偿意图已持久化");
            try { cache.invalidate(invalidation.key(), invalidation.minimumVersion(), invalidation.policy()); }
            catch (RuntimeException invalidationFailure) {
                log.atError().addKeyValue("traceId", traceId).addKeyValue("cacheKey", invalidation.key())
                        .addKeyValue("eventId", eventId)
                        .addKeyValue("errorType", invalidationFailure.getClass().getSimpleName())
                        .log("缓存失效失败，等待 Outbox 补偿");
            }
        }
    }
}
