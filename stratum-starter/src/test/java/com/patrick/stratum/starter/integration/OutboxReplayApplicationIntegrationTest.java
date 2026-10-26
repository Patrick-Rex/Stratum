package com.patrick.stratum.starter.integration;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.patrick.stratum.application.event.OutboxConsumerApplicationService;
import com.patrick.stratum.application.event.OutboxReplayApplicationService;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.base.cache.VersionedCache;
import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.domain.event.OutboxConsumptionRepository;
import com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group;
import com.patrick.stratum.domain.event.OutboxConsumer;
import com.patrick.stratum.domain.event.OutboxDeadLetterRepository;
import com.patrick.stratum.domain.event.OutboxDeadLetterRepository.Category;
import com.patrick.stratum.domain.event.OutboxDeadLetterRepository.Letter;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxPublisher;
import com.patrick.stratum.domain.event.OutboxPublisher.Delivery;
import com.patrick.stratum.domain.event.OutboxRelayRepository;
import com.patrick.stratum.domain.event.OutboxReplayRepository;
import com.patrick.stratum.domain.event.OutboxReplayRequester;
import com.patrick.stratum.domain.event.OutboxRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static com.patrick.stratum.domain.event.OutboxReplayRepository.Outcome.*;

/** 真实应用事务验证人工登记提交、消费恢复关联及回滚；Broker闭环由下一节点验收。 */
class OutboxReplayApplicationIntegrationTest extends MariaDbIntegrationTest {
    @Autowired private UnitOfWork unitOfWork;
    @Autowired private OutboxReplayRequester requester;
    @Autowired private OutboxReplayRepository replay;
    @Autowired private OutboxRepository outbox;
    @Autowired private OutboxDeadLetterRepository deadLetters;
    @Autowired private OutboxConsumer consumer;
    @Autowired private OutboxConsumptionRepository consumption;
    @Autowired private OutboxRelayRepository relay;
    @Autowired private ObjectProvider<OutboxPublisher> publisher;
    @Autowired private VersionedCache cache;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    private final List<String> aggregates = new ArrayList<>();
    private final List<UUID> archives = new ArrayList<>();

    /** 按引用顺序只清理本用例持久化记录及线程上下文。 */
    @AfterEach
    void cleanup() {
        TraceIdContext.clear();
        for (var id : archives) jdbc.update("DELETE FROM outbox_replay_request WHERE dead_id = ?", id.toString());
        for (var id : archives) jdbc.update("DELETE FROM outbox_replay_delivery WHERE dead_id = ?", id.toString());
        for (var id : archives) jdbc.update("DELETE FROM outbox_dead_letter WHERE dead_id = ?", id.toString());
        for (var aggregate : aggregates) {
            jdbc.update("DELETE FROM outbox_consumption WHERE aggregate_id = ?", aggregate);
            jdbc.update("DELETE FROM outbox_event WHERE aggregate_id = ?", aggregate);
            jdbc.update("DELETE FROM outbox_aggregate_progress WHERE aggregate_id = ?", aggregate);
        }
    }

