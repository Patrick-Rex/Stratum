package com.patrick.stratum.application.event;

import java.util.Objects;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.domain.event.OutboxRelayControlRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 共享Relay状态的应用边界；准入及变更只有在独立工作单元提交后返回。
 * <p>示例：{@code control.change(observed.revision(), true)}显式暂停；不自行启动任务。</p>
 */
@Component
@RequiredArgsConstructor
public class OutboxRelayControlApplicationService {
    private final UnitOfWork work;
    private final OutboxRelayControlRepository repository;

    /**
     * 在事务外读取共享控制状态，不改变该状态。
     * @return 当前暂停标志和观察版本
     * @throws IllegalStateException 已在事务中
     * @throws NullPointerException 存储结果为空
     * @apiNote 示例：{@code var state = control.current()}。
     */
    public OutboxRelayControlRepository.State current() {
        requireOutside();
        return Objects.requireNonNull(repository.current(), "Relay控制状态不能为空");
    }

    /**
     * 使用已观察版本显式变更暂停状态，不自动覆盖较新配置。
     * @param expectedRevision 已观察的非负版本
     * @param paused 目标暂停标志
     * @return 独立事务已提交的状态
     * @throws IllegalStateException 已在事务中
     * @throws NullPointerException 存储结果为空
     * @throws IllegalArgumentException 版本非法
     * @throws com.patrick.stratum.common.error.StratumException 状态竞争、旧版本或版本溢出
     * @apiNote 示例：{@code control.change(state.revision(), false)}显式恢复。
     */
    public OutboxRelayControlRepository.State change(long expectedRevision, boolean paused) {
        requireOutside();
        return work.execute(() -> Objects.requireNonNull(repository.change(expectedRevision, paused), "Relay控制状态不能为空"));
    }

    /**
     * 通过独立工作单元锁定权威状态并决定批次准入，提交失败不返回资格。
     * @return 已提交的准入结果；只有允许时调用方才能开始批次
     * @throws IllegalStateException 已在事务中
     * @throws NullPointerException 存储结果为空
     * @throws com.patrick.stratum.common.error.StratumException 状态行竞争
     * @apiNote 示例：{@code if (control.tryAdmit().allowed()) { startBatch(); }}。
     */
    public OutboxRelayControlRepository.Admission tryAdmit() {
        requireOutside();
        return work.execute(() -> Objects.requireNonNull(repository.tryAdmit(), "Relay准入结果不能为空"));
    }

    /** 避免共享控制结果参与外层事务后被误当作已提交事实。 */
    private static void requireOutside() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Relay控制入口必须从事务外调用");
    }
}
