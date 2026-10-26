package com.patrick.stratum.starter.integration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.event.OutboxConsumptionRepository;
import com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group;
import com.patrick.stratum.domain.event.OutboxDeadLetterRepository;
import com.patrick.stratum.domain.event.OutboxDeadLetterRepository.Category;
import com.patrick.stratum.domain.event.OutboxDeadLetterRepository.Letter;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxPublisher.Delivery;
import com.patrick.stratum.domain.event.OutboxRelayRepository;
import com.patrick.stratum.domain.event.OutboxReplayRepository;
import com.patrick.stratum.domain.event.OutboxReplayRepository.Request;
import com.patrick.stratum.domain.event.OutboxRepository;
import com.patrick.stratum.infrastructure.outbox.OutboxReplayRepositoryImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static com.patrick.stratum.domain.event.OutboxReplayRepository.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实数据库验证人工请求登记、原事件核验、独立投递意图与事务竞争；不发送MQ。 */
class OutboxReplayRequestIntegrationTest extends MariaDbIntegrationTest {
    @Autowired private UnitOfWork unitOfWork;
    @Autowired private OutboxRepository outbox;
    @Autowired private OutboxDeadLetterRepository deadLetters;
    @Autowired private OutboxConsumptionRepository consumption;
    @Autowired private OutboxReplayRepository replay;
    @Autowired private OutboxRelayRepository relay;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    @Autowired private PlatformTransactionManager transactionManager;
    private final List<String> aggregates = new ArrayList<>();
    private final List<UUID> archives = new ArrayList<>();

    /** 按外键顺序仅清理自身请求、意图、归档、消费凭据与原事件。 */
    @AfterEach
    void cleanup() {
        for (var id : archives) jdbc.update("DELETE FROM outbox_replay_request WHERE dead_id = ?", id.toString());
        for (var id : archives) jdbc.update("DELETE FROM outbox_replay_delivery WHERE dead_id = ?", id.toString());
        for (var id : archives) jdbc.update("DELETE FROM outbox_dead_letter WHERE dead_id = ?", id.toString());
        for (var aggregate : aggregates) {
            jdbc.update("DELETE FROM outbox_consumption WHERE aggregate_id = ?", aggregate);
            jdbc.update("DELETE FROM outbox_event WHERE aggregate_id = ?", aggregate);
            jdbc.update("DELETE FROM outbox_aggregate_progress WHERE aggregate_id = ?", aggregate);
        }
    }

    /** 人工请求只创建新投递意图，原事件身份/原文/SENT状态和消费进度不改变。 */
    @Test
    void registersIndependentIntentWithoutChangingOriginalEvent() {
        var event = seed(1, true).getFirst(); var archive = archive(event, body(event)); var request = request(archive);
        var result = enqueue(request); assertEquals(QUEUED, result.outcome());
        assertEquals(request.requestId(), result.deliveryId().orElseThrow());
        var row = jdbc.queryForMap("SELECT * FROM outbox_replay_delivery WHERE delivery_id = ?", result.deliveryId().orElseThrow().toString());
        assertEquals(event.message().eventId(), row.get("event_id")); assertEquals(event.message().aggregateId(), row.get("aggregate_id"));
        assertEquals(1L, ((Number) row.get("event_sequence")).longValue()); assertEquals("PENDING", row.get("status"));
        assertEquals("SENT", status(event)); assertEquals(0L, progress(event));
        assertEquals(event.message().payload(), jdbc.queryForObject("SELECT payload FROM outbox_event WHERE event_id = ?", String.class, event.message().eventId()));
        assertEquals("REPLAY_REQUESTED", jdbc.queryForObject("SELECT status FROM outbox_dead_letter WHERE dead_id = ?", String.class, archive.toString()));
        assertEquals("replay-request-trace", jdbc.queryForObject("SELECT trace_id FROM outbox_replay_request WHERE request_id = ?", String.class, request.requestId().toString()));
    }

    /** 同请求稳定返回，新的请求指向相同归档仍复用意图；请求ID不能更换目标。 */
    @Test
    void deduplicatesRequestsAndRejectsChangedTarget() {
        var event = seed(1, true).getFirst(); var archive = archive(event, body(event)); var request = request(archive);
        var first = enqueue(request); assertEquals(first, enqueue(request));
        var another = enqueue(request(archive)); assertEquals(ALREADY_QUEUED, another.outcome()); assertEquals(first.deliveryId(), another.deliveryId());
        assertEquals(1, intentCount(archive)); assertEquals(2, requestCount(archive));
        var otherEvent = seed(1, true).getFirst(); var otherArchive = archive(otherEvent, body(otherEvent));
        var failure = assertThrows(StratumException.class, () -> enqueue(new Request(request.requestId(), otherArchive, "new-trace")));
        assertEquals(40900, failure.getErrorCode().getCode()); assertEquals(0, intentCount(otherArchive)); assertEquals(0, requestCount(otherArchive));
    }

