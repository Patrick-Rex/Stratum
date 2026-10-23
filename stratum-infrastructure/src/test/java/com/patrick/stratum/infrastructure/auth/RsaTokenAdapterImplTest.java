package com.patrick.stratum.infrastructure.auth;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.TokenCodec.Purpose;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 使用每次测试运行生成的真实RSA密钥验证令牌边界，不提交私钥或令牌常量。 */
class RsaTokenAdapterImplTest {
    private static final Instant NOW = Instant.parse("2026-10-24T08:00:00Z");
    private static final UUID SESSION = UUID.randomUUID();
    private static KeyPair keys;

    /**
     * 创建本次运行专用RSA密钥。
     * @throws Exception JCA密钥生成失败
     */
    @BeforeAll
    static void generateKeys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
    }

    /** 两类令牌具备独立jti、固定有效期，版本保持long精度。 */
    @Test
    void roundTripsBothPurposesWithoutVersionPrecisionLoss() {
        var codec = codecAt(NOW);
        for (Purpose purpose : Purpose.values()) {
            String token = codec.issue("user-1", SESSION, Long.MAX_VALUE, purpose);
            var claims = codec.verify(token, purpose);
            assertEquals("user-1", claims.subject());
            assertEquals(SESSION, claims.sessionId());
            assertEquals(Long.MAX_VALUE, claims.version());
            assertEquals(NOW, claims.issuedAt());
            assertEquals(NOW.plus(purpose.lifetime()), claims.expiresAt());
            assertEquals(purpose, claims.purpose());
            assertNotEquals(claims.tokenId(), codec.verify(codec.issue("user-1", SESSION, 0, purpose), purpose).tokenId());
            assertFalse(claims.toString().contains(token));
            rejects(codec, token, purpose == Purpose.ACCESS ? Purpose.REFRESH : Purpose.ACCESS);
        }
    }

    /** 到期瞬间拒绝、到期前接受，未来签发时间不被时钟宽限掩盖。 */
    @Test
    void enforcesExactExpiryAndFutureIssueTime() {
        for (Purpose purpose : Purpose.values()) {
            String token = codecAt(NOW).issue("user-1", SESSION, 0, purpose);
            assertNotNull(codecAt(NOW.plus(purpose.lifetime()).minusNanos(1)).verify(token, purpose));
            rejects(codecAt(NOW.plus(purpose.lifetime())), token, purpose);
            rejects(codecAt(NOW.minusSeconds(1)), token, purpose);
        }
    }

    /**
     * 即使由可信密钥签名，缺失必要声明或不合法版本/标识仍拒绝。
     * @throws Exception 测试签名失败
     */
    @Test
    void rejectsMissingAndMalformedClaims() throws Exception {
        for (String required : List.of("sub", "iat", "exp", "jti", "sid", "ver", "iss", "aud", "token_use")) {
            var claims = baseline();
            claims.remove(required);
            rejects(codecAt(NOW), sign(claims, JWSAlgorithm.RS256, "JWT"), Purpose.ACCESS);
        }
        var malformed = List.<Map.Entry<String, Object>>of(
                Map.entry("sub", " "), Map.entry("sub", 123), Map.entry("sub", "x".repeat(65)), Map.entry("sub", "name\n"),
                Map.entry("sid", "1-1-1-1-1"), Map.entry("sid", 123), Map.entry("jti", "unknown"),
                Map.entry("ver", -1L), Map.entry("ver", "0"), Map.entry("ver", 0.5),
                Map.entry("iss", "other"), Map.entry("aud", List.of("other")),
                Map.entry("aud", List.of("stratum-api", "other")), Map.entry("token_use", "REFRESH"),
                Map.entry("iat", Date.from(NOW.plusSeconds(1))),
                Map.entry("exp", Date.from(NOW.plusSeconds(901))),
                Map.entry("exp", Date.from(NOW.minusSeconds(1))));
        for (var entry : malformed) {
            var claims = baseline();
            claims.put(entry.getKey(), entry.getValue());
            rejects(codecAt(NOW), sign(claims, JWSAlgorithm.RS256, "JWT"), Purpose.ACCESS);
        }
    }

    /**
     * 拒绝不同算法、类型、未签名内容及非配对签名。
     * @throws Exception 测试密钥生成或签名失败
     */
    @Test
    void rejectsWrongAlgorithmTypeAndSignature() throws Exception {
        rejects(codecAt(NOW), sign(baseline(), JWSAlgorithm.RS512, "JWT"), Purpose.ACCESS);
        rejects(codecAt(NOW), sign(baseline(), JWSAlgorithm.RS256, "other"), Purpose.ACCESS);
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair other = generator.generateKeyPair();
        var otherCodec = new RsaTokenAdapterImpl((RSAPublicKey) other.getPublic(),
                (RSAPrivateKey) other.getPrivate(), "stratum", "stratum-api", Clock.fixed(NOW, ZoneOffset.UTC));
        rejects(codecAt(NOW), otherCodec.issue("user-1", SESSION, 0, Purpose.ACCESS), Purpose.ACCESS);
        String token = codecAt(NOW).issue("user-1", SESSION, 0, Purpose.ACCESS);
        rejects(codecAt(NOW), token.substring(0, token.lastIndexOf('.') + 1), Purpose.ACCESS);
        String[] segments = token.split("\\.");
        rejects(codecAt(NOW), segments[0] + ".e30." + segments[2], Purpose.ACCESS);
    }

    /** 无效外部令牌统一401且不携带可能泄漏原始输入的异常原因。 */
    @Test
    void boundsInputAndSanitizesFailures() {
        for (String token : List.of("", " ", "secret-malformed", "x".repeat(16_385))) {
            rejects(codecAt(NOW), token, Purpose.ACCESS);
        }
        rejects(codecAt(NOW), null, Purpose.ACCESS);
        rejects(codecAt(NOW), "invalid", null);
    }

    /** 签发拒绝不可信标识、负版本及缺失会话，不默默生成身份。 */
    @Test
    void rejectsInvalidIssuanceInput() {
        var codec = codecAt(NOW);
        for (String subject : List.of("", " ", "x".repeat(65), "with space", "new\nline")) {
            assertThrows(IllegalArgumentException.class, () -> codec.issue(subject, SESSION, 0, Purpose.ACCESS));
        }
        assertThrows(IllegalArgumentException.class, () -> codec.issue(null, SESSION, 0, Purpose.ACCESS));
        assertThrows(IllegalArgumentException.class, () -> codec.issue("user", null, 0, Purpose.ACCESS));
        assertThrows(IllegalArgumentException.class, () -> codec.issue("user", SESSION, -1, Purpose.ACCESS));
        assertThrows(IllegalArgumentException.class, () -> codec.issue("user", SESSION, 0, null));
    }

    /**
     * 弱密钥与错误公私钥配对在构造时失败。
     * @throws Exception 测试密钥生成失败
     */
    @Test
    void rejectsWeakKeysAndInvalidConfiguration() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        var weak = generator.generateKeyPair();
        assertThrows(IllegalArgumentException.class, () -> new RsaTokenAdapterImpl((RSAPublicKey) weak.getPublic(),
                (RSAPrivateKey) weak.getPrivate(), "stratum", "api", Clock.systemUTC()));
        assertThrows(IllegalArgumentException.class, () -> new RsaTokenAdapterImpl((RSAPublicKey) keys.getPublic(),
                (RSAPrivateKey) weak.getPrivate(), "stratum", "api", Clock.systemUTC()));
        for (String identity : List.of("", " ", "x".repeat(129), "value\n")) {
            assertThrows(IllegalArgumentException.class, () -> new RsaTokenAdapterImpl((RSAPublicKey) keys.getPublic(),
                    (RSAPrivateKey) keys.getPrivate(), identity, "api", Clock.systemUTC()));
        }
    }

    /**
     * 创建给定时间的验证器。
     * @param now 固定时刻
     * @return 测试适配器
     */
    private static RsaTokenAdapterImpl codecAt(Instant now) {
        return new RsaTokenAdapterImpl((RSAPublicKey) keys.getPublic(), (RSAPrivateKey) keys.getPrivate(),
                "stratum", "stratum-api", Clock.fixed(now, ZoneOffset.UTC));
    }

    /**
     * 创建可修改的合法声明，用于单字段破坏测试。
     * @return 本次测试独立声明
     */
    private static Map<String, Object> baseline() {
        return new HashMap<>(new JWTClaimsSet.Builder().subject("user-1").issuer("stratum")
                .audience("stratum-api").issueTime(Date.from(NOW)).expirationTime(Date.from(NOW.plusSeconds(900)))
                .jwtID(UUID.randomUUID().toString()).claim("sid", SESSION.toString()).claim("ver", 0L)
                .claim("token_use", "ACCESS").build().getClaims());
    }

    /**
     * 对任意测试声明签名，验证器不能仅依赖签名成功。
     * @param claims 测试声明
     * @param algorithm 签名算法
     * @param type 头类型
     * @return 令牌原文，仅在内存使用
     * @throws Exception 签名失败
     */
    private static String sign(Map<String, Object> claims, JWSAlgorithm algorithm, String type) throws Exception {
        var jsonClaims = new HashMap<>(claims);
        jsonClaims.replaceAll((name, value) -> value instanceof Date date ? date.toInstant().getEpochSecond() : value);
        var token = new JWSObject(new JWSHeader.Builder(algorithm).type(new JOSEObjectType(type)).build(),
                new Payload(JSONObjectUtils.toJSONString(jsonClaims)));
        token.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
        return token.serialize();
    }

    /**
     * 断言拒绝结果统一且不泄漏输入。
     * @param codec 验证器
     * @param token 令牌输入
     * @param purpose 接受用途
     */
    private static void rejects(RsaTokenAdapterImpl codec, String token, Purpose purpose) {
        var failure = assertThrows(StratumException.class, () -> codec.verify(token, purpose));
        assertEquals(CommonErrorCode.UNAUTHORIZED, failure.getErrorCode());
        assertNull(failure.getCause());
        assertTrue(failure.getDetails().isEmpty());
    }
}
