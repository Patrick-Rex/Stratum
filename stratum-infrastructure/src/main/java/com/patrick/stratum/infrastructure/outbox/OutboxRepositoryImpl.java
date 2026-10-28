package com.patrick.stratum.infrastructure.outbox;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryException;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.common.trace.StandardTraceContext;
import com.patrick.stratum.infrastructure.tracing.OpenTelemetryTraceContext;
import com.patrick.stratum.domain.event.OutboxMessage;
import com.patrick.stratum.domain.event.OutboxRepository;
import com.patrick.stratum.domain.event.OutboxStatus;

/** Outbox 落盘适配器：共用 Spring 事务连接，唯一事件ID拒绝覆盖或重复登记，聚合序号与事件同事务提交。 */
@Repository
public class OutboxRepositoryImpl implements OutboxRepository {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxRepositoryImpl.class);
    private final EasyEntityQuery query;
    private final JdbcTemplate jdbc;
    private final StandardTraceContext standardTrace;

    /**
     * 使用实际数据库客户端与标准上下文组件。
     * @param query 已绑定工作单元的ORM客户端
     * @param jdbc 已绑定工作单元的JDBC客户端
     * @param standardTrace 标准上下文边界
     * @throws NullPointerException 必需依赖为空
     * @apiNote 示例：由Spring注入同一工作单元的数据访问客户端和标准上下文。
     */
    @Autowired
    public OutboxRepositoryImpl(EasyEntityQuery query, JdbcTemplate jdbc, StandardTraceContext standardTrace) {
        this.query = Objects.requireNonNull(query); this.jdbc = Objects.requireNonNull(jdbc);
        this.standardTrace = Objects.requireNonNull(standardTrace);
    }

    /**
     * 保留既有手动构造方式，使用无状态标准上下文适配器。
     * @param query 已绑定工作单元的ORM客户端
     * @param jdbc 已绑定工作单元的JDBC客户端
     * @throws NullPointerException 客户端为空
     * @apiNote 示例：{@code new OutboxRepositoryImpl(query, jdbc)}。
     */
    public OutboxRepositoryImpl(EasyEntityQuery query, JdbcTemplate jdbc) {
        this(query, jdbc, new OpenTelemetryTraceContext());
    }

    /** {@inheritDoc} */
    @Override
    public void append(OutboxMessage message) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Outbox 必须在 Application 工作单元内写入");
        }
        var now = LocalDateTime.now(ZoneOffset.UTC);
        var row = new OutboxRecord();
        row.setEventId(message.eventId());
        row.setEventType(message.eventType());
        row.setAggregateId(message.aggregateId());
        row.setOccurredAt(LocalDateTime.ofInstant(message.occurredAt(), ZoneOffset.UTC));
        row.setPayload(message.payload());
        row.setTraceId(message.traceId());
        captureStandardTrace(row);
        row.setStatus(OutboxStatus.PENDING.name());
        row.setRetryCount(0);
        row.setNextRetryAt(now);
        row.setCreatedAt(now);
        row.setVersion(0L);
        try {
            row.setEventSequence(allocateSequence(message.aggregateId()));
            query.insertable(row).executeRows();
        } catch (DataAccessException | EasyQueryException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && sql.getErrorCode() == 1062) {
                    throw new StratumException(CommonErrorCode.CONFLICT, List.of("事件已登记"), failure);
                }
                if (cause instanceof SQLException sql && (sql.getErrorCode() == 1020 || "40001".equals(sql.getSQLState()))) {
                    throw new StratumException(CommonErrorCode.CONFLICT, List.of("事件顺序状态已变更"), failure);
                }
            }
            throw failure;
        }
    }

    /**
     * 捕获规范标准上下文；观测组件异常只保留空上下文并记录安全异常类型。
     * @param row 同一事务内待写入的事件行
     */
    private void captureStandardTrace(OutboxRecord row) {
        try {
            standardTrace.capture().flatMap(value -> standardTrace.normalize(value.traceparent(), value.tracestate())).ifPresent(value -> {
                row.setStandardTraceparent(value.traceparent());
                row.setStandardTracestate(value.tracestate().isEmpty() ? null : value.tracestate());
            });
        } catch (RuntimeException failure) {
            row.setStandardTraceparent(null); row.setStandardTracestate(null);
            try { LOG.atWarn().addKeyValue("errorType", failure.getClass().getSimpleName()).log("标准追踪上下文捕获失败"); }
            catch (RuntimeException ignored) { /* 日志故障也不能中断业务写入。 */ }
        }
    }

    /**
     * 在已有写事务中锁定聚合进度并分配下一个序号，锁持续至工作单元结束。
     * @param aggregateId 原聚合标识，不参与SQL拼接
     * @return 当前事务唯一的正序号
     * @throws IllegalStateException 序号耗尽或进度数据异常
     */
    private long allocateSequence(String aggregateId) {
        jdbc.update("INSERT INTO outbox_aggregate_progress (aggregate_id) VALUES (?) "
                + "ON DUPLICATE KEY UPDATE aggregate_id = VALUES(aggregate_id)", aggregateId);
        int changed = jdbc.update("UPDATE outbox_aggregate_progress SET last_sequence = last_sequence + 1 "
                + "WHERE aggregate_id = ? AND last_sequence < ?", aggregateId, Long.MAX_VALUE);
        if (changed != 1) throw new IllegalStateException("Outbox聚合序号已经耗尽");
        Long sequence = jdbc.queryForObject("SELECT last_sequence FROM outbox_aggregate_progress "
                + "WHERE aggregate_id = ?", Long.class, aggregateId);
        if (sequence == null || sequence < 1) throw new IllegalStateException("Outbox聚合序号状态异常");
        return sequence;
    }
}
