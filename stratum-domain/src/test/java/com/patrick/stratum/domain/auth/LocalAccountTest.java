package com.patrick.stratum.domain.auth;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 校验账号领域边界；格式样本不是任何用户密码的真实散列。 */
class LocalAccountTest {
    private static final String FORMAT_SAMPLE = "$2b$10$" + "a".repeat(53);

    /** 身份由领域生成，账号不保存原始密码，默认日志描述仅含ID。 */
    @Test
    void createsFixedIdentityWithoutExposingCredentials() {
        var account = LocalAccount.create("Admin_1", FORMAT_SAMPLE, true);
        assertEquals(account.getId(), UUID.fromString(account.getId()).toString());
        assertNotEquals(account.getId(), LocalAccount.create("Admin_1", FORMAT_SAMPLE, true).getId());
        assertEquals("Admin_1", account.getUsername());
        assertEquals(FORMAT_SAMPLE, account.getPasswordHash());
        assertTrue(account.isEnabled());
        assertTrue(account.isBootstrapAdministrator());
        assertNull(account.getVersion());
        assertFalse(account.toString().contains(FORMAT_SAMPLE));
        assertFalse(account.toString().contains("Admin_1"));
    }

    /** 精确用户名边界拒绝空白、控制字符和超长输入，不悄悄修正。 */
    @Test
    void rejectsInvalidUsernames() {
        for (String username : List.of("", " ", " admin", "admin ", "name\n", "x".repeat(65), "a|b", "a:b")) {
            assertThrows(IllegalArgumentException.class, () -> LocalAccount.create(username, FORMAT_SAMPLE, false));
        }
        assertThrows(IllegalArgumentException.class, () -> LocalAccount.create(null, FORMAT_SAMPLE, false));
        assertEquals("x".repeat(64), LocalAccount.create("x".repeat(64), FORMAT_SAMPLE, false).getUsername());
    }

    /** 明文、畸形和无界成本散列不进入模型；错误消息不携带输入。 */
    @Test
    void rejectsUnsupportedHashes() {
        for (String encoded : List.of("plaintext-secret", "", "$2b$31$" + "a".repeat(53),
                "$2b$03$" + "a".repeat(53), "$2x$10$" + "a".repeat(53), FORMAT_SAMPLE + "a")) {
            var failure = assertThrows(IllegalArgumentException.class, () -> LocalAccount.create("admin", encoded, true));
            assertEquals("密码散列格式不合法", failure.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> LocalAccount.create("admin", null, true));
        assertTrue(PasswordHasher.supported("$2y$12$" + "a".repeat(53)));
    }

    /** 重建保留身份、禁用状态及版本，非法ID或版本拒绝。 */
    @Test
    void restoresValidatedStateAndAdvancesVersion() {
        String id = UUID.randomUUID().toString();
        var account = LocalAccount.restore(id, "admin", FORMAT_SAMPLE, false, false, 2);
        assertEquals(id, account.getId());
        assertFalse(account.isEnabled());
        assertFalse(account.isBootstrapAdministrator());
        assertEquals(2, account.getVersion());
        assertThrows(IllegalArgumentException.class, () -> account.markPersisted(2));
        account.markPersisted(3);
        assertEquals(3, account.getVersion());
        assertThrows(IllegalArgumentException.class, () -> LocalAccount.restore("1-1-1-1-1", "admin", FORMAT_SAMPLE, true, false, 0));
        assertThrows(IllegalArgumentException.class, () -> LocalAccount.restore(id, "admin", FORMAT_SAMPLE, true, false, -1));
    }
}
