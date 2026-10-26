package com.patrick.stratum.gateway.filter;

import java.net.InetAddress;
import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** 按可信直连IP限制账号、角色、权限及指标管理入口，先于体缓冲执行，不替代ERP管理员鉴权。 */
@Component
@EnableConfigurationProperties(GatewayManagementProperties.class)
public class GatewayManagementSourceFilter implements WebFilter, Ordered {
    private static final List<String> MANAGEMENT_PATHS = List.of("/api/auth/accounts", "/api/auth/roles", "/api/auth/permissions", "/actuator/prometheus");
    private final List<Cidr> allowed;

    /**
     * 启动时一次性解析全部CIDR，只接受IP字面量，不触发DNS查询。
     * @param properties 不可变允许列表
     * @throws IllegalArgumentException CIDR语法或前缀非法
     * @apiNote 示例：通过stratum.gateway.management.allowed-cidrs完整配置允许列表。
     */
    public GatewayManagementSourceFilter(GatewayManagementProperties properties) {
        allowed = properties.allowedCidrs().stream().map(Cidr::parse).toList();
    }

    /** {@inheritDoc} */
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 5; }

    /** {@inheritDoc} */
    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return Mono.defer(() -> {
            String path = normalizedPath(exchange.getRequest().getPath().pathWithinApplication());
            boolean management = MANAGEMENT_PATHS.stream().anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
            if (management) {
                var peer = exchange.getRequest().getRemoteAddress();
                byte[] address = peer == null || peer.isUnresolved() ? null : peer.getAddress().getAddress();
                if (address == null || allowed.stream().noneMatch(cidr -> cidr.contains(address))) {
                    return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
                }
            }
            return chain.filter(exchange);
        });
    }

    /**
     * 以一次解码的路径段及去除矩阵参数后的值判断归属，模糊分隔符和点段直接拒绝。
     * @param path 应用内路径
     * @return 只用于来源判断的规范路径，不改写上游URI
     * @throws ResponseStatusException 路径有模糊分隔符、重复转义或点段
     */
    private String normalizedPath(PathContainer path) {
        var normalized = new StringBuilder();
        for (var element : path.elements()) {
            if (element instanceof PathContainer.PathSegment segment) {
                String value = segment.valueToMatch();
                int parameters = value.indexOf(';'); if (parameters >= 0) value = value.substring(0, parameters);
                if (value.isEmpty()) continue;
                if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || value.indexOf('%') >= 0 || value.equals(".") || value.equals("..")) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                }
                normalized.append('/').append(value);
            }
        }
        return normalized.toString();
    }

    /**
     * 已解析的私有CIDR，不向请求开放修改或解析目标主机。
     * @param network IP字节表示
     * @param bits 前缀长度
     */
    private record Cidr(byte[] network, int bits) {
        /**
         * 解析固定IP字面量与前缀，拒绝主机名、区域标识和额外分隔符。
         * @param value 配置文本
         * @return 已校验的私有CIDR
         * @throws IllegalArgumentException 配置无效
         */
        private static Cidr parse(String value) {
            int slash = value.indexOf('/');
            if (slash < 1 || slash != value.lastIndexOf('/') || value.indexOf('%') >= 0 || value.indexOf('[') >= 0 || value.indexOf(']') >= 0) {
                throw new IllegalArgumentException("管理来源CIDR格式无效");
            }
            String host = value.substring(0, slash); String prefix = value.substring(slash + 1);
            if (!prefix.matches("[0-9]{1,3}")) {
                throw new IllegalArgumentException("管理来源必须是IP字面量CIDR");
            }
            byte[] bytes = InetAddress.ofLiteral(host).getAddress(); int bits = Integer.parseInt(prefix);
            if (bits > bytes.length * 8) throw new IllegalArgumentException("管理来源CIDR前缀无效");
            return new Cidr(bytes, bits);
        }

        /**
         * 仅比较同地址族的前缀，主机位不参与判断。
         * @param address 已解析的真实对端字节
         * @return 是否在本网络中
         */
        private boolean contains(byte[] address) {
            if (address.length != network.length) return false;
            int whole = bits / 8; int remaining = bits % 8;
            for (int index = 0; index < whole; index++) if (address[index] != network[index]) return false;
            return remaining == 0 || ((address[whole] ^ network[whole]) & (255 << (8 - remaining))) == 0;
        }
    }
}
