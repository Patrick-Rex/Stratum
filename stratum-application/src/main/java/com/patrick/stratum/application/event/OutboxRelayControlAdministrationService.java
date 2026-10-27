package com.patrick.stratum.application.event;

import java.util.Objects;
import java.util.UUID;

import com.patrick.stratum.application.auth.CurrentIdentity;
import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.domain.auth.AdministrationPolicy;
import com.patrick.stratum.domain.auth.AuthorizationAdministrationRepository;
import com.patrick.stratum.domain.event.OutboxRelayControlRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 由当前启用管理员查询或变更共享Relay暂停状态，权限与版本在同一工作单元内复核。
 * <p>示例：{@code administration.change(observed.revision(), true)}显式暂停。</p>
 */
@Component
@RequiredArgsConstructor
public class OutboxRelayControlAdministrationService {
    private final CurrentIdentity identity;
    private final AuthorizationAdministrationRepository administration;
    private final OutboxRelayControlRepository repository;
    private final UnitOfWork work;

    /**
     * 重新核验当前管理员并读取共享状态。
     * @return 当前暂停标志及观察版本
     * @throws IllegalStateException 已存在外层事务
     * @throws NullPointerException 存储结果为空
     * @throws com.patrick.stratum.common.error.StratumException 未认证或不再是启用管理员
     * @apiNote 示例：{@code var observed = administration.current()}。
     */
    public OutboxRelayControlRepository.State current() {
        requireOutside(); UUID actor = identity.require().userId();
        return work.execute(() -> {
            requireAdministrator(actor);
            return Objects.requireNonNull(repository.current(), "Relay控制状态不能为空");
        });
    }

    /**
     * 重新核验当前管理员，以观察版本原子更新共享暂停状态。
     * @param expectedRevision 调用者观察的非负版本
     * @param paused 目标暂停状态
     * @return 事务提交后的权威状态
     * @throws IllegalStateException 已存在外层事务
     * @throws NullPointerException 存储结果为空
     * @throws IllegalArgumentException 版本非法
     * @throws com.patrick.stratum.common.error.StratumException 未授权、版本过期或状态竞争
     * @apiNote 示例：{@code administration.change(observed.revision(), false)}显式恢复。
     */
    public OutboxRelayControlRepository.State change(long expectedRevision, boolean paused) {
        requireOutside(); UUID actor = identity.require().userId();
        return work.execute(() -> {
            requireAdministrator(actor);
            return Objects.requireNonNull(repository.change(expectedRevision, paused), "Relay控制状态不能为空");
        });
    }

    /**
     * 持管理协调锁读取权威管理员关系，避免并发撤权后继续操作。
     * @param actor 已认证服务器主体
     */
    private void requireAdministrator(UUID actor) {
        administration.lock();
        AdministrationPolicy.requireAdministrator(administration.isEnabledAdministrator(actor));
    }

    /** 避免外层回滚导致调用者误认为管理状态已经提交。 */
    private static void requireOutside() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Relay管理入口必须从事务外调用");
    }
}
