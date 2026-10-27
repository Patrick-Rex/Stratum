package com.patrick.stratum.starter.integration;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.patrick.stratum.application.event.OutboxConsumerApplicationService;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.base.cache.CacheInvalidation;
import com.patrick.stratum.common.base.cache.VersionedCache;
import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.domain.event.OutboxConsumptionRepository;
import com.patrick.stratum.domain.event.OutboxConsumer;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxPublisher;
import com.patrick.stratum.domain.event.OutboxPublisher.Delivery;
import com.patrick.stratum.domain.event.OutboxRelayRepository;
import com.patrick.stratum.domain.event.OutboxRepository;
import com.patrick.stratum.domain.event.OutboxStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import static com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group.*;
import static com.patrick.stratum.domain.event.OutboxConsumer.Outcome.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** 真实应用事务与Redis版本屏障组合验证消费副作用、重复及失败重试。 */
class OutboxConsumerApplicationIntegrationTest extends MariaDbIntegrationTest {
    private static final VersionedCache.Policy POLICY = new VersionedCache.Policy(Duration.ofMinutes(5), Duration.ZERO);
    @Autowired private UnitOfWork unitOfWork;
    @Autowired private OutboxRepository outbox;
    @Autowired private OutboxConsumptionRepository consumption;
    @Autowired private OutboxConsumer consumer;
    @Autowired private OutboxRelayRepository relay;
    @Autowired private VersionedCache cache;
    @Autowired private JsonMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    private final List<String> aggregates = new ArrayList<>();
    private final List<String> keys = new ArrayList<>();

    /** 清理本用例的凭据、事件、进度和随机Redis键，恢复线程上下文。 */
    @AfterEach
    void cleanup() {
        TraceIdContext.clear();
        for (var aggregate : aggregates) {
            jdbc.update("DELETE FROM outbox_consumption WHERE aggregate_id = ?", aggregate);
            jdbc.update("DELETE FROM outbox_event WHERE aggregate_id = ?", aggregate);
            jdbc.update("DELETE FROM outbox_aggregate_progress WHERE aggregate_id = ?", aggregate);
        }
        for (var key : keys) redis.delete(List.of(key, "null:" + key.substring(4), "version:" + key.substring(4)));
    }

    /** ERP只留痕且提交原高精度载荷，不触碰缓存；成功和重复均恢复原trace。 */
    @Test
    void journalsErpAndRestoresCallerTrace() {
        var probe = new CacheProbe(cache); var service = service(consumption, probe);
        var event = seed("erp.probe.created", "{\"amount\":999999999999999999.123456789012}", 1).getFirst();
        TraceIdContext.setTraceId("outer-trace");
        assertEquals(CONSUMED, service.consume(event, ERP_AUDIT));
        assertEquals(DUPLICATE, service.consume(event, ERP_AUDIT));
        assertEquals("outer-trace", TraceIdContext.getRequiredTraceId()); assertEquals(0, probe.invalidations);
        assertEquals(event.message().payload(), jdbc.queryForObject(
                "SELECT payload FROM outbox_consumption WHERE event_id = ?", String.class, event.message().eventId()));
        assertEquals(1, receipts(event)); assertEquals(1L, progress(event));
    }

    /** 补偿删除旧值并留下有TTL的最低版本屏障，重复不会改写新值或重置版本标记。 */
    @Test
    void invalidatesRealCacheOnceAndPreservesNewerData() {
        var key = key(); assertTrue(cache.putIfNewer(key, new VersionedCache.Value(1, "old"), POLICY));
        var event = cacheEvent(key, 2); var probe = new CacheProbe(cache); var service = service(consumption, probe);
        assertEquals(CONSUMED, service.consume(event, CACHE_INVALIDATION));
        assertNull(redis.opsForValue().get(key));
        assertFalse(cache.putIfNewer(key, new VersionedCache.Value(1, "late"), POLICY));
        assertTrue(redis.getExpire("version:" + key.substring(4), java.util.concurrent.TimeUnit.MILLISECONDS) > 0);
        assertTrue(cache.putIfNewer(key, new VersionedCache.Value(3, "new"), POLICY));
        String stamp = redis.opsForValue().get("version:" + key.substring(4));
        assertEquals(DUPLICATE, service.consume(event, CACHE_INVALIDATION));
        assertEquals(stamp, redis.opsForValue().get("version:" + key.substring(4)));
        assertEquals("new", cache.get(key, POLICY, Optional::empty).orElseThrow().payload());
        assertEquals(1, probe.invalidations); assertEquals(1, receipts(event));
    }

