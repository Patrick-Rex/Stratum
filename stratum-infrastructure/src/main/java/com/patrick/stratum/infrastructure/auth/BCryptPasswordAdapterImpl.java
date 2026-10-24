package com.patrick.stratum.infrastructure.auth;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.patrick.stratum.domain.auth.PasswordHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/** BCrypt密码适配器：生成成本10的2b散列，验证前严格限制UTF-8长度并拒绝错误代理字符。 */
@Component
public final class BCryptPasswordAdapterImpl implements PasswordHasher {
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(BCryptPasswordEncoder.BCryptVersion.$2B, 10);

    /** {@inheritDoc} */
    @Override
    public String hash(char[] password) {
        if (!valid(password)) { throw new IllegalArgumentException("密码输入不合法"); }
        return encoder.encode(CharBuffer.wrap(password));
    }

    /** {@inheritDoc} */
    @Override
    public boolean matches(char[] password, String encoded) {
        if (!valid(password) || !PasswordHasher.supported(encoded)) { return false; }
        try { return encoder.matches(CharBuffer.wrap(password), encoded); }
        catch (IllegalArgumentException failure) { return false; }
    }

    /**
     * 验证1至72字节的UTF-8输入，清除临时编码缓冲，不修改调用方数组。
     * @param password 候选密码
     * @return 输入是否有效
     */
    private static boolean valid(char[] password) {
        if (password == null || password.length == 0 || password.length > 72
                || CharBuffer.wrap(password).chars().allMatch(Character::isWhitespace)) { return false; }
        try {
            var bytes = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(password));
            try { return bytes.remaining() <= 72; }
            finally { if (bytes.hasArray()) { Arrays.fill(bytes.array(), (byte) 0); } }
        } catch (CharacterCodingException failure) { return false; }
    }
}
