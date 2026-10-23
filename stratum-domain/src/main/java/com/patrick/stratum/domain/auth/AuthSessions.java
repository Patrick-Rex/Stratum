package com.patrick.stratum.domain.auth;

/** 会话权威存储端口；只接受服务器验证后的身份，不保存密码或令牌原文。 */
public interface AuthSessions {
    /**
     * 读取用户撤销版本，首次身份验证成功后可初始化为0。
     * @param subject 已通过身份验证的用户标识
     * @return 非负版本，不以浮点数表示
     * @apiNote 示例：{@code long version = sessions.userVersion(userId)}。
     */
    long userVersion(String subject);

    /**
     * 原子创建会话及Refresh白名单，要求用户版本已经初始化且仍匹配。
     * @param refresh 已签发并验证的Refresh声明
     * @throws com.patrick.stratum.common.error.StratumException 版本/时效无效返回401，标识重复返回409
     * @apiNote 示例：{@code sessions.create(codec.verify(refreshToken, TokenCodec.Purpose.REFRESH))}。
     */
    void create(TokenCodec.Claims refresh);

    /**
     * 对密码学校验通过的Access再次验证Redis会话与用户撤销版本；不缓存授权成功。
     * @param access 已验证Access声明
     * @throws com.patrick.stratum.common.error.StratumException 会话失效返回401，存储不可用拒绝请求
     * @apiNote 示例：{@code sessions.requireActive(codec.verify(token, TokenCodec.Purpose.ACCESS))}。
     */
    void requireActive(TokenCodec.Claims access);
}
