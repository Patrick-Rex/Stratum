package com.patrick.stratum.starter.integration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group;
import com.patrick.stratum.domain.event.OutboxDeadLetterRepository;
import com.patrick.stratum.domain.event.OutboxDeadLetterRepository.Category;
import com.patrick.stratum.domain.event.OutboxDeadLetterRepository.Letter;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxPublisher.Delivery;
import com.patrick.stratum.domain.event.OutboxRepository;
import com.patrick.stratum.infrastructure.outbox.OutboxDeadLetterRepositoryImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;

import static org.junit.jupiter.api.Assertions.*;

/** 真实MariaDB核验原死信证据、稳定去重、隔离归档、回滚和跨实例竞争。 */
class OutboxDeadLetterIntegrationTest extends MariaDbIntegrationTest {
    @Autowired private UnitOfWork unitOfWork;
    @Autowired private OutboxDeadLetterRepository deadLetters;
    @Autowired private OutboxRepository outbox;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JsonMapper json;
    @Autowired private PlatformTransactionManager transactionManager;
    private final List<UUID> ids = new ArrayList<>();
    private final List<String> aggregates = new ArrayList<>();

    /** 只清理自身归档、事件及技术进度。 */
    @AfterEach
    void cleanup() {
        for (var id : ids) jdbc.update("DELETE FROM outbox_dead_letter WHERE dead_id = ?", id.toString());
        for (var aggregate : aggregates) {
            jdbc.update("DELETE FROM outbox_event WHERE aggregate_id = ?", aggregate);
            jdbc.update("DELETE FROM outbox_aggregate_progress WHERE aggregate_id = ?", aggregate);
        }
    }

    /** 归档原体、高精度载荷和身份，不改变原Outbox状态或消费进度。 */
    @Test
    void preservesEvidenceWithoutAdvancingConsumption() {
        var delivery = delivery(); aggregates.add(delivery.message().aggregateId());
        unitOfWork.execute(() -> { outbox.append(delivery.message()); return null; });
        var letter = valid(delivery, "{\"publicationId\":\"one\"}", 6); var id = append(letter);
        var row = jdbc.queryForMap("SELECT * FROM outbox_dead_letter WHERE dead_id = ?", id.toString());
        assertArrayEquals(letter.body(), (byte[]) row.get("body"));
        assertEquals(delivery.message().payload(), row.get("payload")); assertEquals(delivery.message().traceId(), row.get("trace_id"));
        assertEquals(delivery.message().eventId(), row.get("event_id")); assertEquals(1L, ((Number) row.get("event_sequence")).longValue());
        assertEquals("DEAD", row.get("status")); assertEquals(6L, ((Number) row.get("failure_count")).longValue());
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id = ?", String.class, delivery.message().eventId()));
        assertEquals(0L, jdbc.queryForObject("SELECT consumed_sequence FROM outbox_aggregate_progress WHERE aggregate_id = ?",
                Long.class, delivery.message().aggregateId()));
    }

    /** 元数据字段顺序不影响去重；重复返回首次ID，保留首份原文并只增加收存次数。 */
    @Test
    void deduplicatesCanonicalMetadataAndKeepsHighestFailureCount() {
        var delivery = delivery(); var first = valid(delivery, "{\"b\":2,\"a\":{\"d\":4,\"c\":3}}", 6);
        var id = append(first); var repeated = valid(delivery, "{\"a\":{\"c\":3,\"d\":4},\"b\":2}", 3);
        assertEquals(id, append(repeated));
        var row = jdbc.queryForMap("SELECT seen_count, failure_count, metadata_json FROM outbox_dead_letter WHERE dead_id = ?", id.toString());
        assertEquals(2L, ((Number) row.get("seen_count")).longValue()); assertEquals(6L, ((Number) row.get("failure_count")).longValue());
        assertEquals(first.metadataJson(), row.get("metadata_json"));
        assertNotEquals(id, append(valid(delivery, "{\"publicationId\":\"another\"}", 6)));
    }

    /** 非法UTF-8及空原体可隔离保留，没有本地原事件也不会创建聚合进度。 */
    @Test
    void quarantinesMalformedAndEmptyBodiesWithoutBusinessIdentity() {
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_aggregate_progress", Long.class);
        var raw = new byte[] {(byte) 0xff, 0, (byte) 0xc0};
        var id = append(invalid(raw, "{\"source\":\"malformed\"}"));
        var row = jdbc.queryForMap("SELECT body, event_id, status FROM outbox_dead_letter WHERE dead_id = ?", id.toString());
        assertArrayEquals(raw, (byte[]) row.get("body")); assertNull(row.get("event_id")); assertEquals("QUARANTINED", row.get("status"));
        var empty = append(invalid(new byte[0], "{}"));
        assertEquals(0, jdbc.queryForObject("SELECT OCTET_LENGTH(body) FROM outbox_dead_letter WHERE dead_id = ?", Integer.class, empty.toString()));
        assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_aggregate_progress", Long.class));
        assertNotEquals(empty, append(new Letter(UUID.randomUUID(), Group.CACHE_INVALIDATION, new byte[0], "{}",
                Optional.empty(), Category.INVALID_ENVELOPE, 0)));
    }

