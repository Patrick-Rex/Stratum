package com.patrick.stratum.infrastructure.auth;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.patrick.stratum.domain.auth.TokenCodec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/** 在真实Spring上下文验证外部配置绑定、失败关闭与敏感值保护。 */
class JwtConfigurationTest {
    private static KeyPair keys;

    /**
     * 动态创建测试密钥，避免硬编码凭据。
     * @throws Exception JCA不可用
     */
    @BeforeAll
    static void generateKeys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
    }

    /** 正常PEM配置能装配唯一端口并签发两种令牌，配置toString不暴露值。 */
    @Test
    void bindsExternalKeysAndVerifiesTokens() {
        try (var context = context(properties())) {
            context.refresh();
            var codec = context.getBean(TokenCodec.class);
            for (var purpose : TokenCodec.Purpose.values()) {
                var session = UUID.randomUUID();
                assertEquals(session, codec.verify(codec.issue("user", session, 1, purpose), purpose).sessionId());
            }
            var properties = context.getBean(JwtConfiguration.Properties.class);
            assertEquals("JwtProperties[配置内容已隐藏]", properties.toString());
        }
    }

    /** 组件尚未启用时不装配端口；该行为不代表已安装HTTP安全过滤链。 */
    @Test
    void doesNotInstallCodecUntilExplicitlyEnabled() {
        for (Map<String, Object> values : List.of(Map.<String, Object>of(),
                Map.<String, Object>of("stratum.auth.jwt.enabled", "false"))) {
            try (var context = context(values)) {
                context.refresh();
                assertTrue(context.getBeansOfType(TokenCodec.class).isEmpty());
            }
        }
    }

    /** 已启用时任一必要项缺失或为空均不能启动，异常不携带配置原文。 */
    @Test
    void rejectsMissingAndBlankConfiguration() {
        for (String name : List.of("public-key", "private-key", "issuer", "audience")) {
            var values = properties();
            values.remove("stratum.auth.jwt." + name);
            rejects(values);
            values.put("stratum.auth.jwt." + name, "");
            rejects(values);
        }
    }

    /** 损坏/超长/非PEM/格式不符的私钥和无关前缀均失败。 */
    @Test
    void rejectsMalformedPemWithoutLeakingSecrets() {
        for (String value : List.of("private-secret-invalid", "x".repeat(16_385),
                "-----BEGIN PRIVATE KEY-----\n@bad@\n-----END PRIVATE KEY-----",
                pem(keys.getPrivate().getEncoded(), "RSA PRIVATE KEY"),
                "extra" + pem(keys.getPrivate().getEncoded(), "PRIVATE KEY"))) {
            var values = properties();
            values.put("stratum.auth.jwt.private-key", value);
            rejects(values);
        }
    }

    /**
     * 弱密钥和错配密钥不能通过实际签名自检。
     * @throws Exception JCA不可用
     */
    @Test
    void rejectsWeakAndMismatchedKeys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        for (int bits : List.of(1024, 2048)) {
            generator.initialize(bits);
            var other = generator.generateKeyPair();
            var values = properties();
            values.put("stratum.auth.jwt.private-key", pem(other.getPrivate().getEncoded(), "PRIVATE KEY"));
            if (bits == 1024) {
                values.put("stratum.auth.jwt.public-key", pem(other.getPublic().getEncoded(), "PUBLIC KEY"));
            }
            rejects(values);
        }
    }

    /**
     * 构造尚未刷新的上下文，调用者负责关闭。
     * @param values 配置项
     * @return 上下文
     */
    private static AnnotationConfigApplicationContext context(Map<String, Object> values) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-jwt", values));
        context.register(JwtConfiguration.class);
        return context;
    }

    /**
     * 生成独立配置，密钥不输出至日志。
     * @return 可修改配置
     */
    private static Map<String, Object> properties() {
        return new HashMap<>(Map.of("stratum.auth.jwt.enabled", "true",
                "stratum.auth.jwt.public-key", pem(keys.getPublic().getEncoded(), "PUBLIC KEY"),
                "stratum.auth.jwt.private-key", pem(keys.getPrivate().getEncoded(), "PRIVATE KEY"),
                "stratum.auth.jwt.issuer", "stratum-test", "stratum.auth.jwt.audience", "stratum-api-test"));
    }

    /**
     * 将动态DER编码包装为PEM。
     * @param encoded 动态密钥编码
     * @param label PEM类型
     * @return PEM文本
     */
    private static String pem(byte[] encoded, String label) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded)
                + "\n-----END " + label + "-----";
    }

    /**
     * 断言装配失败且异常链无底层密钥数据。
     * @param values 无效配置
     */
    private static void rejects(Map<String, Object> values) {
        try (var context = context(values)) {
            var failure = assertThrows(RuntimeException.class, context::refresh);
            Throwable root = failure;
            while (root.getCause() != null) {
                assertFalse(root.getMessage().contains("BEGIN"));
                assertFalse(root.getMessage().contains("private-secret-invalid"));
                root = root.getCause();
            }
            assertEquals("JWT配置无效：检查外部RSA PEM密钥、签发者与接收者", root.getMessage());
        }
    }
}
