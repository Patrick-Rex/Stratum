package com.patrick.stratum.infrastructure.auth;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.patrick.stratum.domain.auth.PasswordHasher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证实际BCrypt运算、随机盐和UTF-8边界，不保存真实账号凭据。 */
class BCryptPasswordAdapterImplTest {
    private final PasswordHasher hasher = new BCryptPasswordAdapterImpl();

    /** 同一密码生成不同随机盐，正确密码匹配，错误密码不匹配。 */
    @Test
    void hashesWithRandomSaltAndPreservesCallerBuffer() {
        char[] password = UUID.randomUUID().toString().toCharArray();
        char[] original = password.clone();
        try {
            String first = hasher.hash(password);
            String second = hasher.hash(password);
            assertNotEquals(first, second);
            assertTrue(first.startsWith("$2b$10$"));
            assertTrue(PasswordHasher.supported(first));
            assertTrue(hasher.matches(password, first));
            assertTrue(hasher.matches(password, second));
            assertFalse(hasher.matches("incorrect".toCharArray(), first));
            assertArrayEquals(original, password);
        } finally { Arrays.fill(password, '\0'); Arrays.fill(original, '\0'); }
    }

    /** ASCII与多字节密码都按UTF-8的72字节边界处理，不允许截断前缀登录。 */
    @Test
    void boundsUtf8BytesWithoutTruncation() {
        for (String boundary : List.of("a".repeat(72), "密".repeat(24), "😀".repeat(18))) {
            char[] password = boundary.toCharArray();
            try {
                String encoded = hasher.hash(password);
                assertTrue(hasher.matches(password, encoded));
                char[] overlong = (boundary + "a").toCharArray();
                try {
                    assertThrows(IllegalArgumentException.class, () -> hasher.hash(overlong));
                    assertFalse(hasher.matches(overlong, encoded));
                } finally { Arrays.fill(overlong, '\0'); }
            } finally { Arrays.fill(password, '\0'); }
        }
    }

    /** 空值、空白及孤立UTF-16代理字符不能产生散列或认证成功。 */
    @Test
    void rejectsEmptyWhitespaceAndMalformedUnicode() {
        assertThrows(IllegalArgumentException.class, () -> hasher.hash(null));
        assertFalse(hasher.matches(null, null));
        for (char[] invalid : List.of(new char[0], new char[]{' ', '\t'}, new char[]{'\uD800'}, new char[]{'\uDC00'})) {
            var failure = assertThrows(IllegalArgumentException.class, () -> hasher.hash(invalid));
            assertEquals("密码输入不合法", failure.getMessage());
            assertNull(failure.getCause());
            assertFalse(hasher.matches(invalid, "$2b$10$" + "a".repeat(53)));
        }
    }

    /** 不支持格式及过高成本的存储值在昂贵运算前拒绝。 */
    @Test
    void rejectsMalformedAndUnboundedCostHashes() {
        char[] password = UUID.randomUUID().toString().toCharArray();
        try {
            assertFalse(hasher.matches(password, null));
            for (String encoded : List.of("", "plaintext", "$2b$31$" + "a".repeat(53),
                    "$2b$13$" + "a".repeat(53), "$2x$10$" + "a".repeat(53), "$2b$10$invalid")) {
                assertFalse(hasher.matches(password, encoded));
            }
        } finally { Arrays.fill(password, '\0'); }
    }
}
