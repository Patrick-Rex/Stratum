package com.patrick.stratum.interfaceadapter.security;

import java.net.InetAddress;
import java.util.List;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 指标入口直连IP来源策略；只处理字面地址，不读取请求头或执行DNS查询。 */
public final class MetricsSourcePolicy {
    private final List<Cidr> allowed;

    /**
     * 在启动时解析完整允许列表，不接受主机名或格式错误的网段。
     * @param properties 不可变来源配置
     * @throws IllegalArgumentException 存在无效CIDR
     * @throws NullPointerException 配置为空
     * @apiNote 示例：{@code new MetricsSourcePolicy(new Properties(List.of("127.0.0.0/8", "::1/128")))}。
     */
    public MetricsSourcePolicy(Properties properties) {
        allowed = Objects.requireNonNull(properties, "指标来源配置不能为空").allowedCidrs().stream().map(Cidr::parse).toList();
    }

    /**
     * 只对Servlet容器提供的直连地址判定，不接收Forwarded类请求头。
     * @param remoteAddress 直连IP字面量；缺失或格式错误时拒绝
     * @return 地址属于允许网段时为true
     * @apiNote 示例：{@code policy.allows("127.0.0.1")}。
     */
    public boolean allows(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.indexOf('%') >= 0 || remoteAddress.indexOf('[') >= 0 || remoteAddress.indexOf(']') >= 0) return false;
        try {
            byte[] address = InetAddress.ofLiteral(remoteAddress).getAddress();
            return allowed.stream().anyMatch(cidr -> cidr.contains(address));
        } catch (IllegalArgumentException failure) { return false; }
    }

    /**
     * 指标允许来源完整列表；空列表表示全部拒绝。
     * @param allowedCidrs 至多64个IPv4或IPv6 CIDR
     */
    @ConfigurationProperties("stratum.management.metrics")
    public record Properties(@DefaultValue({"127.0.0.0/8", "::1/128"}) List<String> allowedCidrs) {
        /**
         * 保持完整配置不可变，拒绝空值和超界项目。
         * @param allowedCidrs 完整来源列表，每项最长64字符
         * @throws NullPointerException 列表或项目为空
         * @throws IllegalArgumentException 列表数量或项目长度无效
         * @apiNote 示例：{@code new Properties(List.of("127.0.0.0/8", "::1/128"))}。
         */
        public Properties {
            allowedCidrs = List.copyOf(Objects.requireNonNull(allowedCidrs, "指标来源不能为空"));
            if (allowedCidrs.size() > 64 || allowedCidrs.stream().anyMatch(value -> value.isEmpty() || value.length() > 64)) {
                throw new IllegalArgumentException("指标来源CIDR数量或长度无效");
            }
        }
    }

    /**
     * 私有不可变网段，主机位不参与前缀比较。
     * @param network 网络地址字节
     * @param bits 前缀位数
     */
    private record Cidr(byte[] network, int bits) {
        /**
         * 解析字面地址及十进制前缀，拒绝DNS、区域标识和多余分隔符。
         * @param value 配置CIDR
         * @return 已验证网段
         * @throws IllegalArgumentException 地址或前缀无效
         */
        private static Cidr parse(String value) {
            int slash = value.indexOf('/');
            if (slash < 1 || slash != value.lastIndexOf('/') || value.indexOf('%') >= 0 || value.indexOf('[') >= 0 || value.indexOf(']') >= 0) {
                throw new IllegalArgumentException("指标来源CIDR格式无效");
            }
            String prefix = value.substring(slash + 1);
            if (!prefix.matches("[0-9]{1,3}")) throw new IllegalArgumentException("指标来源前缀无效");
            byte[] address = InetAddress.ofLiteral(value.substring(0, slash)).getAddress();
            int bits = Integer.parseInt(prefix);
            if (bits > address.length * 8) throw new IllegalArgumentException("指标来源前缀越界");
            return new Cidr(address, bits);
        }

        /**
         * 同地址族按位比较前缀，不匹配的地址族直接拒绝。
         * @param address 已解析对端地址
         * @return 是否属于网段
         */
        private boolean contains(byte[] address) {
            if (address.length != network.length) return false;
            int whole = bits / 8; int remaining = bits % 8;
            for (int index = 0; index < whole; index++) if (address[index] != network[index]) return false;
            return remaining == 0 || ((address[whole] ^ network[whole]) & (255 << (8 - remaining))) == 0;
        }
    }
}
