package com.patrick.stratum.application.job;

import java.util.Objects;
import java.util.UUID;

import com.patrick.stratum.application.auth.CurrentIdentity;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.AdministrationPolicy;
import com.patrick.stratum.domain.auth.AuthorizationAdministrationRepository;
import com.patrick.stratum.domain.job.DeadLetterReplayJob;
import com.patrick.stratum.domain.job.JobCancellationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 由当前启用管理员取消尚未开始的死信重放任务，授权与状态在同一工作单元提交。
 * <p>示例：{@code cancellation.cancel(taskId)}；重复取消仍检查当前授权。</p>
 */
@Component
@RequiredArgsConstructor
public class DeadLetterReplayCancellationService {
    private final CurrentIdentity identity;
    private final AuthorizationAdministrationRepository administration;
    private final JobCancellationRepository cancellation;
    private final UnitOfWork work;

    /**
     * 重新核验管理员后幂等取消固定类型任务；已开始或其他类型不可取消。
     * @param taskId 死信重放任务执行UUID
     * @return 已提交的任务UUID
     * @throws NullPointerException 任务标识为空
     * @throws IllegalStateException 已在外层事务内
     * @throws StratumException 未认证、管理员资格失效、任务不存在或已开始
     * @apiNote 示例：{@code cancellation.cancel(taskId)}返回不代表业务消费。
     */
    public UUID cancel(UUID taskId) {
        Objects.requireNonNull(taskId, "任务标识不能为空");
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("任务取消必须从事务外调用");
        UUID actor = identity.require().userId();
        return work.execute(() -> {
            administration.lock();
            AdministrationPolicy.requireAdministrator(administration.isEnabledAdministrator(actor));
            var result = Objects.requireNonNull(cancellation.cancelUnstarted(taskId, DeadLetterReplayJob.CODE), "任务取消结果不能为空");
            return switch (result) {
                case CANCELLED -> taskId;
                case NOT_FOUND -> throw new StratumException(CommonErrorCode.NOT_FOUND, "重放任务不存在");
                case ALREADY_STARTED -> throw new StratumException(CommonErrorCode.CONFLICT, "任务已开始，不能取消");
            };
        });
    }
}
