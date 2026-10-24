package com.patrick.stratum.application.auth;

import java.security.Principal;
import java.util.Objects;
import java.util.UUID;

/** 应用和查询获取当前服务器可信身份的端口，不允许从请求体或自报用户头构造身份。 */
public interface CurrentIdentity {
    /**
     * 获取经过Access密码学与权威会话验证的当前身份。
     * @return 不含密码或令牌的不可变快照
     * @throws com.patrick.stratum.common.error.StratumException 缺少可信身份时为UNAUTHORIZED
     * @apiNote 示例：{@code identities.require().userId()}；跨线程须显式传递快照。
     */
    Snapshot require();

    /**
     * 不可变的已验证身份，不携带权限结论，RBAC和数据范围须另外检查。
     * @param userId 服务器验证的账号UUID
     * @param sessionId 权威会话UUID
     * @param version 用户撤销版本
     */
    record Snapshot(UUID userId, UUID sessionId, long version) implements Principal {
        /**
         * 校验身份元数据，不接受缺失标识或负版本。
         * @param userId 账号UUID
         * @param sessionId 会话UUID
         * @param version 非负版本
         * @apiNote 仅由安全组件在验证完成后构造，不作为HTTP请求DTO。
         */
        public Snapshot {
            Objects.requireNonNull(userId, "用户标识不能为空");
            Objects.requireNonNull(sessionId, "会话标识不能为空");
            if (version < 0) { throw new IllegalArgumentException("用户版本非法"); }
        }

        /**
         * 返回标准主体名称。
         * @return 账号UUID字符串
         * @apiNote 示例：{@code identity.getName()}用于服务器命令主体。
         */
        @Override public String getName() { return userId.toString(); }

        /**
         * 隐藏会话标识，防止安全上下文调试日志间接输出。
         * @return 固定描述
         * @apiNote 不使用此描述作为用户ID。
         */
        @Override public String toString() { return "AuthenticatedIdentity[内容已隐藏]"; }
    }
}
