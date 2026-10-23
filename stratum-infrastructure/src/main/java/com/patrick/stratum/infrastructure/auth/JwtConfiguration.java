package com.patrick.stratum.infrastructure.auth;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;

import com.patrick.stratum.domain.auth.TokenCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 外部RSA密钥装配；仅建立Token编解码器，HTTP安全过滤链由后续节点接入。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "stratum.auth.jwt.enabled", havingValue = "true")
@EnableConfigurationProperties(JwtConfiguration.Properties.class)
public class JwtConfiguration {

    /**
     * 从环境绑定的PEM密钥创建编解码器，签发/验证探针确保公私钥可协同工作。
     * @param properties 外部JWT配置，不得记录原文
     * @return 已完成密钥自检的Token端口
     * @throws IllegalStateException 缺失、损坏、弱密钥或配对失败；异常不保留敏感原因
     * @apiNote 由Spring装配；示例：注入{@code TokenCodec}后调用签发方法。
     */
    @Bean
    public TokenCodec tokenCodec(Properties properties) {
        try {
            var factory = KeyFactory.getInstance("RSA");
            var publicKey = (RSAPublicKey) factory.generatePublic(
                    new X509EncodedKeySpec(pem(properties.publicKey(), "PUBLIC KEY")));
            var privateKey = (RSAPrivateKey) factory.generatePrivate(
                    new PKCS8EncodedKeySpec(pem(properties.privateKey(), "PRIVATE KEY")));
            var codec = new RsaTokenAdapterImpl(publicKey, privateKey, properties.issuer(),
                    properties.audience(), Clock.systemUTC());
            String probe = codec.issue("key-check", UUID.randomUUID(), 0, TokenCodec.Purpose.ACCESS);
            codec.verify(probe, TokenCodec.Purpose.ACCESS);
            return codec;
        } catch (GeneralSecurityException | RuntimeException failure) {
            throw new IllegalStateException("JWT配置无效：检查外部RSA PEM密钥、签发者与接收者");
        }
    }

    /**
     * 解析单个有界PEM块，拒绝其他格式与无关内容。
     * @param value PEM文本，允许真实换行
     * @param label 标准块名称
     * @return DER编码
     */
    private static byte[] pem(String value, String label) {
        if (value == null || value.length() > 16_384) { throw new IllegalArgumentException("密钥缺失或过长"); }
        String text = value.strip();
        String begin = "-----BEGIN " + label + "-----";
        String end = "-----END " + label + "-----";
        if (!text.startsWith(begin) || !text.endsWith(end)) { throw new IllegalArgumentException("PEM格式不合法"); }
        String body = text.substring(begin.length(), text.length() - end.length()).replaceAll("[\\r\\n\\t ]", "");
        return Base64.getDecoder().decode(body);
    }

    /**
     * 外部配置值；无默认密钥，toString始终隐藏内容。
     * @param publicKey X.509 PUBLIC KEY PEM
     * @param privateKey PKCS#8 PRIVATE KEY PEM
     * @param issuer 固定签发者
     * @param audience 固定接收者
     */
    @ConfigurationProperties("stratum.auth.jwt")
    public record Properties(String publicKey, String privateKey, String issuer, String audience) {
        /**
         * 防止配置对象意外输出密钥。
         * @return 不含配置值的固定文本
         * @apiNote 示例：日志参数意外引用配置对象时仅得到隐藏提示。
         */
        @Override
        public String toString() { return "JwtProperties[配置内容已隐藏]"; }
    }
}
