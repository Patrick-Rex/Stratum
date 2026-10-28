package com.patrick.stratum.query.job;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.core.exception.EasyQueryException;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.event.OutboxReplayRepository;
import com.patrick.stratum.domain.job.DeadLetterReplayJob;
import com.patrick.stratum.domain.job.DeadLetterReplayTaskStatus;
import com.patrick.stratum.domain.job.JobExecutionRepository;
import com.patrick.stratum.query.QueryContext;
import com.patrick.stratum.query.QueryHandler;
import com.patrick.stratum.query.auth.AdministrationQueryAccess;
import com.patrick.stratum.query.dto.QueryResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 当前管理员读取单一重放任务的安全状态；所有业务与消费事实来自同一SQL快照。
 * <p>示例：由QueryGateway路由{@code DeadLetterReplayStatusQuery}。</p>
 */
@Component
@RequiredArgsConstructor
public class DeadLetterReplayStatusQueryHandler implements QueryHandler<DeadLetterReplayStatusQuery, DeadLetterReplayStatusQuery.Row> {
    private final EasyEntityQuery database;
    private final AdministrationQueryAccess access;
    private static final String SNAPSHOT = """
            SELECT t.task_id, j.state, j.attempt_no, j.initial_trace_id AS trace_id,
                   r.outcome AS replay_outcome, d.status AS delivery_state,
                   CASE WHEN r.request_id IS NOT NULL AND r.dead_id=t.archive_id AND a.dead_id IS NOT NULL
                          AND a.category='RETRIES_EXHAUSTED' AND e.event_id=a.event_id
                          AND BINARY a.aggregate_id=BINARY e.aggregate_id AND a.event_sequence=e.event_sequence
                          AND BINARY a.event_type=BINARY e.event_type AND a.occurred_at=e.occurred_at
                          AND BINARY a.trace_id=BINARY e.trace_id AND JSON_EQUALS(a.payload,e.payload)=1
                          AND ((r.outcome='ALREADY_CONSUMED' AND r.delivery_id IS NULL)
                            OR (r.outcome IN ('QUEUED','ALREADY_QUEUED') AND d.delivery_id IS NOT NULL
                              AND d.dead_id=t.archive_id AND d.event_id=e.event_id
                              AND BINARY d.aggregate_id=BINARY e.aggregate_id AND d.event_sequence=e.event_sequence
                              AND d.consumer_group=a.consumer_group))
                        THEN 1 ELSE 0 END AS target_matches,
                   CASE WHEN c.event_id IS NULL THEN 0
                        WHEN e.event_id IS NOT NULL AND p.aggregate_id IS NOT NULL
                          AND c.event_id=e.event_id AND c.consumer_group=a.consumer_group
                          AND BINARY c.aggregate_id=BINARY e.aggregate_id AND c.event_sequence=e.event_sequence
                          AND BINARY c.event_type=BINARY e.event_type AND c.occurred_at=e.occurred_at
                          AND BINARY c.trace_id=BINARY e.trace_id AND BINARY c.payload=BINARY e.payload
                          AND p.consumed_sequence>=e.event_sequence
                        THEN 1 ELSE 2 END AS consumption_proof
            FROM dead_letter_replay_task t
            JOIN job_execution j ON j.execution_id=t.task_id
            LEFT JOIN outbox_replay_request r ON r.request_id=t.task_id
            LEFT JOIN outbox_replay_delivery d ON d.delivery_id=r.delivery_id
            LEFT JOIN outbox_dead_letter a ON a.dead_id=t.archive_id
            LEFT JOIN outbox_event e ON e.event_id=a.event_id
            LEFT JOIN outbox_consumption c ON c.event_id=a.event_id AND c.consumer_group=a.consumer_group
            LEFT JOIN outbox_aggregate_progress p ON p.aggregate_id=e.aggregate_id
            WHERE t.task_id=? AND j.job_code=?
            """;

    /** {@inheritDoc} */
    @Override public String queryCode() { return "outbox.replay-task.status"; }

    /** {@inheritDoc} */
    @Override public Class<DeadLetterReplayStatusQuery> queryType() { return DeadLetterReplayStatusQuery.class; }

    /** {@inheritDoc} */
    @Override public QueryResult<DeadLetterReplayStatusQuery.Row> handle(DeadLetterReplayStatusQuery query, QueryContext context) {
        access.require(context);
        UUID taskId = (UUID) query.spec().getFilters().getFirst().value();
        try {
            var rows = database.queryable(SNAPSHOT, DeadLetterReplayEvidenceView.class, List.of(taskId.toString(), DeadLetterReplayJob.CODE))
                    .configure(options -> options.setPrintSQL(false)).limit(1).toList();
            if (rows.isEmpty()) return new QueryResult<>(1, 1, 0, List.of());
            var evidence = rows.getFirst();
            var outcome = Optional.ofNullable(evidence.getReplayOutcome()).map(OutboxReplayRepository.Outcome::valueOf);
            var delivery = Optional.ofNullable(evidence.getDeliveryState()).map(DeadLetterReplayTaskStatus.Delivery::valueOf);
            var consumption = switch (evidence.getConsumptionProof()) {
                case 0 -> DeadLetterReplayTaskStatus.Consumption.NONE;
                case 1 -> DeadLetterReplayTaskStatus.Consumption.VALID;
                case 2 -> DeadLetterReplayTaskStatus.Consumption.INVALID;
                default -> throw new IllegalStateException("消费证据编码不合法");
            };
            var result = DeadLetterReplayTaskStatus.evaluate(new DeadLetterReplayTaskStatus.Evidence(
                    JobExecutionRepository.State.valueOf(evidence.getState()), evidence.getAttemptNo(), outcome, delivery,
                    Integer.valueOf(1).equals(evidence.getTargetMatches()), consumption));
            var row = new DeadLetterReplayStatusQuery.Row(UUID.fromString(evidence.getTaskId()), result.status(), result.progress(),
                    result.lastError().map(Enum::name).orElse(null), evidence.getTraceId());
            return new QueryResult<>(1, 1, 1, List.of(row));
        } catch (EasyQueryException | IllegalArgumentException | IllegalStateException failure) {
            throw new StratumException(CommonErrorCode.INTERNAL_ERROR);
        }
    }
}
