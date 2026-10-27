package com.patrick.stratum.domain.event;

/**
 * 消费凭据对账端口：每个已有写工作单元最多检查一条凭据，不推断发布确认。
 * <p>示例：Application以unitOfWork.execute(reconciler::scanOne)执行有界单项事务。</p>
 */
public interface OutboxReconciliationRepository {
    /**
     * 按本轮数据库UTC上界原子推进持久游标并复核一条凭据；忙行或不匹配在后续轮次重访。
     * @return 修复、已扫描、轮次结束或另一事务持有游标
     * @throws IllegalStateException 没有活动写工作单元或游标存储异常
     * @apiNote 示例：按扫描数限制每次Application调用，直到轮次结束或游标忙。
     */
    ScanOutcome scanOne();

    /** 值对象枚举：单项扫描结果，不将未修复、无候选或锁忙当作发布成功。 */
    enum ScanOutcome {
        /** 匹配消费凭据并修正一条未确认原事件。 */
        RECONCILED,
        /** 检查或跳过一条凭据，游标已前进。 */
        SCANNED,
        /** 当前轮次结束，游标已回到开头。 */
        CYCLE_COMPLETE,
        /** 其他事务持有游标，本次没有扫描或修改。 */
        BUSY
    }
}