    /** Redis效果发生后连接报错，数据库凭据及进度回滚；重试仍保持版本屏障。 */
    @Test
    void retriesAfterCacheFailureWithoutPrematureProgress() {
        var key = key(); var event = cacheEvent(key, 4); var probe = new CacheProbe(cache); probe.failAfterInvalidate = true;
        TraceIdContext.setTraceId("caller");
        assertThrows(IllegalStateException.class, () -> service(consumption, probe).consume(event, CACHE_INVALIDATION));
        assertEquals("caller", TraceIdContext.getRequiredTraceId()); assertEquals(0, receipts(event));
        assertEquals(0L, progress(event)); assertFalse(cache.putIfNewer(key, new VersionedCache.Value(3, "late"), POLICY));
        assertEquals(CONSUMED, consumer.consume(event, CACHE_INVALIDATION));
        assertEquals(1L, progress(event)); assertEquals(1, receipts(event));
    }

    /** 消费存储完成后出现失败，同一事务仍整体回滚；重复外部失效不能删去更新版本。 */
    @Test
    void rollsBackAfterReceiptWriteAndSafelyReappliesCacheEffect() {
        var key = key(); var event = cacheEvent(key, 5);
        OutboxConsumptionRepository failing = new OutboxConsumptionRepository() {
            /** {@inheritDoc} */
            @Override public Result begin(Delivery delivery, Group group) { return consumption.begin(delivery, group); }
            /** {@inheritDoc} */
            @Override public void complete(Delivery delivery, Group group) {
                consumption.complete(delivery, group); throw new IllegalStateException("模拟提交前失败");
            }
        };
        assertThrows(IllegalStateException.class, () -> service(failing, cache).consume(event, CACHE_INVALIDATION));
        assertEquals(0, receipts(event)); assertEquals(0L, progress(event));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id=?", String.class, event.message().eventId()));
        assertTrue(cache.putIfNewer(key, new VersionedCache.Value(6, "newer"), POLICY));
        assertEquals(CONSUMED, consumer.consume(event, CACHE_INVALIDATION));
        assertEquals("newer", cache.get(key, POLICY, Optional::empty).orElseThrow().payload());
    }

    /** 后序等待时不执行缓存，畸形补偿数据失败且不留凭据，不持有trace。 */
    @Test
    void waitsAndRejectsInvalidCachePayloadWithoutEffects() {
        var events = seed(CacheInvalidation.EVENT_TYPE, "{}", 2); var probe = new CacheProbe(cache);
        var service = service(consumption, probe);
        assertEquals(WAITING, service.consume(events.getLast(), CACHE_INVALIDATION));
        assertThrows(RuntimeException.class, () -> service.consume(events.getFirst(), CACHE_INVALIDATION));
        assertEquals(0, probe.invalidations); assertEquals(0, receipts(events.getFirst()));
        assertEquals(0L, progress(events.getFirst())); assertTrue(TraceIdContext.getTraceId().isEmpty());
    }

    /** 入口拒绝已有事务，防止返回结果早于外层提交。 */
    @Test
    void refusesOuterTransactionBeforeAnyEffect() {
        var event = cacheEvent(key(), 2);
        assertThrows(IllegalStateException.class,
                () -> unitOfWork.execute(() -> consumer.consume(event, CACHE_INVALIDATION)));
        assertEquals(0, receipts(event)); assertEquals(0L, progress(event));
    }

    /** 成功消费尚未确认的PENDING或FAILED原事件时保留来源和失败次数，不伪造发送确认。 */
    @Test
    void preservesUnconfirmedOriginWhenConsumptionCommits() {
        for (String original : List.of("PENDING", "FAILED")) {
            var event = seed("erp.probe.created", "{}", 1).getFirst();
            jdbc.update("UPDATE outbox_event SET status=?, retry_count=3 WHERE event_id=?", original, event.message().eventId());
            assertEquals(CONSUMED, consumer.consume(event, ERP_AUDIT));
            var row = jdbc.queryForMap("SELECT status, consumed_from_status, retry_count, lease_token FROM outbox_event WHERE event_id=?", event.message().eventId());
            assertEquals("CONSUMED", row.get("status")); assertEquals(original, row.get("consumed_from_status"));
            assertEquals(3, ((Number) row.get("retry_count")).intValue()); assertNull(row.get("lease_token"));
            assertEquals(1, receipts(event)); assertEquals(1L, progress(event));
        }
        assertTrue(OutboxStatus.PENDING.canTransitionTo(OutboxStatus.CONSUMED));
        assertTrue(OutboxStatus.FAILED.canTransitionTo(OutboxStatus.CONSUMED));
        assertFalse(OutboxStatus.CONSUMED.canTransitionTo(OutboxStatus.SENT));
    }

