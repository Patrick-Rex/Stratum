package com.patrick.stratum.application.job;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.patrick.stratum.application.auth.CurrentIdentity;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.trace.CorrelationIdPolicy;
import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.domain.auth.AdministrationPolicy;
import com.patrick.stratum.domain.auth.AuthorizationAdministrationRepository;
import com.patrick.stratum.domain.job.DeadLetterReplayJob;
import com.patrick.stratum.domain.job.DeadLetterReplayTaskRepository;
import com.patrick.stratum.domain.job.JobDefinition;
import com.patrick.stratum.domain.job.JobExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 当前启用管理员登记固定死信重放任务，授权与幂等绑定在同一工作单元提交。
 * <p>示例：{@code registration.register(requestId, archiveId)}只表示任务已登记。</p>
 */
@Component
@RequiredArgsConstructor
public class DeadLetterReplayRegistrationService {
    private static final JobDefinition.RetryPolicy RETRY = new JobDefinition.RetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(30));
    private final CurrentIdentity identity;
    private final AuthorizationAdministrationRepository administration;
    private final DeadLetterReplayTaskRepository tasks;
    private final UnitOfWork work;

    /**
     * 复核当前管理员并提交一个固定任务，同一请求身份复用首次结果。
     * @param requestId 客户端提供的幂等请求UUID
     * @param archiveId 待重放归档UUID，实际资格由重放处理时核验
     * @return 已提交的首次任务UUID，不表示发送或消费成功
     * @throws NullPointerException 请求或归档身份为空
     * @throws IllegalStateException 已在外层事务内
     * @throws com.patrick.stratum.common.error.StratumException 未认证、授权失效或请求身份冲突
     * @apiNote 示例：{@code registration.register(requestId, archiveId)}。
     */
    public UUID register(UUID requestId, UUID archiveId) {
        Objects.requireNonNull(requestId, "请求标识不能为空"); Objects.requireNonNull(archiveId, "归档标识不能为空");
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("任务登记必须从事务外调用");
        UUID actor = identity.require().userId();
        String trace = CorrelationIdPolicy.resolve(TraceIdContext.getTraceId().orElse(null));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var definition = new JobDefinition(DeadLetterReplayJob.CODE, JobDefinition.Type.ONCE, Optional.empty(), Optional.of(now),
                30, RETRY, JobDefinition.IdempotencyRule.EXPLICIT_KEY);
        var submission = new JobExecutionRepository.Submission(UUID.randomUUID(), definition, now, requestId.toString(),
                DeadLetterReplayJob.arguments(archiveId), trace);
        return work.execute(() -> {
            administration.lock();
            AdministrationPolicy.requireAdministrator(administration.isEnabledAdministrator(actor));
            return Objects.requireNonNull(tasks.register(new DeadLetterReplayTaskRepository.Request(requestId, archiveId, actor, submission)),
                    "重放任务登记结果不能为空");
        });
    }
}
