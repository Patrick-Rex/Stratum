package com.patrick.stratum.domain.auth;

import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;

/** 领域服务：统一管理操作者与最后一个可用管理员保护，不持有数据库或会话状态。 */
public final class AdministrationPolicy {
    /** 禁止实例化无状态规则。 */
    private AdministrationPolicy() { }

    /**
     * 要求操作者是权威存储中当前可用管理员。
     * @param enabledAdministrator 是否为启用的管理员角色成员
     * @throws StratumException 非管理员返回403
     * @apiNote 示例：{@code AdministrationPolicy.requireAdministrator(currentState)}。
     */
    public static void requireAdministrator(boolean enabledAdministrator) {
        if (!enabledAdministrator) throw new StratumException(CommonErrorCode.FORBIDDEN);
    }

    /**
     * 停用或移除可用管理员时，必须至少留下另一名可用管理员。
     * @param enabledCount 持有协调锁后读取的可用管理员数量
     * @param removesEnabledAdministrator 本次是否减少一名可用管理员
     * @throws StratumException 会移除最后一名管理员时返回409
     * @apiNote 示例：{@code AdministrationPolicy.requireAdministratorRemaining(count, removing)}。
     */
    public static void requireAdministratorRemaining(long enabledCount, boolean removesEnabledAdministrator) {
        if (enabledCount < 0) throw new IllegalArgumentException("管理员数量不合法");
        if (removesEnabledAdministrator && enabledCount <= 1) throw new StratumException(CommonErrorCode.CONFLICT);
    }
}
