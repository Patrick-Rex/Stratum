package com.patrick.stratum.application.auth;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.AccountCredentials;
import com.patrick.stratum.domain.auth.LocalAccount;
import com.patrick.stratum.domain.auth.PasswordHasher;
import org.springframework.stereotype.Component;

/** 本地密码身份验证：凭据经查询端口读取，仅返回已验证账号ID，不持有会话状态。 */
@Component
public class LocalIdentityApplicationService {
    private final AccountCredentials credentials;
    private final PasswordHasher passwords;
    private final String unknownAccountHash;

    /**
     * 装配端口并生成未知账号校验使用的随机散列，避免未知用户跳过BCrypt计算。
     * @param credentials 内部查询端口
     * @param passwords 密码散列端口
     * @apiNote 由Spring装配，应用代码仅调用authenticate。
     */
    public LocalIdentityApplicationService(AccountCredentials credentials, PasswordHasher passwords) {
        this.credentials = credentials; this.passwords = passwords;
        char[] random = UUID.randomUUID().toString().toCharArray();
        try { this.unknownAccountHash = passwords.hash(random); }
        finally { Arrays.fill(random, '\0'); }
    }

    /**
     * 验证精确用户名及密码，未知、非法、禁用和密码错误统一拒绝。
     * 存储故障继续失败关闭，不将其误报为凭据错误；无身份字段由HTTP请求自行指定。
     * @param username 用户输入的登录标识
     * @param password 消费后清零的密码数组；外层调用方也须保证清理
     * @param traceId 服务器链路标识
     * @return 验证成功的账号UUID，不能据此跳过会话或权限检查
     * @throws StratumException 凭据无效为UNAUTHORIZED；存储故障保持内部错误
     * @apiNote 示例：{@code identities.authenticate(username, password, traceId)}在事务外调用。
     */
    public UUID authenticate(String username, char[] password, String traceId) {
        try {
            Optional<AccountCredentials.Snapshot> account = Optional.empty();
            if (validUsername(username)) { account = credentials.findByUsername(username, traceId); }
            boolean matched = passwords.matches(password, account.map(AccountCredentials.Snapshot::passwordHash).orElse(unknownAccountHash));
            if (!matched || account.isEmpty() || !account.orElseThrow().enabled()) {
                throw new StratumException(CommonErrorCode.UNAUTHORIZED);
            }
            return account.orElseThrow().id();
        } finally {
            if (password != null) { Arrays.fill(password, '\0'); }
        }
    }

    /**
     * 在查询前判断登录标识技术边界，不回显非法输入。
     * @param username 用户输入
     * @return 是否可构造精确凭据查询
     */
    private static boolean validUsername(String username) {
        try { LocalAccount.requireUsername(username); return true; }
        catch (IllegalArgumentException failure) { return false; }
    }
}