    /** 数据库领取夹具中消费先提交但租约仍有效时，保留令牌供模拟的确认结果写入SENT。 */
    @Test
    void retainsLiveLeaseForLaterConfirmedSend() {
        var event = seed("erp.probe.created", "{}", 1).getFirst();
        var claim = claimedFixture(event, 30);
        assertEquals(CONSUMED, consumer.consume(event, ERP_AUDIT));
        var before = jdbc.queryForMap("SELECT status, consumed_from_status, lease_token FROM outbox_event WHERE event_id=?", event.message().eventId());
        assertEquals("PROCESSING", before.get("status")); assertNull(before.get("consumed_from_status"));
        assertEquals(claim.token().toString(), before.get("lease_token"));
        assertTrue(unitOfWork.execute(() -> relay.finish(claim, OutboxPublisher.PublishResult.CONFIRMED, retryPolicy())));
        var after = jdbc.queryForMap("SELECT status, consumed_from_status, lease_token FROM outbox_event WHERE event_id=?", event.message().eventId());
        assertEquals("SENT", after.get("status")); assertNull(after.get("consumed_from_status")); assertNull(after.get("lease_token"));
    }

    /** 先前已持久化的模拟确认保持SENT，不为它添加未知确认来源或修改版本。 */
    @Test
    void preservesAlreadyConfirmedStatus() {
        var event = seed("erp.probe.created", "{}", 1).getFirst();
        jdbc.update("UPDATE outbox_event SET status='SENT', version=5 WHERE event_id=?", event.message().eventId());
        assertEquals(CONSUMED, consumer.consume(event, ERP_AUDIT));
        var row = jdbc.queryForMap("SELECT status, consumed_from_status, version FROM outbox_event WHERE event_id=?", event.message().eventId());
        assertEquals("SENT", row.get("status")); assertNull(row.get("consumed_from_status"));
        assertEquals(5L, ((Number) row.get("version")).longValue());
        assertEquals(1, receipts(event));
    }

    /** 数据库租约实际到期后消费会清除旧令牌并记录来源，迟到确认无法覆盖消费事实。 */
    @Test
    void fencesExpiredLeaseWithoutInventingConfirmation() {
        var event = seed("erp.probe.created", "{}", 1).getFirst();
        var claim = claimedFixture(event, 1);
        await().atMost(Duration.ofSeconds(4)).until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT lease_until <= UTC_TIMESTAMP(6) FROM outbox_event WHERE event_id=?", Boolean.class, event.message().eventId())));
        assertEquals(CONSUMED, consumer.consume(event, ERP_AUDIT));
        var row = jdbc.queryForMap("SELECT status, consumed_from_status, lease_token, lease_until FROM outbox_event WHERE event_id=?", event.message().eventId());
        assertEquals("CONSUMED", row.get("status")); assertEquals("PROCESSING", row.get("consumed_from_status"));
        assertNull(row.get("lease_token")); assertNull(row.get("lease_until"));
        assertFalse(unitOfWork.execute(() -> relay.finish(claim, OutboxPublisher.PublishResult.CONFIRMED, retryPolicy())));
        assertEquals(1, receipts(event)); assertEquals(1L, progress(event));
    }

