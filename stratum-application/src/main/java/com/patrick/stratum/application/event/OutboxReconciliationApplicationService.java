package com.patrick.stratum.application.event;

import java.util.Objects;

import com.patrick.stratum.application.transaction.UnitOfWork;
import com.patrick.stratum.domain.event.OutboxReconciliationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 在事务外按扫描数量限制消费凭据对账，每一项使用独立写工作单元。
 * <p>示例：{@code reconciler.runBatch(32)}；此入口不启动调度或连接消息代理。</p>
 */
@Component
@RequiredArgsConstructor
public class OutboxReconciliationApplicationService {
    private final UnitOfWork unitOfWork;
    private final OutboxReconciliationRepository repository;

    /**
     * 有界扫描凭据；已提交的单项结果不会因后续项失败而回滚。
     * @param limit 本次最多扫描的凭据数，范围为1至128
     * @return 已扫描与已修复数量及停止原因；扫描数包含未修复的凭据
     * @throws IllegalArgumentException 批次上限超出范围
     * @throws IllegalStateException 调用时已有事务
     * @apiNote 示例：{@code var result = reconciler.runBatch(32);}。
     */
    public Result runBatch(int limit) {
        if (limit < 1 || limit > 128) throw new IllegalArgumentException("对账扫描上限必须在1至128之间");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("对账批次必须从事务外调用");
        }
        int scanned = 0;
        int reconciled = 0;
        while (scanned < limit) {
            if (Thread.currentThread().isInterrupted()) return new Result(scanned, reconciled, Termination.INTERRUPTED);
            var outcome = unitOfWork.execute(() -> Objects.requireNonNull(repository.scanOne(), "单项对账结果不能为空"));
            switch (outcome) {
                case RECONCILED -> { scanned++; reconciled++; }
                case SCANNED -> scanned++;
                case CYCLE_COMPLETE -> { return new Result(scanned, reconciled, Termination.CYCLE_COMPLETE); }
                case BUSY -> { return new Result(scanned, reconciled, Termination.BUSY); }
            }
        }
        return new Result(scanned, reconciled, Termination.LIMIT);
    }

    /**
     * 本次已提交的扫描统计及停止原因。
     * @param scanned 已扫描凭据数
     * @param reconciled 已修复原事件数
     * @param termination 本次停止原因
     */
    public record Result(int scanned, int reconciled, Termination termination) { }

    /** 批次停止原因，不将游标忙或中断误认为轮次完成。 */
    public enum Termination {
        /** 已达到扫描上限。 */ LIMIT,
        /** 当前轮次已完成。 */ CYCLE_COMPLETE,
        /** 其他事务正在持有游标。 */ BUSY,
        /** 调用线程已中断。 */ INTERRUPTED
    }
}
