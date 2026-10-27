package com.patrick.stratum.domain.event;

/**
 * 多实例共享的Relay暂停与批次准入端口，存储实现不自行开启事务。
 * <p>示例：Application通过写工作单元调用{@code control.tryAdmit()}，仅在提交后使用准入结果。</p>
 */
public interface OutboxRelayControlRepository {
    /**
     * 获取当前权威状态；读取不自动恢复或覆盖已有配置。
     * @return 暂停标志及单调版本
     * @apiNote 示例：{@code control.current()}供管理端展示观察版本。
     */
    State current();

    /**
     * 按观察版本变更暂停状态，未变更不递增；旧版本或版本耗尽不能覆盖当前值。
     * @param expectedRevision 调用者观察的非负版本
     * @param paused 目标暂停标志
     * @return 本工作单元提交后的权威状态
     * @throws IllegalArgumentException 版本为负数
     * @throws IllegalStateException 未处于已有写工作单元
     * @throws com.patrick.stratum.common.error.StratumException 观察版本过期或递增上限已到
     * @apiNote 示例：{@code unitOfWork.execute(() -> control.change(revision, true))}。
     */
    State change(long expectedRevision, boolean paused);

    /**
     * 在持有同一状态行锁时决定本批次是否获准；仅提交后可开始工作。
     * @return 准入结论及观察到的状态版本；已暂停时不获准
     * @throws IllegalStateException 未处于已有写工作单元
     * @apiNote 示例：事务提交后仅当{@code admission.allowed()}为true时开始批次。
     */
    Admission tryAdmit();

    /**
     * 值对象：权威暂停状态及单调版本。
     * @param paused 是否暂停
     * @param revision 非负单调版本
     */
    record State(boolean paused, long revision) {
        /**
         * 校验版本边界。
         * @param paused 是否暂停
         * @param revision 非负版本
         * @throws IllegalArgumentException 版本为负数
         * @apiNote 示例：{@code new State(true, 0)}表示初始暂停。
         */
        public State { if (revision < 0) throw new IllegalArgumentException("Relay控制版本不能为负数"); }
    }

    /**
     * 值对象：在数据库状态行保护下产生的单次批次准入结果。
     * @param allowed 是否允许批次开始
     * @param revision 决定准入时的非负版本
     */
    record Admission(boolean allowed, long revision) {
        /**
         * 校验准入版本边界。
         * @param allowed 是否允许
         * @param revision 非负版本
         * @throws IllegalArgumentException 版本为负数
         * @apiNote 示例：{@code new Admission(false, 1)}表示该版本暂停。
         */
        public Admission { if (revision < 0) throw new IllegalArgumentException("Relay准入版本不能为负数"); }
    }
}
