package com.patrick.stratum.domain.job;

import com.patrick.stratum.domain.job.JobExecutionRepository.Lease;

/**
 * 任务领取资格的技术端口，在已有写工作单元内锁定并核验当前证据。
 * <p>示例：{@code unitOfWork.execute(() -> authority.lockCurrent(lease))}。</p>
 */
public interface JobLeaseAuthority {
    /**
     * 以原令牌、完整输入与数据库时间核验当前领取，并将行锁保持至工作单元结束。
     * @param lease 原领取证据，不能由HTTP调用方自行构造
     * @return 当前有效为true；忙、过期、已完成或证据不符为false
     * @throws NullPointerException 领取证据为空
     * @throws IllegalStateException 未处于已有写工作单元
     * @throws com.patrick.stratum.common.error.StratumException 数据库状态竞争
     * @apiNote 示例：副作用前后分别调用{@code authority.lockCurrent(lease)}复核租期。
     */
    boolean lockCurrent(Lease lease);
}
