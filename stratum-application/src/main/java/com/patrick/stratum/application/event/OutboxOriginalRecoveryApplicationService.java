package com.patrick.stratum.application.event;

import java.util.Objects;
import java.util.UUID;

import com.patrick.stratum.application.auth.CurrentIdentity;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.common.trace.CorrelationIdPolicy;
import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.domain.auth.AdministrationPolicy;
import com.patrick.stratum.domain.auth.AuthorizationAdministrationRepository;
import com.patrick.stratum.domain.event.OutboxOriginalRecoveryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 由当前可信管理员请求原事件恢复，授权复核与回执、事件状态在同一工作单元提交。
 * <p>示例：{@code recovery.recover(requestId, eventId)}；成功不表示消息已经发送。</p>
 */
@Component
@RequiredArgsConstructor
public class OutboxOriginalRecoveryApplicationService {
    private final CurrentIdentity identity;
    private final AuthorizationAdministrationRepository administration;
    private final OutboxOriginalRecoveryRepository repository;
    private final UnitOfWork work;

    /**
     * 每次调用均在管理协调锁内复核当前管理员身份，再执行幂等恢复。
     * @param requestId 恢复请求UUID
     * @param eventId 原事件UUID
     * @return 首次请求的稳定回执，不代表发送或消费完成
     * @throws IllegalStateException 已有外层事务
     * @throws NullPointerException 请求或事件标识为空
     * @throws com.patrick.stratum.common.error.StratumException 未认证、非当前管理员或事件不可恢复
     * @apiNote 示例：{@code recovery.recover(requestId, eventId)}；操作人由认证上下文提供。
     */
    public OutboxOriginalRecoveryRepository.Receipt recover(UUID requestId, UUID eventId) {
        Objects.requireNonNull(requestId, "请求标识不能为空");
        Objects.requireNonNull(eventId, "事件标识不能为空");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("原事件恢复必须从事务外调用");
        }
        UUID actor = identity.require().userId();
        String trace = CorrelationIdPolicy.resolve(TraceIdContext.getTraceId().orElse(null));
        return work.execute(() -> {
            administration.lock();
            AdministrationPolicy.requireAdministrator(administration.isEnabledAdministrator(actor));
            return Objects.requireNonNull(repository.recover(new OutboxOriginalRecoveryRepository.Request(requestId, eventId, actor, trace)),
                    "原事件恢复回执不能为空");
        });
    }
}
