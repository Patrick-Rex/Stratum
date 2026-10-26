package com.patrick.stratum.infrastructure.outbox;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.event.OutboxConsumptionRepository;
import com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxPublisher.Delivery;
import com.patrick.stratum.domain.event.OutboxReplayRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 人工请求、独立Outbox重放意图及归档状态原子登记，不调用MQ或重写原事件。 */
@Repository
@RequiredArgsConstructor
public class OutboxReplayRepositoryImpl implements OutboxReplayRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final OutboxConsumptionRepository consumption;

    /** {@inheritDoc} */
    @Override
    public Receipt enqueue(Request request) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("重放存储必须参与已有应用写工作单元");
        }
        Objects.requireNonNull(request);
        try {
            var known = registration(request.requestId(), false);
            if (known != null) return receipt(request, known);
            var initial = archive(request.archiveId(), false);
            if (initial == null) throw new StratumException(CommonErrorCode.NOT_FOUND, "死信归档不存在");
            if (initial.delivery() == null || initial.status().equals("QUARANTINED")) throw invalid("隔离死信不能重放");
            var eligibility = consumption.begin(initial.delivery(), initial.group());
            if (eligibility == OutboxConsumptionRepository.Result.WAITING) throw conflict("原事件尚非可恢复的聚合头");
            UUID token = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO outbox_replay_request (request_id, dead_id, registration_token, outcome, trace_id)
                    VALUES (?, ?, ?, 'REGISTERING', ?)
                    ON DUPLICATE KEY UPDATE request_id = VALUES(request_id)
                    """, request.requestId().toString(), request.archiveId().toString(), token.toString(), request.traceId());
            var current = registration(request.requestId(), true);
            if (current == null) throw conflict("重放请求登记失败");
            if (!current.archiveId().equals(request.archiveId())) throw conflict("重放请求标识已用于其他归档");
            if (!current.outcome().equals("REGISTERING")) return receipt(request, current);
            if (!current.token().equals(token)) throw conflict("重放请求登记尚未完成");
            var archive = archive(request.archiveId(), true);
            if (archive == null) throw conflict("死信归档状态正在变更");
            if (!Objects.equals(initial.delivery(), archive.delivery()) || initial.group() != archive.group()) {
                throw conflict("归档身份已变更");
            }
            verifyBody(archive);
            if (eligibility == OutboxConsumptionRepository.Result.DUPLICATE) {
                jdbc.update("UPDATE outbox_dead_letter SET status = 'RESOLVED' WHERE dead_id = ?", request.archiveId().toString());
                jdbc.update("""
                        UPDATE outbox_replay_delivery SET status = 'RESOLVED', resolved_at = UTC_TIMESTAMP(6),
                            lease_token = NULL, lease_until = NULL, version = version + 1
                        WHERE dead_id = ? AND status <> 'RESOLVED'
                        """, request.archiveId().toString());
                return complete(request, null, Outcome.ALREADY_CONSUMED, 0);
            }
            String originalStatus = jdbc.queryForObject("SELECT status FROM outbox_event WHERE event_id = ? FOR UPDATE",
                    String.class, archive.delivery().message().eventId());
            if (!("SENT".equals(originalStatus) || "FAILED".equals(originalStatus))) throw conflict("原投递尚未结束");
            if (!(archive.status().equals("DEAD") || archive.status().equals("REPLAY_REQUESTED"))) throw conflict("归档当前不可重放");
            return enqueueDelivery(request, archive);
        } catch (DataAccessException failure) { throw translate(failure); }
        catch (IllegalArgumentException failure) { throw invalid("归档身份或原事件核验失败"); }
    }

    /**
     * 每份归档保持一个独立意图；同事件只容许一个待发送或领取中的意图。
     * @param request 当前请求
     * @param archive 已锁定且核验的证据
     * @return 稳定结果
     */
    private Receipt enqueueDelivery(Request request, Archive archive) {
        var intents = jdbc.query("""
                SELECT delivery_id, status, retry_count FROM outbox_replay_delivery WHERE dead_id = ? FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new Intent(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getInt(3)), request.archiveId().toString());
        UUID deliveryId; int previousFailures = 0; Outcome outcome;
        if (intents.isEmpty()) {
            Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_replay_delivery WHERE dead_id = ?", Integer.class, request.archiveId().toString());
            if (existing != null && existing > 0) throw conflict("重放意图正在变更");
            deliveryId = request.requestId(); outcome = Outcome.QUEUED;
            var delivery = archive.delivery();
            jdbc.update("""
                    INSERT INTO outbox_replay_delivery (delivery_id, dead_id, event_id, aggregate_id, event_sequence, consumer_group)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, deliveryId.toString(), request.archiveId().toString(), delivery.message().eventId(),
                    delivery.message().aggregateId(), delivery.sequence(), archive.group().name());
        } else {
            var intent = intents.getFirst(); deliveryId = intent.id();
            if (intent.status().equals("RESOLVED")) throw conflict("已恢复意图与消费凭据不一致");
            if (intent.status().equals("FAILED")) {
                previousFailures = intent.failures(); outcome = Outcome.QUEUED;
                jdbc.update("""
                        UPDATE outbox_replay_delivery SET status = 'PENDING', retry_count = 0, next_retry_at = UTC_TIMESTAMP(6),
                            lease_token = NULL, lease_until = NULL, version = version + 1 WHERE delivery_id = ?
                        """, deliveryId.toString());
            } else outcome = Outcome.ALREADY_QUEUED;
        }
        jdbc.update("UPDATE outbox_dead_letter SET status = 'REPLAY_REQUESTED' WHERE dead_id = ?", request.archiveId().toString());
        return complete(request, deliveryId, outcome, previousFailures);
    }

    /**
     * 结束同事务请求登记，保留首次结果及人工重试前的失败计数。
     * @param request 当前请求
     * @param deliveryId 可空独立意图
     * @param outcome 最终请求结果
     * @param previousFailures 原意图失败计数
     * @return 稳定回执
     */
    private Receipt complete(Request request, UUID deliveryId, Outcome outcome, int previousFailures) {
        int changed = jdbc.update("""
                UPDATE outbox_replay_request SET delivery_id = ?, outcome = ?, previous_failures = ?
                WHERE request_id = ? AND outcome = 'REGISTERING'
                """, deliveryId == null ? null : deliveryId.toString(), outcome.name(), previousFailures, request.requestId().toString());
        if (changed != 1) throw conflict("重放请求结果未完成");
        return new Receipt(request.requestId(), request.archiveId(), Optional.ofNullable(deliveryId), outcome);
    }

    /**
     * 读取单一请求的历史结果；新请求不提前持有不存在主键的间隙锁。
     * @param id 请求UUID
     * @param locked 是否当前读
     * @return 历史登记，不存在时为空
     */
    private Registration registration(UUID id, boolean locked) {
        var rows = jdbc.query("SELECT dead_id, registration_token, delivery_id, outcome FROM outbox_replay_request WHERE request_id = ?"
                        + (locked ? " FOR UPDATE" : ""),
                (rs, row) -> new Registration(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)),
                        rs.getString(3) == null ? null : UUID.fromString(rs.getString(3)), rs.getString(4)), id.toString());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * 将历史登记恢复为稳定回执，拒绝相同请求ID改变归档目标。
     * @param request 当前输入
     * @param registration 历史登记
     * @return 首次结果
     */
    private static Receipt receipt(Request request, Registration registration) {
        if (!request.archiveId().equals(registration.archiveId())) throw conflict("重放请求标识已用于其他归档");
        if (registration.outcome().equals("REGISTERING")) throw conflict("重放请求尚未完成");
        return new Receipt(request.requestId(), request.archiveId(), Optional.ofNullable(registration.deliveryId()), Outcome.valueOf(registration.outcome()));
    }

    /**
     * 在获得聚合锁后才锁定归档，保持与消费完成一致的锁顺序。
     * @param id 归档UUID
     * @param locked 是否跳过忙行的当前读
     * @return 原始证据快照或空
     */
    private Archive archive(UUID id, boolean locked) {
        var rows = jdbc.query("""
                SELECT status, consumer_group, event_id, event_type, aggregate_id, occurred_at, payload, trace_id, event_sequence, body
                FROM outbox_dead_letter WHERE dead_id = ?
                """ + (locked ? " FOR UPDATE SKIP LOCKED" : ""), (rs, row) -> {
            Delivery delivery = rs.getString(3) == null ? null : new Delivery(new OutboxMessage(rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getObject(6, LocalDateTime.class).toInstant(ZoneOffset.UTC), rs.getString(7), rs.getString(8)), rs.getLong(9));
            return new Archive(rs.getString(1), Group.valueOf(rs.getString(2)), delivery, rs.getBytes(10));
        }, id.toString());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * 重放前独立核验完整原体，不把归档解析声明直接当作发送授权。
     * @param archive 已持锁的原证据
     * @throws StratumException 原体格式或身份不符
     */
    private void verifyBody(Archive archive) {
        try {
            if (archive.body().length == 0 || archive.body().length > 512 * 1024) throw invalid("归档原体超出原事件契约");
            String raw = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(archive.body())).toString();
            var reader = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
            JsonNode root = reader.readTree(raw); var expected = archive.delivery().message();
            var sequence = root.get("sequence");
            if (!root.isObject() || root.size() != 7 || root.get("payload") == null
                    || sequence == null || !sequence.isIntegralNumber() || !sequence.canConvertToLong()
                    || sequence.longValue() != archive.delivery().sequence()
                    || !expected.eventId().equals(text(root, "eventId")) || !expected.eventType().equals(text(root, "eventType"))
                    || !expected.aggregateId().equals(text(root, "aggregateId")) || !expected.traceId().equals(text(root, "traceId"))
                    || !expected.occurredAt().equals(Instant.parse(text(root, "occurredAt")))
                    || !reader.readTree(expected.payload()).equals(root.get("payload"))) throw invalid("归档原体与身份声明不一致");
        } catch (RuntimeException | CharacterCodingException failure) {
            throw invalid("归档原体不是合法原事件");
        }
    }

    /**
     * 保持文本字段严格类型。
     * @param root 根对象
     * @param field 固定字段
     * @return 原文本
     */
    private static String text(JsonNode root, String field) {
        var value = root.get(field);
        if (value == null || !value.isTextual()) throw invalid("归档字段不是文本");
        return value.asString();
    }

    /**
     * 创建不含原载荷的参数错误。
     * @param message 固定安全说明
     * @return 40000错误
     */
    private static StratumException invalid(String message) { return new StratumException(CommonErrorCode.BAD_REQUEST, message); }

    /**
     * 创建可重试的状态冲突。
     * @param message 固定安全说明
     * @return 40900错误
     */
    private static StratumException conflict(String message) { return new StratumException(CommonErrorCode.CONFLICT, message); }

    /**
     * 并发唯一键、快照或串行化冲突要求回滚整个工作单元，不在存储内重试。
     * @param failure JDBC失败
     * @return 统一冲突或原异常
     */
    private static RuntimeException translate(DataAccessException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && (sql.getErrorCode() == 1062 || sql.getErrorCode() == 1020 || "40001".equals(sql.getSQLState()))) {
                return new StratumException(CommonErrorCode.CONFLICT, List.of("重放登记状态已变更"), failure);
            }
        }
        return failure;
    }

    /**
     * 私有归档快照，原体不输出到日志。
     * @param status 归档状态
     * @param group 固定组
     * @param delivery 可空身份声明
     * @param body 有界原体
     */
    private record Archive(String status, Group group, Delivery delivery, byte[] body) { }

    /**
     * 当前请求登记快照，不包含正文。
     * @param archiveId 原目标
     * @param token 登记竞争令牌
     * @param deliveryId 可空独立意图
     * @param outcome 已登记结果
     */
    private record Registration(UUID archiveId, UUID token, UUID deliveryId, String outcome) { }

    /**
     * 已锁定意图状态。
     * @param id 独立投递UUID
     * @param status 当前状态
     * @param failures 已记失败次数
     */
    private record Intent(UUID id, String status, int failures) { }
}