    /** 外层失败回滚请求、意图与归档状态，不能留下可见的登记中占位。 */
    @Test
    void rollsBackRequestIntentAndArchiveStateTogether() {
        var event = seed(1, true).getFirst(); var archive = archive(event, body(event)); var request = request(archive);
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            replay.enqueue(request); throw new IllegalStateException("测试登记后回滚");
        }));
        assertEquals(0, intentCount(archive)); assertEquals(0, requestCount(archive));
        assertEquals("DEAD", jdbc.queryForObject("SELECT status FROM outbox_dead_letter WHERE dead_id = ?", String.class, archive.toString()));
        assertEquals(QUEUED, enqueue(request).outcome());
    }

    /** 已有真实消费凭据时只登记已消费回执，不产生新发送。 */
    @Test
    void recognizesAlreadyConsumedEvent() {
        var event = seed(1, true).getFirst();
        unitOfWork.execute(() -> { consumption.complete(event, Group.ERP_AUDIT); return null; });
        var archive = archive(event, body(event)); var request = request(archive); var result = enqueue(request);
        assertEquals(ALREADY_CONSUMED, result.outcome()); assertTrue(result.deliveryId().isEmpty());
        assertEquals(result, enqueue(request)); assertEquals(0, intentCount(archive)); assertEquals(1, requestCount(archive));
        assertEquals("RESOLVED", jdbc.queryForObject("SELECT status FROM outbox_dead_letter WHERE dead_id = ?", String.class, archive.toString()));
        assertEquals(1L, progress(event)); assertEquals("SENT", status(event));
    }

    /** 隔离、未知原事件、缺失归档及非头证据不能创建重放请求。 */
    @Test
    void rejectsQuarantineUnknownAndNonHeadEvidence() {
        var invalid = new Letter(UUID.randomUUID(), Group.ERP_AUDIT, new byte[0], "{}", Optional.empty(), Category.INVALID_ENVELOPE, 0);
        var quarantine = unitOfWork.execute(() -> deadLetters.append(invalid)); archives.add(quarantine);
        assertError(40000, request(quarantine)); assertEquals(0, requestCount(quarantine));
        assertError(40400, request(UUID.randomUUID()));
        var unknown = new Delivery(new OutboxMessage(UUID.randomUUID().toString(), "erp.probe.created", UUID.randomUUID().toString(),
                Instant.parse("2026-10-26T10:00:00Z"), "{}", "unknown"), 1);
        var unknownArchive = archive(unknown, body(unknown)); assertError(40000, request(unknownArchive)); assertEquals(0, intentCount(unknownArchive));
        var later = seed(2, true).getLast(); var nonHead = archive(later, body(later));
        assertError(40900, request(nonHead)); assertEquals(0, requestCount(nonHead));
    }

    /** 解析声明相符仍须核验原体，拒绝篡改、重复字段、非法UTF-8和隐式序号类型。 */
    @Test
    void independentlyVerifiesArchivedRawBody() {
        var event = seed(1, true).getFirst(); var raw = new String(body(event), java.nio.charset.StandardCharsets.UTF_8);
        var bad = List.of("{}", raw + " {}", raw.replace("\"sequence\":1", "\"sequence\":1.0"),
                raw.replace("999999999999999999.123456789012", "1"), raw.replaceFirst("\\{", "{\"eventId\":\"duplicate\","));
        for (var body : bad) {
            var id = archive(event, body.getBytes(java.nio.charset.StandardCharsets.UTF_8)); assertError(40000, request(id)); assertEquals(0, requestCount(id));
        }
        var badUtf8 = archive(event, new byte[] {(byte) 0xff}); assertError(40000, request(badUtf8));
        var message = event.message(); var forged = new Delivery(new OutboxMessage(message.eventId(), message.eventType(), message.aggregateId(),
                message.occurredAt(), "{}", message.traceId()), 1);
        var forgedId = archive(forged, body(forged)); assertError(40000, request(forgedId)); assertEquals(0, intentCount(forgedId));
    }

    /** 原事件仍待发送或持有效领取时拒绝另建重放，避免绕过原投递竞争。 */
    @Test
    void refusesOriginalDeliveryStillInProgress() {
        var event = seed(1, false).getFirst(); var archive = archive(event, body(event));
        assertError(40900, request(archive)); assertEquals("PENDING", status(event)); assertEquals(0, requestCount(archive));
        var claims = unitOfWork.execute(() -> relay.claim(new OutboxRelayRepository.ClaimRequest(1, java.time.Duration.ofSeconds(30))));
        assertEquals(1, claims.size()); assertEquals(event.message().eventId(), claims.getFirst().delivery().message().eventId());
        assertError(40900, request(archive)); assertEquals("PROCESSING", status(event)); assertEquals(0, intentCount(archive));
    }

    /** 失败意图可由新人工请求恢复，旧回执稳定，原失败计数留在新请求记录。 */
    @Test
    void requeuesFailedIntentWithoutRewritingOriginalHistory() {
        var event = seed(1, true).getFirst(); var archive = archive(event, body(event)); var firstRequest = request(archive);
        var first = enqueue(firstRequest); var id = first.deliveryId().orElseThrow();
        jdbc.update("UPDATE outbox_replay_delivery SET status = 'FAILED', retry_count = 3 WHERE delivery_id = ?", id.toString());
        var retry = request(archive); var result = enqueue(retry);
        assertEquals(QUEUED, result.outcome()); assertEquals(first.deliveryId(), result.deliveryId()); assertEquals(first, enqueue(firstRequest));
        assertEquals(0, jdbc.queryForObject("SELECT retry_count FROM outbox_replay_delivery WHERE delivery_id = ?", Integer.class, id.toString()));
        assertEquals(3, jdbc.queryForObject("SELECT previous_failures FROM outbox_replay_request WHERE request_id = ?", Integer.class, retry.requestId().toString()));
        assertEquals("SENT", status(event)); assertEquals(0L, progress(event));
    }

    /** 同事件不同发布归档不能同时建立两个待发送意图，确认后另一归档可被显式恢复。 */
    @Test
    void protectsSinglePendingIntentAcrossArchives() {
        var event = seed(1, true).getFirst(); var firstArchive = archive(event, body(event)); var secondArchive = archive(event, body(event));
        var first = enqueue(request(firstArchive)); var secondRequest = request(secondArchive);
        assertError(40900, secondRequest); assertEquals(0, requestCount(secondArchive)); assertEquals(0, intentCount(secondArchive));
        jdbc.update("UPDATE outbox_replay_delivery SET status = 'SENT' WHERE delivery_id = ?", first.deliveryId().orElseThrow().toString());
        assertEquals(QUEUED, enqueue(secondRequest).outcome()); assertEquals(1, intentCount(secondArchive));
        assertEquals(ALREADY_QUEUED, enqueue(request(firstArchive)).outcome());
    }

    /** 存储必须在可写工作单元中调用，值对象拒绝非法关联标识。 */
    @Test
    void requiresWritableTransactionAndValidCorrelation() {
        var event = seed(1, true).getFirst(); var archive = archive(event, body(event)); var request = request(archive);
        assertThrows(IllegalStateException.class, () -> replay.enqueue(request));
        var readOnly = new TransactionTemplate(transactionManager); readOnly.setReadOnly(true);
        assertThrows(IllegalStateException.class, () -> readOnly.execute(status -> replay.enqueue(request)));
        assertThrows(IllegalArgumentException.class, () -> new Request(UUID.randomUUID(), archive, "unsafe\ntrace"));
        assertEquals(0, requestCount(archive));
    }

    /** 两个实例并发恢复同归档：持锁竞争返回冲突，回滚后新事务复用唯一意图。 */
    @Test
    void serializesConcurrentRequestsAndRetriesWholeUnit() throws Exception {
        var event = seed(1, true).getFirst(); var archive = archive(event, body(event)); var first = request(archive); var other = request(archive);
        var second = new OutboxReplayRepositoryImpl(jdbc, json, consumption);
        var held = new CountDownLatch(1); var release = new CountDownLatch(1); var firstConnection = new AtomicLong(); var otherConnection = new AtomicLong();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var owner = executor.submit(() -> unitOfWork.execute(() -> {
                firstConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                var result = replay.enqueue(first); held.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                return result;
            }));
            try {
                assertTrue(held.await(5, TimeUnit.SECONDS));
                var competitor = executor.submit(() -> unitOfWork.execute(() -> {
                    otherConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)); return second.enqueue(other);
                }));
                var conflict = assertThrows(java.util.concurrent.ExecutionException.class, () -> competitor.get(3, TimeUnit.SECONDS));
                assertEquals(40900, assertInstanceOf(StratumException.class, conflict.getCause()).getErrorCode().getCode());
                assertNotEquals(firstConnection.get(), otherConnection.get()); release.countDown();
                var created = owner.get(5, TimeUnit.SECONDS); var retried = unitOfWork.execute(() -> second.enqueue(other));
                assertEquals(ALREADY_QUEUED, retried.outcome()); assertEquals(created.deliveryId(), retried.deliveryId());
                assertEquals(1, intentCount(archive)); assertEquals(2, requestCount(archive));
            } finally { release.countDown(); }
        }
    }

    /**
     * 建立真实本地事件；SENT仅为本节点技术前置夹具，不宣称真实Broker确认。
     * @param count 同聚合条数
     * @param sent 是否构造已结束的原投递状态
     * @return 原始投递列表
     */
    private List<Delivery> seed(int count, boolean sent) {
        var aggregate = UUID.randomUUID().toString(); aggregates.add(aggregate); var events = new ArrayList<Delivery>();
        unitOfWork.execute(() -> {
            for (int index = 1; index <= count; index++) {
                var event = new OutboxMessage(UUID.randomUUID().toString(), "erp.probe.created", aggregate,
                        Instant.parse("2026-10-26T10:00:00.123456Z"), "{\"amount\":999999999999999999.123456789012}", "original-event-trace");
                outbox.append(event); events.add(new Delivery(event, index));
            } return null;
        });
        if (sent) jdbc.update("UPDATE outbox_event SET status = 'SENT' WHERE aggregate_id = ?", aggregate);
        return List.copyOf(events);
    }

    /**
     * 归档本用例证据，不把原体与解析声明的一致性预先假设为可信。
     * @param delivery 声明身份
     * @param raw 原体
     * @return 归档UUID
     */
    private UUID archive(Delivery delivery, byte[] raw) {
        var letter = new Letter(UUID.randomUUID(), Group.ERP_AUDIT, raw,
                "{\"publication\":\"" + UUID.randomUUID() + "\"}", Optional.of(delivery), Category.RETRIES_EXHAUSTED, 6);
        var id = unitOfWork.execute(() -> deadLetters.append(letter)); archives.add(id); return id;
    }

    /**
     * 按真实传输格式构造无损JSON信封。
     * @param delivery 原事件
     * @return UTF-8原体
     */
    private byte[] body(Delivery delivery) {
        var event = delivery.message();
        return json.writeValueAsBytes(Map.of("eventId", event.eventId(), "eventType", event.eventType(), "aggregateId", event.aggregateId(),
                "occurredAt", event.occurredAt().toString(), "traceId", event.traceId(), "sequence", delivery.sequence(),
                "payload", json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(event.payload())));
    }

    /**
     * 产生新的人工操作身份。
     * @param archiveId 归档UUID
     * @return 安全请求
     */
    private Request request(UUID archiveId) { return new Request(UUID.randomUUID(), archiveId, "replay-request-trace"); }

    /**
     * 在应用事务内调用技术写端口。
     * @param request 人工请求
     * @return 稳定回执
     */
    private OutboxReplayRepository.Receipt enqueue(Request request) { return unitOfWork.execute(() -> replay.enqueue(request)); }

    /**
     * 验证失败码，避免把预期拒绝误当作持久化成功。
     * @param code 预期错误码
     * @param request 当前请求
     */
    private void assertError(int code, Request request) {
        var failure = assertThrows(StratumException.class, () -> enqueue(request)); assertEquals(code, failure.getErrorCode().getCode());
    }

    /**
     * 查询某归档的请求数量。
     * @param archive 归档UUID
     * @return 请求数
     */
    private int requestCount(UUID archive) { return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_replay_request WHERE dead_id = ?", Integer.class, archive.toString()); }

    /**
     * 查询某归档独立投递数量。
     * @param archive 归档UUID
     * @return 意图数
     */
    private int intentCount(UUID archive) { return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_replay_delivery WHERE dead_id = ?", Integer.class, archive.toString()); }

    /**
     * 查询原Outbox状态。
     * @param event 原事件
     * @return 原状态
     */
    private String status(Delivery event) { return jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id = ?", String.class, event.message().eventId()); }

    /**
     * 查询原聚合真实消费进度。
     * @param event 原事件
     * @return 已消费序号
     */
    private long progress(Delivery event) { return jdbc.queryForObject("SELECT consumed_sequence FROM outbox_aggregate_progress WHERE aggregate_id = ?", Long.class, event.message().aggregateId()); }
}
