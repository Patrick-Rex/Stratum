package com.patrick.stratum.gateway.filter;

import java.util.List;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 管理入口允许的直连CIDR列表，完整列表替换默认值，不存在动态请求授权来源。
 * @param allowedCidrs 最多64个IPv4/IPv6 CIDR
 */
@ConfigurationProperties("stratum.gateway.management")
public record GatewayManagementProperties(@DefaultValue({"127.0.0.0/8", "::1/128"}) List<String> allowedCidrs) {
    /**
     * 保持配置不可变，空列表表示拒绝所有管理来源。
     * @param allowedCidrs CIDR完整列表，每项最长64字符
     * @throws NullPointerException 列表或元素缺失
     * @throws IllegalArgumentException 列表过大或文本为空/超长
     * @apiNote 示例：{@code new GatewayManagementProperties(List.of("127.0.0.0/8", "::1/128", "192.0.2.0/24"))}。
     */
    public GatewayManagementProperties {
        Objects.requireNonNull(allowedCidrs, "allowedCidrs"); allowedCidrs = List.copyOf(allowedCidrs);
        if (allowedCidrs.size() > 64 || allowedCidrs.stream().anyMatch(value -> value.isEmpty() || value.length() > 64)) {
            throw new IllegalArgumentException("管理来源CIDR数量或文本超界");
        }
    }
}
