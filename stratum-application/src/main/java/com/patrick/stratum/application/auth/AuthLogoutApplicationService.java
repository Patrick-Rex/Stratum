package com.patrick.stratum.application.auth;

import com.patrick.stratum.domain.auth.AuthRevocations;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 仅用服务器当前身份编排本人注销，不接受外部用户、会话或版本参数。 */
@Component
@RequiredArgsConstructor
public class AuthLogoutApplicationService {
    private final CurrentIdentity identities;
    private final AuthRevocations revocations;

    /**
     * 注销当前已验证会话，存储确认前不返回成功。
     * @apiNote 示例：{@code logout.logout()}；其他会话不受影响。
     * @throws com.patrick.stratum.common.error.StratumException 身份无效或撤销存储失败
     */
    public void logout() {
        var identity = identities.require();
        revocations.revokeSession(identity.userId(), identity.sessionId(), identity.version());
    }

    /**
     * 注销当前账号全部已有会话，身份版本再次原子校验。
     * @apiNote 示例：{@code logout.logoutAll()}；操作完成后当前Access也立即失效。
     * @throws com.patrick.stratum.common.error.StratumException 身份无效或撤销存储失败
     */
    public void logoutAll() {
        var identity = identities.require();
        revocations.revokeAll(identity.userId(), identity.sessionId(), identity.version());
    }
}
