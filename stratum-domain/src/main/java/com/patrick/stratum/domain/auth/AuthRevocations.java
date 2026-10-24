package com.patrick.stratum.domain.auth;

import java.util.UUID;

/** 本人会话撤销端口；参数只能来自服务器已验证身份，不能采用客户端自报用户或版本。 */
public interface AuthRevocations {
    /**
     * 原子校验用户版本及会话归属后删除当前会话，使其Access和所有Refresh不可再用。
     * @param userId 已认证账号
     * @param sessionId 已认证会话
     * @param version 已认证撤销版本
     * @throws com.patrick.stratum.common.error.StratumException 身份过期返回401，存储失败返回500
     * @apiNote 示例：{@code revocations.revokeSession(userId, sessionId, version)}；不影响其他会话。
     */
    void revokeSession(UUID userId, UUID sessionId, long version);

    /**
     * 原子校验当前身份并递增权威用户版本，使该账号全部既有Access与Refresh失效。
     * @param userId 已认证账号
     * @param sessionId 已认证会话
     * @param version 已认证撤销版本
     * @return 递增后的精确版本
     * @throws com.patrick.stratum.common.error.StratumException 身份过期返回401，存储失败或版本溢出返回500
     * @apiNote 示例：{@code long next = revocations.revokeAll(userId, sessionId, version)}；成功后可重新登录。
     */
    long revokeAll(UUID userId, UUID sessionId, long version);
}
