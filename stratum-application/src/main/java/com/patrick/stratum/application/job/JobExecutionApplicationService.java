package com.patrick.stratum.application.job;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.trace.CorrelationIdPolicy;
import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.domain.job.JobExecutionRepository;
import com.patrick.stratum.domain.job.JobExecutionRepository.Lease;
import com.patrick.stratum.domain.job.JobExecutionRepository.Submission;
import com.patrick.stratum.domain.job.JobRecoveryRepository;
import com.patrick.stratum.domain.job.JobRecoveryRepository.Outcome;
import com.patrick.stratum.domain.job.JobRecoveryRepository.ReplayRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 为任务技术状态提供明确提交边界；不执行处理器、创建线程或调度Quartz。
 * <p>示例：事务外调用register(input)，随后claim(id)，执行副作用后再finish(lease, outcome)。</p>
 */
@Component
@RequiredArgsConstructor
public class JobExecutionApplicationService {
    private final UnitOfWork unitOfWork;
    private final JobExecutionRepository executions;
    private final JobRecoveryRepository recovery;

    /**
     * 原子登记不可变输入，返回时事务已提交。
     * @param submission 完整登记输入
     * @return 首次登记的执行UUID
     * @throws IllegalStateException 调用者仍处于外层事务
     * @apiNote 示例：UUID id = jobs.register(submission)。
     */
    public UUID register(Submission submission) {
        Objects.requireNonNull(submission, "任务登记不能为空");
        return transact(submission.traceId(), () -> executions.register(submission));
    }

    /**
     * 查询有限技术候选，保持仓储的事务契约；结果不能代替实际领取。
     * @param jobCode 固定任务代码
     * @param limit 1至128个候选
     * @return 不可变执行UUID列表
     * @throws IllegalStateException 调用者仍处于外层事务
     * @apiNote 示例：jobs.due("outbox.relay", 16)。
     */
    public List<UUID> due(String jobCode, int limit) {
        return transact(currentTrace(), () -> List.copyOf(executions.due(jobCode, limit)));
    }

    /**
     * 在短工作单元内竞争领取，返回后才允许在事务外执行处理器。
     * @param executionId 原执行UUID
     * @return 已提交的领取或空结果
     * @throws IllegalStateException 调用者仍处于外层事务
     * @apiNote 示例：jobs.claim(executionId).ifPresent(worker::execute)。
     */
    public Optional<Lease> claim(UUID executionId) {
        Objects.requireNonNull(executionId, "任务执行身份不能为空");
        return transact(currentTrace(), () -> executions.claim(executionId));
    }

    /**
     * 在独立工作单元完成原领取，真实租期和身份仍由存储核验。
     * @param lease 原领取证据
     * @param outcome 实际固定结果分类
     * @return 已提交结果为true，忙或失效为false
     * @throws IllegalStateException 调用者仍处于外层事务
     * @apiNote 示例：jobs.finish(lease, Outcome.SUCCEEDED)。
     */
    public boolean finish(Lease lease, Outcome outcome) {
        Objects.requireNonNull(lease, "任务领取不能为空");
        Objects.requireNonNull(outcome, "任务结果不能为空");
        return transact(lease.submission().traceId(), () -> recovery.finish(lease, outcome));
    }

    /**
     * 原子登记人工恢复，返回执行身份不表示处理器已完成。
     * @param request 当前死亡目标与独立请求UUID
     * @return 原执行UUID
     * @throws IllegalStateException 调用者仍处于外层事务
     * @apiNote 示例：jobs.replay(new ReplayRequest(UUID.randomUUID(), deadId, traceId))。
     */
    public UUID replay(ReplayRequest request) {
        Objects.requireNonNull(request, "任务恢复请求不能为空");
        return transact(request.traceId(), () -> recovery.replay(request));
    }

    /**
     * 拒绝外层事务，仓储结果校验发生在提交前，所有路径恢复调用线程上下文。
     * @param <T> 非空结果类型
     * @param trace 安全操作关联标识
     * @param operation 仅执行技术存储的工作单元操作
     * @return 已提交的非空结果
     * @throws IllegalStateException 调用者仍处于事务
     * @throws NullPointerException 仓储违反非空返回契约，当前工作单元回滚
     */
    private <T> T transact(String trace, Supplier<T> operation) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("任务状态入口必须从事务外调用，以保证返回前提交");
        }
        var previous = TraceIdContext.getTraceId();
        TraceIdContext.setTraceId(CorrelationIdPolicy.resolve(trace));
        try {
            return unitOfWork.execute(() -> Objects.requireNonNull(operation.get(), "任务存储结果不能为空"));
        } finally {
            if (previous.isPresent()) TraceIdContext.setTraceId(previous.orElseThrow());
            else TraceIdContext.clear();
        }
    }

    /**
     * 为尚未读取执行记录的候选/领取操作取得安全调用关联值，不修改原上下文。
     * @return 合法调用trace或新UUID
     */
    private String currentTrace() {
        return CorrelationIdPolicy.resolve(TraceIdContext.getTraceId().orElse(null));
    }
}