    /** 外层回滚同时撤销首次归档和重复收存计数。 */
    @Test
    void rollsBackNewEvidenceAndDuplicateCounters() {
        var letter = valid(delivery(), "{}", 6);
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            deadLetters.append(letter); throw new IllegalStateException("测试回滚");
        }));
        assertEquals(0, count(letter.id()));
        var id = append(letter);
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> {
            deadLetters.append(letter); throw new IllegalStateException("测试重复回滚");
        }));
        assertEquals(1L, jdbc.queryForObject("SELECT seen_count FROM outbox_dead_letter WHERE dead_id = ?", Long.class, id.toString()));
    }

    /** 必须参与可写事务，严格元数据拒绝重复字段、非对象或尾随内容。 */
    @Test
    void requiresWriteTransactionAndStrictMetadata() {
        var letter = invalid(new byte[0], "{}");
        assertThrows(IllegalStateException.class, () -> deadLetters.append(letter));
        var readOnly = new TransactionTemplate(transactionManager); readOnly.setReadOnly(true);
        assertThrows(IllegalStateException.class, () -> readOnly.execute(status -> deadLetters.append(letter)));
        for (var metadata : List.of("[]", "{} {}", "{\"x\":1,\"x\":2}", "invalid")) {
            assertThrows(IllegalArgumentException.class, () -> append(invalid(new byte[0], metadata)));
        }
    }

    /** 归档ID复用但证据不同必须整体失败，不能修改已有归档正文或计数。 */
    @Test
    void rejectsReusedIdWithDifferentEvidence() {
        var original = invalid(new byte[] {1}, "{}"); var id = append(original);
        var changed = new Letter(id, Group.ERP_AUDIT, new byte[] {2}, "{}", Optional.empty(), Category.INVALID_ENVELOPE, 0);
        assertThrows(IllegalStateException.class, () -> append(changed));
        assertArrayEquals(new byte[] {1}, jdbc.queryForObject("SELECT body FROM outbox_dead_letter WHERE dead_id = ?", byte[].class, id.toString()));
        assertEquals(1L, jdbc.queryForObject("SELECT seen_count FROM outbox_dead_letter WHERE dead_id = ?", Long.class, id.toString()));
    }

    /** 主键和另一行证据同时冲突时必须回滚，不能更新A却返回B并假报成功。 */
    @Test
    void rejectsSimultaneousPrimaryAndEvidenceConflicts() {
        var first = invalid(new byte[] {1}, "{}"); var second = invalid(new byte[] {2}, "{}");
        var firstId = append(first); var secondId = append(second);
        var conflict = new Letter(firstId, second.group(), second.body(), second.metadataJson(),
                second.delivery(), second.category(), second.failureCount());
        assertThrows(IllegalStateException.class, () -> append(conflict));
        for (var id : List.of(firstId, secondId)) {
            assertEquals(1L, jdbc.queryForObject("SELECT seen_count FROM outbox_dead_letter WHERE dead_id = ?", Long.class, id.toString()));
        }
        assertArrayEquals(first.body(), jdbc.queryForObject("SELECT body FROM outbox_dead_letter WHERE dead_id = ?", byte[].class, firstId.toString()));
        assertArrayEquals(second.body(), jdbc.queryForObject("SELECT body FROM outbox_dead_letter WHERE dead_id = ?", byte[].class, secondId.toString()));
    }

    /** 原体防御复制和值相等正确，上限和计数饱和不产生溢出。 */
    @Test
    void boundsEvidenceAndSaturatesCounters() {
        byte[] raw = {1}; var letter = invalid(raw, "{}"); raw[0] = 2;
        byte[] returned = letter.body(); returned[0] = 3; assertArrayEquals(new byte[] {1}, letter.body());
        assertEquals(letter, new Letter(letter.id(), letter.group(), new byte[] {1}, "{}", Optional.empty(), letter.category(), 0));
        assertFalse(letter.toString().contains("metadataJson"));
        assertThrows(IllegalArgumentException.class, () -> invalid(new byte[Letter.MAX_BODY_BYTES + 1], "{}"));
        assertThrows(IllegalArgumentException.class, () -> invalid(new byte[0], "x".repeat(Letter.MAX_METADATA_BYTES + 1)));
        var maximum = invalid(new byte[Letter.MAX_BODY_BYTES], "{}"); var maximumId = append(maximum);
        assertEquals(Letter.MAX_BODY_BYTES, jdbc.queryForObject("SELECT OCTET_LENGTH(body) FROM outbox_dead_letter WHERE dead_id = ?",
                Integer.class, maximumId.toString()));
        var id = append(letter);
        jdbc.update("UPDATE outbox_dead_letter SET seen_count = ?, failure_count = ? WHERE dead_id = ?", Long.MAX_VALUE, Long.MAX_VALUE, id.toString());
        append(letter);
        assertEquals(Long.MAX_VALUE, jdbc.queryForObject("SELECT seen_count FROM outbox_dead_letter WHERE dead_id = ?", Long.class, id.toString()));
        assertEquals(Long.MAX_VALUE, jdbc.queryForObject("SELECT failure_count FROM outbox_dead_letter WHERE dead_id = ?", Long.class, id.toString()));
        var validId = append(valid(delivery(), "{}", 6));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE outbox_dead_letter SET event_sequence = NULL WHERE dead_id = ?", validId.toString()));
    }

    /** 两个实例并发归档相同证据，唯一索引在首事务提交后返回同一个ID。 */
    @Test
    void deduplicatesAcrossIndependentConnections() throws Exception {
        var letter = valid(delivery(), "{}", 6); var duplicate = valid(letter.delivery().orElseThrow(), "{}", 6);
        var second = new OutboxDeadLetterRepositoryImpl(jdbc, json); var ready = new CountDownLatch(1); var release = new CountDownLatch(1);
        var competingStarted = new CountDownLatch(1); var ownerConnection = new AtomicLong(); var competingConnection = new AtomicLong();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var owner = executor.submit(() -> unitOfWork.execute(() -> {
                ownerConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                var id = deadLetters.append(letter); ready.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                return id;
            }));
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                var competing = executor.submit(() -> unitOfWork.execute(() -> {
                    competingConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)); competingStarted.countDown();
                    return second.append(duplicate);
                }));
                assertTrue(competingStarted.await(5, TimeUnit.SECONDS));
                assertNotEquals(ownerConnection.get(), competingConnection.get());
                assertThrows(TimeoutException.class, () -> competing.get(100, TimeUnit.MILLISECONDS));
                release.countDown(); var firstId = owner.get(5, TimeUnit.SECONDS); ids.add(firstId);
                assertEquals(firstId, competing.get(5, TimeUnit.SECONDS));
                assertEquals(2L, jdbc.queryForObject("SELECT seen_count FROM outbox_dead_letter WHERE dead_id = ?", Long.class, firstId.toString()));
            } finally { release.countDown(); }
        }
    }

    /**
     * 在真实应用工作单元登记并跟踪归档ID。
     * @param letter 原证据
     * @return 稳定归档ID
     */
    private UUID append(Letter letter) { var id = unitOfWork.execute(() -> deadLetters.append(letter)); ids.add(id); return id; }

    /**
     * 构造可解析但仍需后续重放核验的证据。
     * @param delivery 原身份声明
     * @param metadata 元数据快照
     * @param failures 非负计数
     * @return 有界原证据
     */
    private Letter valid(Delivery delivery, String metadata, long failures) {
        byte[] body = json.writeValueAsBytes(java.util.Map.of("eventId", delivery.message().eventId(), "eventType", delivery.message().eventType(),
                "aggregateId", delivery.message().aggregateId(), "occurredAt", delivery.message().occurredAt().toString(),
                "traceId", delivery.message().traceId(), "sequence", delivery.sequence(), "payload",
                json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(delivery.message().payload())));
        return new Letter(UUID.randomUUID(), Group.ERP_AUDIT, body, metadata, Optional.of(delivery), Category.RETRIES_EXHAUSTED, failures);
    }

    /**
     * 构造不能直接重放的隔离证据。
     * @param body 任意原体
     * @param metadata 有界元数据JSON
     * @return 隔离归档值
     */
    private Letter invalid(byte[] body, String metadata) { return new Letter(UUID.randomUUID(), Group.ERP_AUDIT, body, metadata, Optional.empty(), Category.INVALID_ENVELOPE, 0); }

    /**
     * 构造独立的身份声明，不自动创建本地业务或Outbox。
     * @return 原始事件值
     */
    private Delivery delivery() {
        return new Delivery(new OutboxMessage(UUID.randomUUID().toString(), "erp.probe.created", UUID.randomUUID().toString(),
                Instant.parse("2026-10-26T10:00:00.123456Z"), "{\"amount\":999999999999999999.123456789012}", "dead-letter-it"), 1);
    }

    /**
     * 独立查询某归档ID是否已提交。
     * @param id 归档UUID
     * @return 行数
     */
    private int count(UUID id) { return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_dead_letter WHERE dead_id = ?", Integer.class, id.toString()); }
}
