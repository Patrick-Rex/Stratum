package com.patrick.stratum.domain.auth;

/** 密码散列端口；密码数组由调用方及时清零，适配器不得长期保存或记录原文。 */
public interface PasswordHasher {
    /**
     * 生成带随机盐的BCrypt散列，不截断超长输入。
     * @param password 密码字符数组，UTF-8编码最多72字节
     * @return 可持久化的BCrypt散列，仍不得写入日志
     * @throws IllegalArgumentException 输入为空或超出编码边界
     * @apiNote 示例：{@code String encoded = hasher.hash(password)}后由调用方清零password。
     */
    String hash(char[] password);

    /**
     * 比较密码与散列；不合法输入按不匹配处理，不输出原文。
     * @param password 候选密码数组
     * @param encoded 已持久化散列
     * @return 是否匹配
     * @apiNote 示例：{@code hasher.matches(password, account.getPasswordHash())}。
     */
    boolean matches(char[] password, String encoded);

    /**
     * 检查受支持的BCrypt格式并限制计算成本在4至12之间。
     * @param encoded 待检查散列
     * @return 格式与成本是否受支持；不代表密码已验证
     * @apiNote 示例：{@code PasswordHasher.supported(encoded)}用于拒绝明文进入持久化模型。
     */
    static boolean supported(String encoded) {
        return encoded != null && encoded.matches("\\$2[aby]\\$(0[4-9]|1[0-2])\\$[./A-Za-z0-9]{53}");
    }
}
