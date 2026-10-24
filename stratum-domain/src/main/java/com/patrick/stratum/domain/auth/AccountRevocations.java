package com.patrick.stratum.domain.auth;

import java.util.UUID;

/** 目标账号撤销端口；仅由已验证管理权限的应用用例调用，不要求目标当前存在会话。 */
public interface AccountRevocations {
    /**
     * 原子递增账号权威版本，使所有较早版本的Access与Refresh失效。
     * @param userId 已由管理用例验证的目标账号UUID
     * @return 递增后的正版本；尚未登录的账号从1开始
     * @throws com.patrick.stratum.common.error.StratumException 存储故障、非法版本或溢出时失败关闭
     * @apiNote 示例：{@code revocations.revoke(userId)}；数据库回滚时不得恢复较早撤销版本。
     */
    long revoke(UUID userId);
}
