package com.patrick.stratum.starter.integration;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.patrick.stratum.application.event.OutboxDeadLetterApplicationService;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group;
import com.patrick.stratum.domain.event.OutboxDeadLetterCollector;
import com.patrick.stratum.domain.event.OutboxDeadLetterRepository;
import com.patrick.stratum.domain.event.OutboxDeadLetterRepository.Category;
import com.patrick.stratum.domain.event.OutboxDeadLetterRepository.Letter;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxPublisher.Delivery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 用实际应用事务验证归档返回时机、回滚、上下文与安全日志，不模拟Broker。 */
@ExtendWith(OutputCaptureExtension.class)
class OutboxDeadLetterApplicationIntegrationTest extends MariaDbIntegrationTest {
    @Autowired private UnitOfWork unitOfWork;
    @Autowired private OutboxDeadLetterRepository repository;
    @Autowired private OutboxDeadLetterCollector collector;
    @Autowired private JdbcTemplate jdbc;
    private final List<UUID> ids = new ArrayList<>();

    /** 删除自身归档并清理线程trace，不影响原有业务事件。 */
    @AfterEach
    void cleanup() {
        TraceIdContext.clear();
        for (var id : ids) jdbc.update("DELETE FROM outbox_dead_letter WHERE dead_id = ?", id.toString());
    }

    /** 返回后独立连接可读取已提交归档，安全原trace贯穿工作单元并恢复调用者上下文。 */
    @Test
    void returnsOnlyAfterCommitAndRestoresTrace() throws Exception {
        var letter = letter(true, "archive-trace"); ids.add(letter.id());
        TraceIdContext.setTraceId("caller");
        var service = new OutboxDeadLetterApplicationService(unitOfWork, value -> {
            assertEquals("archive-trace", TraceIdContext.getRequiredTraceId()); return repository.append(value);
        });
        assertEquals(letter.id(), service.archive(letter));
        try (var connection = jdbc.getDataSource().getConnection();
                var statement = connection.prepareStatement("SELECT COUNT(*) FROM outbox_dead_letter WHERE dead_id = ?")) {
            statement.setString(1, letter.id().toString());
            try (var rows = statement.executeQuery()) { assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); }
        }
        assertEquals("caller", TraceIdContext.getRequiredTraceId());
    }

    /** 重复应用调用返回同一归档ID，未知身份声明不会自动产生本地Outbox。 */
    @Test
    void returnsStableDuplicateIdentityWithoutCreatingBusinessState() {
        var first = letter(true, "trace"); var firstId = collector.archive(first); ids.add(firstId);
        var duplicate = new Letter(UUID.randomUUID(), first.group(), first.body(), first.metadataJson(), first.delivery(), first.category(), first.failureCount());
        assertEquals(firstId, collector.archive(duplicate));
        assertEquals(2L, jdbc.queryForObject("SELECT seen_count FROM outbox_dead_letter WHERE dead_id = ?", Long.class, firstId.toString()));
        var event = first.delivery().orElseThrow().message();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_id = ?", Integer.class, event.eventId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_aggregate_progress WHERE aggregate_id = ?", Integer.class, event.aggregateId()));
    }

    /** 仓储写入后失败必须整事务回滚，不返回假成功，trace也不能残留。 */
    @Test
    void rollsBackWrittenEvidenceOnFailure() {
        var letter = letter(false, "ignored"); ids.add(letter.id()); TraceIdContext.setTraceId("caller");
        var service = new OutboxDeadLetterApplicationService(unitOfWork, value -> {
            repository.append(value); throw new IllegalStateException("受控写入后故障");
        });
        assertThrows(IllegalStateException.class, () -> service.archive(letter));
        assertEquals(0, count(letter.id())); assertEquals("caller", TraceIdContext.getRequiredTraceId());
    }

    /** 端口违反非空返回契约时，同样不能提交归档后误报成功。 */
    @Test
    void rollsBackIfRepositoryReturnsNoIdentity() {
        var letter = letter(false, "ignored"); ids.add(letter.id());
        var service = new OutboxDeadLetterApplicationService(unitOfWork, value -> { repository.append(value); return null; });
        assertThrows(NullPointerException.class, () -> service.archive(letter));
        assertEquals(0, count(letter.id())); assertTrue(TraceIdContext.getTraceId().isEmpty());
    }

    /** 外层事务调用在归档前拒绝，避免调用者在提交前ack原消息。 */
    @Test
    void refusesOuterTransactionBeforePersistingEvidence() {
        var letter = letter(false, "ignored"); ids.add(letter.id());
        assertThrows(IllegalStateException.class, () -> unitOfWork.execute(() -> collector.archive(letter)));
        assertEquals(0, count(letter.id()));
    }

    /** 非法信封原体/元数据和非法trace原文都不会进入成功日志。 */
    @Test
    void archivesQuarantineWithoutLoggingUntrustedContent(CapturedOutput output) {
        var invalid = letter(false, "ignored"); var id = collector.archive(invalid); ids.add(id);
        assertEquals("QUARANTINED", jdbc.queryForObject("SELECT status FROM outbox_dead_letter WHERE dead_id = ?", String.class, id.toString()));
        var unsafeTrace = "UNSAFE_TRACE\nINJECT"; var parsed = letter(true, unsafeTrace);
        ids.add(collector.archive(parsed));
        assertFalse(output.getAll().contains("ARCHIVE_SECRET_BODY"));
        assertFalse(output.getAll().contains("ARCHIVE_SECRET_METADATA"));
        assertFalse(output.getAll().contains("UNSAFE_TRACE"));
        assertTrue(output.getAll().contains("archiveId=" + id));
        assertTrue(TraceIdContext.getTraceId().isEmpty());
    }

    /**
     * 创建不自动登记本地原事件的有界证据。
     * @param parsed 是否包含可解析身份声明
     * @param trace 身份声明中的trace，不用它授权重放
     * @return 隔离证据或待核验死信
     */
    private Letter letter(boolean parsed, String trace) {
        Optional<Delivery> delivery = parsed ? Optional.of(new Delivery(new OutboxMessage(UUID.randomUUID().toString(),
                "erp.probe.created", UUID.randomUUID().toString(), Instant.parse("2026-10-26T10:00:00Z"), "{}", trace), 1)) : Optional.empty();
        return new Letter(UUID.randomUUID(), Group.ERP_AUDIT, "ARCHIVE_SECRET_BODY".getBytes(StandardCharsets.UTF_8),
                "{\"metadata\":\"ARCHIVE_SECRET_METADATA\"}", delivery,
                parsed ? Category.RETRIES_EXHAUSTED : Category.INVALID_ENVELOPE, parsed ? 6 : 0);
    }

    /**
     * 独立读取已提交归档数量。
     * @param id 本测试归档ID
     * @return 行数
     */
    private int count(UUID id) { return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_dead_letter WHERE dead_id = ?", Integer.class, id.toString()); }
}
