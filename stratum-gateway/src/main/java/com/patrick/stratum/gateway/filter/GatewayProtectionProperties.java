package com.patrick.stratum.gateway.filter;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 请求资源上界，启动时校验总缓冲预算，不允许以负值关闭防护。
 * @param maximumBodyBytes 每请求体字节上界
 * @param maximumHeaderBytes 应用头部字节预算
 * @param maximumHeaderCount 头字段数量上界
 * @param maximumBufferedRequests 同时持有体缓冲的请求上界
 * @param bodyTimeout 完整读取请求体的总等待上界
 */
@ConfigurationProperties("stratum.gateway.protection")
public record GatewayProtectionProperties(@DefaultValue("1048576") int maximumBodyBytes,
        @DefaultValue("16384") int maximumHeaderBytes, @DefaultValue("100") int maximumHeaderCount,
        @DefaultValue("32") int maximumBufferedRequests, @DefaultValue("5s") Duration bodyTimeout) {
    /**
     * 限制每体1KiB至8MiB、头1至64KiB、字段8至256、并发1至128及总体预算64MiB。
     * @param maximumBodyBytes 请求体字节上界
     * @param maximumHeaderBytes 头字节预算
     * @param maximumHeaderCount 头字段上界
     * @param maximumBufferedRequests 缓冲并发上界
     * @param bodyTimeout 50毫秒至30秒
     * @throws IllegalArgumentException 配置超界
     * @throws NullPointerException 超时缺失
     * @apiNote 示例：{@code new GatewayProtectionProperties(1048576, 16384, 100, 32, Duration.ofSeconds(5))}。
     */
    public GatewayProtectionProperties {
        Objects.requireNonNull(bodyTimeout, "bodyTimeout");
        if (maximumBodyBytes < 1024 || maximumBodyBytes > 8388608 || maximumHeaderBytes < 1024 || maximumHeaderBytes > 65536
                || maximumHeaderCount < 8 || maximumHeaderCount > 256 || maximumBufferedRequests < 1 || maximumBufferedRequests > 128
                || (long) maximumBodyBytes * maximumBufferedRequests > 67108864
                || bodyTimeout.compareTo(Duration.ofMillis(50)) < 0 || bodyTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("请求防护配置超界");
        }
    }
}