    /** 新终态必须保存合法来源；版本达到上限时饱和而不阻断真实消费。 */
    @Test
    void rejectsInvalidOriginAndSaturatesExhaustedVersion() {
        var event = seed("erp.probe.created", "{}", 1).getFirst();
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE outbox_event SET status='CONSUMED' WHERE event_id=?", event.message().eventId()));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE outbox_event SET consumed_from_status='FAILED' WHERE event_id=?", event.message().eventId()));
        jdbc.update("UPDATE outbox_event SET version=9223372036854775807 WHERE event_id=?", event.message().eventId());
        assertEquals(CONSUMED, consumer.consume(event, ERP_AUDIT));
        assertEquals(1, receipts(event)); assertEquals(1L, progress(event));
        var row = jdbc.queryForMap("SELECT status, consumed_from_status, version FROM outbox_event WHERE event_id=?", event.message().eventId());
        assertEquals("CONSUMED", row.get("status")); assertEquals("PENDING", row.get("consumed_from_status"));
        assertEquals(Long.MAX_VALUE, ((Number) row.get("version")).longValue());
    }

    /**
     * 仅为随机事件设置真实数据库租约，模拟持久化领取，未真实发布；不扫描或修改其他聚合。
     * @param event 本测试原事件
     * @param seconds 数据库秒级租期
     * @return 与真实行一致的领取证据
     */
    private OutboxRelayRepository.Claim claimedFixture(Delivery event, int seconds) {
        UUID token = UUID.randomUUID();
        assertEquals(1, jdbc.update("UPDATE outbox_event SET status='PROCESSING', lease_token=?, lease_until=TIMESTAMPADD(SECOND, ?, UTC_TIMESTAMP(6)) WHERE event_id=?",
                token.toString(), seconds, event.message().eventId()));
        var expiry = jdbc.queryForObject("SELECT lease_until FROM outbox_event WHERE event_id=?", LocalDateTime.class, event.message().eventId());
        return new OutboxRelayRepository.Claim(event, token, expiry.toInstant(ZoneOffset.UTC), 0);
    }

    /**
     * 构造结果写回所需有界策略，成功分支不使用退避。
     * @return 固定测试策略
     */
    private OutboxRelayRepository.RetryPolicy retryPolicy() {
        return new OutboxRelayRepository.RetryPolicy(2, Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    /**
     * 用真实工作单元和受控故障端口构造应用用例，不替代数据库事务。
     * @param repository 存储端口
     * @param versionedCache 缓存端口
     * @return 应用入口
     */
    private OutboxConsumer service(OutboxConsumptionRepository repository, VersionedCache versionedCache) {
        return new OutboxConsumerApplicationService(unitOfWork, repository, versionedCache, json);
    }

    /**
     * 创建合法缓存失效事件。
     * @param key 随机缓存键
     * @param version 最低版本
     * @return 原始事件
     */
    private Delivery cacheEvent(String key, long version) {
        return seed(CacheInvalidation.EVENT_TYPE,
                json.writeValueAsString(new CacheInvalidation(key, version, POLICY.ttl().toMillis(), 0)), 1).getFirst();
    }

    /**
     * 创建本测试独享的聚合事件。
     * @param type 主题
     * @param payload 原始载荷
     * @param count 数量
     * @return 不可变投递序列
     */
    private List<Delivery> seed(String type, String payload, int count) {
        var aggregate = UUID.randomUUID().toString(); aggregates.add(aggregate); var events = new ArrayList<Delivery>();
        unitOfWork.execute(() -> {
            for (int index = 1; index <= count; index++) {
                var message = new OutboxMessage(UUID.randomUUID().toString(), type, aggregate,
                        Instant.parse("2026-10-26T10:00:00.123456Z"), payload, "consumer-it-trace");
                outbox.append(message); events.add(new Delivery(message, index));
            } return null;
        }); return List.copyOf(events);
    }

    /**
     * 登记仅供本用例清理的随机Redis键。
     * @return 合法业务缓存键
     */
    private String key() {
        var key = "biz:erp:consumer:" + UUID.randomUUID(); keys.add(key); return key;
    }

    /**
     * 独立读取事件凭据数量。
     * @param event 原事件
     * @return 凭据数
     */
    private int receipts(Delivery event) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_consumption WHERE event_id = ?",
                Integer.class, event.message().eventId());
    }

    /**
     * 独立读取聚合已消费进度。
     * @param event 原事件
     * @return 已消费序号
     */
    private long progress(Delivery event) {
        return jdbc.queryForObject("SELECT consumed_sequence FROM outbox_aggregate_progress WHERE aggregate_id = ?",
                Long.class, event.message().aggregateId());
    }

    /** 保留真实Redis效果，仅在实际失效后注入一次受控连接结果失败。 */
    private static final class CacheProbe implements VersionedCache {
        private final VersionedCache delegate;
        private int invalidations;
        private boolean failAfterInvalidate;

        /**
         * 包装真实缓存，不修改其版本规则。
         * @param delegate 实际Redis适配
         */
        private CacheProbe(VersionedCache delegate) { this.delegate = delegate; }

        /** {@inheritDoc} */
        @Override public Optional<Value> get(String key, Policy policy, Supplier<Optional<Value>> loader) {
            return delegate.get(key, policy, loader);
        }
        /** {@inheritDoc} */
        @Override public boolean putIfNewer(String key, Value value, Policy policy) { return delegate.putIfNewer(key, value, policy); }
        /** {@inheritDoc} */
        @Override public void invalidate(String key, long minimumVersion, Policy policy) {
            invalidations++; delegate.invalidate(key, minimumVersion, policy);
            if (failAfterInvalidate) throw new IllegalStateException("模拟Redis已执行但客户端未获成功");
        }
    }
}
