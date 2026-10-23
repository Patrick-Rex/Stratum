package com.patrick.stratum.domain.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Token签发与密码学验证端口；Redis会话和授权状态由应用另外校验，不属于此端口。 */
public interface TokenCodec {

    /**
     * 签发指定用途的令牌，自动产生jti和有效期。
     * @param subject 服务器确定的用户标识
     * @param sessionId 服务器确定的会话标识
     * @param version 用户撤销版本，非负
     * @param purpose 令牌用途
     * @return 签名后的敏感令牌，禁止写入日志
     * @throws IllegalArgumentException 签发输入不合法
     * @apiNote 示例：{@code codec.issue(userId, sessionId, 0, Purpose.ACCESS)}。
     */
    String issue(String subject, UUID sessionId, long version, Purpose purpose);

    /**
     * 校验签名、用途、签发者、接收者、claims和时间，尚不代表会话有效或具备权限。
     * @param token 待验证的敏感令牌
     * @param purpose 接口接受的唯一用途
     * @return 经校验的不可变claims
     * @throws com.patrick.stratum.common.error.StratumException 令牌无效时返回UNAUTHORIZED
     * @apiNote 示例：{@code codec.verify(token, Purpose.ACCESS)}后仍须查询Redis权威状态。
     */
    Claims verify(String token, Purpose purpose);

    /** 值对象枚举：固定Access与Refresh用途，避免二者相互替代。 */
    enum Purpose {
        ACCESS, REFRESH;

        /**
         * 获取安全设计规定的有效期。
         * @return Access为15分钟，Refresh为14天
         * @apiNote 示例：{@code Purpose.ACCESS.lifetime()}。
         */
        public Duration lifetime() {
            return this == ACCESS ? Duration.ofMinutes(15) : Duration.ofDays(14);
        }
    }

    /**
     * 值对象：验证后的身份和令牌元数据，不承载密码或令牌原文。
     * @param subject 用户标识
     * @param tokenId 唯一令牌标识
     * @param sessionId 会话标识
     * @param version 用户撤销版本
     * @param issuedAt 签发时间
     * @param expiresAt 过期时间
     * @param purpose 令牌用途
     */
    record Claims(String subject, UUID tokenId, UUID sessionId, long version,
            Instant issuedAt, Instant expiresAt, Purpose purpose) { }
}
