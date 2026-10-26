package com.patrick.stratum.infrastructure.outbox;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.event.OutboxDeadLetterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

/** 归档原始死信及稳定去重指纹，参与应用已有工作单元，不改变Outbox或消费进度。 */
@Repository
@RequiredArgsConstructor
public class OutboxDeadLetterRepositoryImpl implements OutboxDeadLetterRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    /** {@inheritDoc} */
    @Override
    public UUID append(Letter letter) {
        requireWrite(); Objects.requireNonNull(letter);
        byte[] body = letter.body();
        String fingerprint = fingerprint(letter, body);
        var delivery = letter.delivery().orElse(null); var event = delivery == null ? null : delivery.message();
        try {
            jdbc.update("""
                    INSERT INTO outbox_dead_letter (dead_id, consumer_group, fingerprint, body, metadata_json,
                        event_id, aggregate_id, event_sequence, event_type, occurred_at, payload, trace_id,
                        category, status, failure_count)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE failure_count = GREATEST(failure_count, VALUES(failure_count)),
                        seen_count = CASE WHEN seen_count < 9223372036854775807 THEN seen_count + 1 ELSE seen_count END,
                        last_seen_at = UTC_TIMESTAMP(6)
                    """, letter.id().toString(), letter.group().name(), fingerprint, body, letter.metadataJson(),
                    event == null ? null : event.eventId(), event == null ? null : event.aggregateId(),
                    delivery == null ? null : delivery.sequence(), event == null ? null : event.eventType(),
                    event == null ? null : LocalDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC),
                    event == null ? null : event.payload(), event == null ? null : event.traceId(), letter.category().name(),
                    delivery == null ? "QUARANTINED" : "DEAD", letter.failureCount());
            var conflicts = jdbc.query("""
                    SELECT dead_id FROM outbox_dead_letter
                    WHERE dead_id = ? AND (consumer_group <> ? OR fingerprint <> ?) FOR UPDATE
                    """, (rs, row) -> rs.getString(1), letter.id().toString(), letter.group().name(), fingerprint);
            if (!conflicts.isEmpty()) throw new IllegalStateException("死信归档标识冲突");
            var ids = jdbc.query("""
                    SELECT dead_id FROM outbox_dead_letter WHERE consumer_group = ? AND fingerprint = ? FOR UPDATE
                    """, (rs, row) -> UUID.fromString(rs.getString(1)), letter.group().name(), fingerprint);
            if (ids.size() != 1) throw new IllegalStateException("死信归档标识冲突");
            return ids.getFirst();
        } catch (DataAccessException failure) { throw translate(failure); }
    }

    /**
     * 对严格JSON元数据排序后计算稳定指纹；长度前缀区分任意原始字节的字段边界。
     * @param letter 原证据
     * @param body 已防御复制的原体
     * @return SHA-256十六进制指纹
     * @throws IllegalArgumentException 元数据不是严格对象
     */
    private String fingerprint(Letter letter, byte[] body) {
        byte[] metadata;
        try {
            var tree = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .readTree(letter.metadataJson());
            if (!tree.isObject()) throw new IllegalArgumentException("死信元数据必须是JSON对象");
            metadata = json.writer().with(JsonNodeFeature.WRITE_PROPERTIES_SORTED).writeValueAsBytes(tree);
        } catch (tools.jackson.core.JacksonException invalid) {
            throw new IllegalArgumentException("死信元数据不是严格JSON对象");
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            update(digest, letter.group().name().getBytes(StandardCharsets.UTF_8));
            update(digest, letter.category().name().getBytes(StandardCharsets.UTF_8));
            update(digest, body); update(digest, metadata);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("JDK缺少标准SHA-256算法");
        }
    }

    /**
     * 追加长度与内容，避免二进制字段边界歧义。
     * @param digest 本次摘要器
     * @param value 有界字段
     */
    private static void update(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array()); digest.update(value);
    }

    /**
     * 拒绝无事务及只读调用。
     * @throws IllegalStateException 没有可写工作单元
     */
    private static void requireWrite() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("死信存储必须参与已有应用写工作单元");
        }
    }

    /**
     * 沿用快照/串行化冲突口径，不在旧事务内重试。
     * @param failure JDBC失败
     * @return 40900冲突或原异常
     */
    private static RuntimeException translate(DataAccessException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && (sql.getErrorCode() == 1020 || "40001".equals(sql.getSQLState()))) {
                return new StratumException(CommonErrorCode.CONFLICT, List.of("死信归档状态已变更"), failure);
            }
        }
        return failure;
    }
}