    /** 返回时独立连接已见登记，入口没有发布器依赖，操作trace恢复且原事件仍未消费。 */
    @Test
    void commitsRequestWithoutPublishingAndRestoresTrace() throws Exception {
        var fixture = fixture(); TraceIdContext.setTraceId("caller");
        var receipt = requester.request(fixture.request()); assertEquals(QUEUED, receipt.outcome());
        try (var connection = jdbc.getDataSource().getConnection();
                var statement = connection.prepareStatement("SELECT COUNT(*) FROM outbox_replay_request WHERE request_id = ?")) {
            statement.setString(1, fixture.request().requestId().toString());
            try (var rows = statement.executeQuery()) { assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); }
        }
        assertEquals("caller", TraceIdContext.getRequiredTraceId()); assertNull(publisher.getIfAvailable());
        assertEquals(0L, progress(fixture)); assertEquals("SENT", originalStatus(fixture));
        assertEquals("PENDING", intentStatus(receipt.deliveryId().orElseThrow()));
    }

    /** 真实消费提交同时恢复归档和意图，旧请求回执保留历史，新请求直接识别已消费。 */
    @Test
    void resolvesRequestedArchiveAndIntentWithinConsumptionTransaction() {
        var fixture = fixture(); var receipt = requester.request(fixture.request());
        assertEquals(OutboxConsumer.Outcome.CONSUMED, consumer.consume(fixture.delivery(), Group.ERP_AUDIT));
        assertEquals("RESOLVED", intentStatus(receipt.deliveryId().orElseThrow())); assertEquals("RESOLVED", archiveStatus(fixture));
        assertEquals(1L, progress(fixture)); assertEquals("SENT", originalStatus(fixture));
        assertNotNull(jdbc.queryForObject("SELECT resolved_at FROM outbox_replay_delivery WHERE delivery_id = ?", java.time.LocalDateTime.class, receipt.deliveryId().orElseThrow().toString()));
        assertEquals(receipt, requester.request(fixture.request()));
        var repeated = requester.request(new OutboxReplayRepository.Request(UUID.randomUUID(), fixture.archiveId(), "new-request"));
        assertEquals(ALREADY_CONSUMED, repeated.outcome()); assertTrue(repeated.deliveryId().isEmpty());
        assertEquals(OutboxConsumer.Outcome.DUPLICATE, consumer.consume(fixture.delivery(), Group.ERP_AUDIT));
    }

    /** 消费先于发布结果落盘时，已恢复意图清除租约，迟到确认不得覆盖消费成功事实。 */
    @Test
    void resolvesInFlightIntentWithoutInventingPublisherConfirmation() {
        var fixture = fixture(); var receipt = requester.request(fixture.request());
        var claim = unitOfWork.execute(() -> relay.claim(new OutboxRelayRepository.ClaimRequest(1, Duration.ofSeconds(30)))).getFirst();
        assertEquals("PROCESSING", intentStatus(receipt.deliveryId().orElseThrow()));
        assertEquals(OutboxConsumer.Outcome.CONSUMED, consumer.consume(fixture.delivery(), Group.ERP_AUDIT));
        assertFalse(unitOfWork.execute(() -> relay.finish(claim, OutboxPublisher.PublishResult.CONFIRMED,
                new OutboxRelayRepository.RetryPolicy(2, Duration.ofSeconds(1), Duration.ofSeconds(1)))));
        var row = jdbc.queryForMap("SELECT status, lease_token, lease_until, sent_at FROM outbox_replay_delivery WHERE delivery_id = ?", receipt.deliveryId().orElseThrow().toString());
        assertEquals("RESOLVED", row.get("status")); assertNull(row.get("lease_token")); assertNull(row.get("lease_until")); assertNull(row.get("sent_at"));
        assertEquals("SENT", originalStatus(fixture));
    }

    /** 消费恢复后再发生失败必须同时回滚凭据、顺序、归档和意图，重试后才恢复。 */
    @Test
    void rollsBackAllConsumptionRecoveryStateTogether() {
        var fixture = fixture(); var receipt = requester.request(fixture.request());
        OutboxConsumptionRepository failing = new OutboxConsumptionRepository() {
            /** {@inheritDoc} */
            @Override public Result begin(Delivery delivery, Group group) { return consumption.begin(delivery, group); }
            /** {@inheritDoc} */
            @Override public void complete(Delivery delivery, Group group) {
                consumption.complete(delivery, group); throw new IllegalStateException("恢复关联后模拟故障");
            }
        };
        var service = new OutboxConsumerApplicationService(unitOfWork, failing, cache, json);
        assertThrows(IllegalStateException.class, () -> service.consume(fixture.delivery(), Group.ERP_AUDIT));
        assertEquals("PENDING", intentStatus(receipt.deliveryId().orElseThrow())); assertEquals("REPLAY_REQUESTED", archiveStatus(fixture));
        assertEquals(0L, progress(fixture)); assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_consumption WHERE event_id = ?", Integer.class, fixture.delivery().message().eventId()));
        consumer.consume(fixture.delivery(), Group.ERP_AUDIT); assertEquals("RESOLVED", intentStatus(receipt.deliveryId().orElseThrow()));
    }

    /** 登记后失败或错误空回执都回滚，不提交悬挂意图且恢复原线程trace。 */
    @Test
    void rollsBackFailedApplicationRequestAndNullReceipt() {
        var fixture = fixture(); TraceIdContext.setTraceId("caller");
        var failing = new OutboxReplayApplicationService(unitOfWork, request -> { replay.enqueue(request); throw new IllegalStateException("登记后故障"); });
        assertThrows(IllegalStateException.class, () -> failing.request(fixture.request())); assertEquals("caller", TraceIdContext.getRequiredTraceId());
        var empty = new OutboxReplayApplicationService(unitOfWork, request -> { replay.enqueue(request); return null; });
        assertThrows(NullPointerException.class, () -> empty.request(fixture.request()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_replay_delivery WHERE dead_id = ?", Integer.class, fixture.archiveId().toString()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_replay_request WHERE dead_id = ?", Integer.class, fixture.archiveId().toString()));
        assertEquals("DEAD", archiveStatus(fixture)); assertEquals("caller", TraceIdContext.getRequiredTraceId());
    }

    /** 外层事务在调用前拒绝，防止返回早于最终提交或混入外部命令事务。 */
    @Test
    void refusesOuterTransactionBeforeRegistering() {
        var fixture = fixture();
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> requester.request(fixture.request())));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_replay_request WHERE dead_id = ?", Integer.class, fixture.archiveId().toString()));
        assertEquals("DEAD", archiveStatus(fixture));
    }

    /**
     * 建立技术前置状态；SENT为SQL夹具，不当作真实发布证明。
     * @return 原事件、归档及人工请求
     */
    private Fixture fixture() {
        var aggregate = UUID.randomUUID().toString(); aggregates.add(aggregate);
        var event = new OutboxMessage(UUID.randomUUID().toString(), "erp.probe.created", aggregate,
                Instant.parse("2026-10-26T10:00:00.123456Z"), "{\"amount\":999999999999999999.123456789012}", "original-replay-app");
        var delivery = new Delivery(event, 1);
        unitOfWork.execute(() -> { outbox.append(event); return null; });
        jdbc.update("UPDATE outbox_event SET status = 'SENT' WHERE event_id = ?", event.eventId());
        byte[] body = json.writeValueAsBytes(Map.of("eventId", event.eventId(), "eventType", event.eventType(), "aggregateId", aggregate,
                "occurredAt", event.occurredAt().toString(), "traceId", event.traceId(), "sequence", 1,
                "payload", json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(event.payload())));
        var archive = unitOfWork.execute(() -> deadLetters.append(new Letter(UUID.randomUUID(), Group.ERP_AUDIT, body,
                "{}", Optional.of(delivery), Category.RETRIES_EXHAUSTED, 6))); archives.add(archive);
        return new Fixture(delivery, archive, new OutboxReplayRepository.Request(UUID.randomUUID(), archive, "manual-replay-app"));
    }

    /**
     * 查询独立意图状态。
     * @param id 意图UUID
     * @return 当前状态
     */
    private String intentStatus(UUID id) { return jdbc.queryForObject("SELECT status FROM outbox_replay_delivery WHERE delivery_id = ?", String.class, id.toString()); }

    /**
     * 查询原Outbox状态。
     * @param fixture 自身夹具
     * @return 原投递状态
     */
    private String originalStatus(Fixture fixture) { return jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id = ?", String.class, fixture.delivery().message().eventId()); }

    /**
     * 查询归档恢复状态。
     * @param fixture 自身夹具
     * @return 归档状态
     */
    private String archiveStatus(Fixture fixture) { return jdbc.queryForObject("SELECT status FROM outbox_dead_letter WHERE dead_id = ?", String.class, fixture.archiveId().toString()); }

    /**
     * 查询聚合消费进度。
     * @param fixture 自身夹具
     * @return 已消费序号
     */
    private long progress(Fixture fixture) { return jdbc.queryForObject("SELECT consumed_sequence FROM outbox_aggregate_progress WHERE aggregate_id = ?", Long.class, fixture.delivery().message().aggregateId()); }

    /**
     * 当前用例不可变夹具，不包含外部连接凭据。
     * @param delivery 原始事件
     * @param archiveId 原归档UUID
     * @param request 人工操作
     */
    private record Fixture(Delivery delivery, UUID archiveId, OutboxReplayRepository.Request request) { }
}
