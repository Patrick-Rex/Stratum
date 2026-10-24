package com.patrick.stratum.domain.auth;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 内部身份验证所需的只读凭据端口；实现必须通过QueryGateway，禁止把返回值暴露为HTTP响应。 */
public interface AccountCredentials {
    /**
     * 按精确用户名读取单个内部快照，不验证密码，也不建立会话。
     * @param username 登录标识
     * @param traceId 服务器链路标识
     * @return 存在的账号凭据，未知账号为空
     * @apiNote 示例：{@code credentials.findByUsername(username, traceId)}在事务外调用。
     */
    Optional<Snapshot> findByUsername(String username, String traceId);

    /**
     * 值对象：内部凭据快照，日志描述隐藏所有内容。
     * @param id 账号UUID
     * @param username 登录标识
     * @param passwordHash BCrypt散列，不得输出
     * @param enabled 是否启用
     */
    record Snapshot(UUID id, String username, String passwordHash, boolean enabled) {
        /**
         * 校验存储快照，防止非法凭据继续参与认证。
         * @param id 账号UUID
         * @param username 登录标识
         * @param passwordHash BCrypt散列
         * @param enabled 是否启用
         * @apiNote 仅由内部查询构造，不能从HTTP请求绑定。
         */
        public Snapshot {
            Objects.requireNonNull(id, "账号标识不能为空");
            LocalAccount.requireUsername(username);
            if (!PasswordHasher.supported(passwordHash)) { throw new IllegalArgumentException("凭据快照不合法"); }
        }

        /**
         * 隐藏凭据与登录标识，避免集合或记录日志间接泄漏。
         * @return 固定脱敏描述
         * @apiNote 示例：{@code snapshot.toString()}不含密码散列。
         */
        @Override
        public String toString() { return "AccountCredentials[内容已隐藏]"; }
    }
}
