package com.patrick.stratum.domain.auth;

import java.util.UUID;
import java.util.regex.Pattern;

import com.patrick.stratum.domain.model.AggregateRoot;

/** 聚合根：本地账号的固定身份及密码散列；初始化标记只用于配置幂等，不赋予任何权限。 */
public final class LocalAccount extends AggregateRoot<String> {
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private final String username;
    private final String passwordHash;
    private final boolean enabled;
    private final boolean bootstrapAdministrator;

    /**
     * 创建或重建账号，密码只允许已支持的散列格式。
     * @param id 标准UUID
     * @param username 固定登录标识
     * @param passwordHash BCrypt散列
     * @param enabled 是否启用
     * @param bootstrapAdministrator 是否为首次初始化账号
     * @param version 持久化版本，新增为空
     */
    private LocalAccount(String id, String username, String passwordHash, boolean enabled,
            boolean bootstrapAdministrator, Long version) {
        super(id);
        this.username = requireUsername(username);
        if (!PasswordHasher.supported(passwordHash)) { throw new IllegalArgumentException("密码散列格式不合法"); }
        if (version != null && version < 0) { throw new IllegalArgumentException("账号版本不合法"); }
        this.passwordHash = passwordHash;
        this.enabled = enabled;
        this.bootstrapAdministrator = bootstrapAdministrator;
        setVersion(version);
    }

    /**
     * 领域内生成UUID，创建启用的新账号，不接收明文密码。
     * @param username 登录标识，区分大小写且不自动裁剪
     * @param passwordHash 受支持的BCrypt散列
     * @param bootstrapAdministrator 初始管理员标记，不能用来绕过RBAC
     * @return 尚未持久化的账号
     * @apiNote 示例：{@code LocalAccount.create(username, hasher.hash(password), true)}。
     */
    public static LocalAccount create(String username, String passwordHash, boolean bootstrapAdministrator) {
        return new LocalAccount(UUID.randomUUID().toString(), username, passwordHash, true, bootstrapAdministrator, null);
    }

    /**
     * 由可信存储恢复账号快照。
     * @param id 标准UUID
     * @param username 登录标识
     * @param passwordHash BCrypt散列
     * @param enabled 是否启用
     * @param bootstrapAdministrator 初始化标记
     * @param version 持久化版本
     * @return 已持久化账号
     * @throws IllegalArgumentException 身份、散列或版本不合法
     * @apiNote 示例：{@code LocalAccount.restore(id, username, encoded, true, false, 0)}。
     */
    public static LocalAccount restore(String id, String username, String passwordHash, boolean enabled,
            boolean bootstrapAdministrator, long version) {
        if (id == null || !UUID.fromString(id).toString().equals(id)) {
            throw new IllegalArgumentException("账号标识不合法");
        }
        return new LocalAccount(id, username, passwordHash, enabled, bootstrapAdministrator, version);
    }

    /**
     * 校验有界登录标识，保持精确匹配语义。
     * @param username 登录标识
     * @return 原标识
     * @throws IllegalArgumentException 不符合1至64位字母、数字、点、下划线或连字符
     * @apiNote 示例：{@code LocalAccount.requireUsername(input)}。
     */
    public static String requireUsername(String username) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException("用户名格式不合法");
        }
        return username;
    }

    /**
     * 获取登录标识。
     * @return 固定用户名
     * @apiNote 示例：{@code account.getUsername()}。
     */
    public String getUsername() { return username; }

    /**
     * 获取仅用于持久化与密码校验的散列，禁止进入响应或日志。
     * @return BCrypt散列
     * @apiNote 示例：{@code hasher.matches(password, account.getPasswordHash())}。
     */
    public String getPasswordHash() { return passwordHash; }

    /**
     * 获取启用状态。
     * @return 是否允许身份验证
     * @apiNote 示例：{@code account.isEnabled()}。
     */
    public boolean isEnabled() { return enabled; }

    /**
     * 获取初始化标记，仅供初始化幂等与后续角色绑定使用。
     * @return 是否为初始化账号
     * @apiNote 示例：{@code account.isBootstrapAdministrator()}；API授权仍查询RBAC。
     */
    public boolean isBootstrapAdministrator() { return bootstrapAdministrator; }

    /**
     * 保存成功后记录持久化版本，必须单调递增。
     * @param version 新版本
     * @throws IllegalArgumentException 版本倒退或相等
     * @apiNote 示例：{@code account.markPersisted(0)}仅由仓储保存成功后调用。
     */
    public void markPersisted(long version) {
        if (version < 0 || getVersion() != null && version <= getVersion()) {
            throw new IllegalArgumentException("账号版本必须递增");
        }
        setVersion(version);
    }
}
