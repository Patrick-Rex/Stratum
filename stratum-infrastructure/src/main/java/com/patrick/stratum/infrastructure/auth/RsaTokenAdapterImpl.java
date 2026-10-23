package com.patrick.stratum.infrastructure.auth;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.SignedJWT;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.domain.auth.TokenCodec;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

/** RS256签发及严格校验适配器；使用外部RSA密钥，不访问网络或保存会话。 */
public final class RsaTokenAdapterImpl implements TokenCodec {
    private static final int MAX_TOKEN_LENGTH = 16_384;
    private static final Pattern SUBJECT = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private final NimbusJwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    private final Clock clock;
    private final String issuer;
    private final String audience;

    /**
     * 建立固定RS256编解码器，不生成默认密钥。
     * @param publicKey RSA公钥，2048至8192位
     * @param privateKey 同模数RSA私钥
     * @param issuer 预期签发者，非空且至多128字符
     * @param audience 预期接收者，非空且至多128字符
     * @param clock 校验与签发共用时钟
     * @throws IllegalArgumentException 密钥或标识非法
     * @apiNote 示例：{@code new RsaTokenAdapterImpl(publicKey, privateKey, issuer, audience, Clock.systemUTC())}。
     */
    public RsaTokenAdapterImpl(RSAPublicKey publicKey, RSAPrivateKey privateKey,
            String issuer, String audience, Clock clock) {
        Objects.requireNonNull(publicKey, "RSA公钥不能为空");
        Objects.requireNonNull(privateKey, "RSA私钥不能为空");
        int bits = publicKey.getModulus().bitLength();
        if (bits < 2048 || bits > 8192 || !publicKey.getModulus().equals(privateKey.getModulus())) {
            throw new IllegalArgumentException("RSA密钥长度或配对不合法");
        }
        this.issuer = identity(issuer);
        this.audience = identity(audience);
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
        var key = new RSAKey.Builder(publicKey).privateKey(privateKey).build();
        encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        decoder = NimbusJwtDecoder.withPublicKey(publicKey).signatureAlgorithm(SignatureAlgorithm.RS256).build();
        var timestamp = new JwtTimestampValidator(Duration.ZERO);
        timestamp.setClock(clock);
        decoder.setJwtValidator(timestamp);
    }

    /** {@inheritDoc} */
    @Override
    public String issue(String subject, UUID sessionId, long version, Purpose purpose) {
        if (subject == null || !SUBJECT.matcher(subject).matches() || sessionId == null
                || version < 0 || purpose == null) {
            throw new IllegalArgumentException("令牌签发参数不合法");
        }
        Instant issued = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        var claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience)).subject(subject)
                .issuedAt(issued).expiresAt(issued.plus(purpose.lifetime())).id(UUID.randomUUID().toString())
                .claim("sid", sessionId.toString()).claim("ver", version).claim("token_use", purpose.name()).build();
        var header = JwsHeader.with(SignatureAlgorithm.RS256).type("JWT").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** {@inheritDoc} */
    @Override
    public Claims verify(String token, Purpose purpose) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH || purpose == null) {
            throw new StratumException(CommonErrorCode.UNAUTHORIZED);
        }
        try {
            requireClaimTypes(token);
            Jwt jwt = decoder.decode(token);
            String subject = jwt.getSubject();
            Object version = jwt.getClaim("ver");
            Instant issued = jwt.getIssuedAt();
            Instant expires = jwt.getExpiresAt();
            Instant now = clock.instant();
            if (!issuer.equals(jwt.getClaim("iss")) || !List.of(audience).equals(jwt.getAudience())
                    || !purpose.name().equals(jwt.getClaim("token_use"))
                    || !"JWT".equals(jwt.getHeaders().get("typ"))
                    || subject == null || !SUBJECT.matcher(subject).matches()
                    || !(version instanceof Long || version instanceof Integer)
                    || ((Number) version).longValue() < 0
                    || issued == null || expires == null || issued.isAfter(now) || !expires.isAfter(now)
                    || issued.getNano() != 0 || expires.getNano() != 0
                    || !expires.equals(issued.plus(purpose.lifetime()))) {
                throw new IllegalArgumentException("令牌声明不合法");
            }
            return new Claims(subject, identifier(jwt.getId()), identifier(jwt.getClaim("sid")),
                    ((Number) version).longValue(), issued, expires, purpose);
        } catch (JwtException | IllegalArgumentException | ClassCastException | ParseException failure) {
            // 不保留解码异常的cause或消息，避免日志及HTTP错误泄漏原始令牌。
            throw new StratumException(CommonErrorCode.UNAUTHORIZED);
        }
    }

    /**
     * 在库自动转换标准声明前校验JSON类型；此步骤不建立信任，随后仍须验证签名。
     * @param token 已限制长度的令牌
     * @throws ParseException 非法JWS结构
     */
    private static void requireClaimTypes(String token) throws ParseException {
        var claims = SignedJWT.parse(token).getPayload().toJSONObject();
        if (claims == null) { throw new IllegalArgumentException("令牌声明不合法"); }
        for (String field : List.of("sub", "iss", "jti", "sid", "token_use")) {
            if (!(claims.get(field) instanceof String)) {
                throw new IllegalArgumentException("令牌声明类型不合法");
            }
        }
        for (String field : List.of("iat", "exp", "ver")) {
            Object value = claims.get(field);
            if (!(value instanceof Long || value instanceof Integer)) {
                throw new IllegalArgumentException("令牌声明类型不合法");
            }
        }
    }

    /**
     * 校验规范UUID字符串，拒绝JDK允许的缩短分段写法。
     * @param value 待验证标识
     * @return 标准UUID
     */
    private static UUID identifier(String value) {
        if (value == null || value.length() != 36) { throw new IllegalArgumentException("标识不合法"); }
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value)) { throw new IllegalArgumentException("标识不合法"); }
        return id;
    }

    /**
     * 校验外部配置标识的大小与字符，避免空值和日志控制字符。
     * @param value 签发者或接收者
     * @return 原始配置值
     */
    private static String identity(String value) {
        if (value == null || value.isBlank() || value.length() > 128
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("签发者或接收者配置不合法");
        }
        return value;
    }
}
