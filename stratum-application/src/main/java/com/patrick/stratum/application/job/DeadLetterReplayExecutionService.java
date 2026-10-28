package com.patrick.stratum.application.job;

import java.util.Objects;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.domain.auth.AdministrationPolicy;
import com.patrick.stratum.domain.auth.AuthorizationAdministrationRepository;
import com.patrick.stratum.domain.event.OutboxReplayRepository;
import com.patrick.stratum.domain.job.DeadLetterReplayTaskRepository;
import com.patrick.stratum.domain.job.JobExecutionRepository.Lease;
import com.patrick.stratum.domain.job.JobLeaseAuthority;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 在单一工作单元核验原领取与当前管理员后登记死信重放，不直接发送消息。
 * <p>示例：由受保护的任务处理器调用{@code execution.execute(lease)}。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeadLetterReplayExecutionService {
    private final UnitOfWork work;
    private final AuthorizationAdministrationRepository administration;
    private final DeadLetterReplayTaskRepository tasks;
    private final JobLeaseAuthority authority;
    private final OutboxReplayRepository replay;

    /**
     * 重新核验首次绑定、当前管理员和原领取，在同一事务登记幂等重放意图。
     * @param lease 工作器实际领取的完整证据
     * @return 已提交的重放登记回执，不表示已发送或消费成功
     * @throws NullPointerException 领取或存储结果为空
     * @throws IllegalStateException 已存在外层事务
     * @throws InterruptedException 执行前或登记过程中检测到中断，事务回滚
     * @throws StratumException 未授权、绑定不符、租约失效或归档不可重放
     * @apiNote 示例：{@code execution.execute(lease)}，仅在任务工作器持有资格时调用。
     */
    public OutboxReplayRepository.Receipt execute(Lease lease) throws InterruptedException {
        Objects.requireNonNull(lease, "任务领取不能为空");
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("重放任务必须从事务外执行");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("重放任务执行前已中断");
        var previous = TraceIdContext.getTraceId();
        TraceIdContext.setTraceId(lease.submission().traceId());
        try {
            var receipt = work.execute(() -> {
                administration.lock();
                var binding = Objects.requireNonNull(tasks.lockBinding(lease.submission().executionId()), "任务绑定结果不能为空")
                        .orElseThrow(() -> new StratumException(CommonErrorCode.NOT_FOUND, "重放任务不存在"));
                if (!binding.matches(lease.submission())) throw new StratumException(CommonErrorCode.CONFLICT, "任务与首次绑定不一致");
                AdministrationPolicy.requireAdministrator(administration.isEnabledAdministrator(binding.operatorId()));
                requireCurrent(lease); checkInterrupted();
                var request = new OutboxReplayRepository.Request(binding.taskId(), binding.archiveId(), lease.submission().traceId());
                var result = Objects.requireNonNull(replay.enqueue(request), "重放登记结果不能为空");
                checkInterrupted(); requireCurrent(lease);
                return result;
            });
            log.atInfo().addKeyValue("taskId", lease.submission().executionId().toString())
                    .addKeyValue("archiveId", receipt.archiveId().toString()).addKeyValue("traceId", lease.submission().traceId())
                    .addKeyValue("outcome", receipt.outcome().name())
                    .log("重放任务登记完成 taskId={} archiveId={} traceId={} outcome={}", lease.submission().executionId(),
                            receipt.archiveId(), lease.submission().traceId(), receipt.outcome().name());
            return receipt;
        } catch (ExecutionInterrupted signal) {
            throw new InterruptedException("重放任务登记已中断");
        } finally {
            if (previous.isPresent()) TraceIdContext.setTraceId(previous.orElseThrow()); else TraceIdContext.clear();
        }
    }

    /**
     * 将无效或正被其他事务占用的领取统一作为竞争失败，事务不得提交副作用。
     * @param lease 原领取证据
     */
    private void requireCurrent(Lease lease) {
        if (!authority.lockCurrent(lease)) throw new StratumException(CommonErrorCode.CONFLICT, "任务领取资格已失效或忙");
    }

    /** 检查中断并通过运行时信号回滚已有工作单元。 */
    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new ExecutionInterrupted();
    }

    /** 仅用于跨越工作单元回滚边界的内部中断信号。 */
    private static final class ExecutionInterrupted extends RuntimeException { }
}
