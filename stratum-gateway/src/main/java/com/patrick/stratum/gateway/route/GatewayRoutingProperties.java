package com.patrick.stratum.gateway.route;

import java.net.URI;
import java.util.List;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * 独立网关的固定上游与灰度配置，不接收请求指定的目标地址。
 * @param enabled 是否启用ERP转发
 * @param upstream 稳定上游根地址
 * @param paths 最多64个有界路径模式
 * @param grayEnabled 是否启用Header灰度
 * @param grayUpstream 可选灰度上游根地址
 * @param grayHeader 灰度Header名称
 * @param grayValue 精确灰度值
 * @param host 可选精确入口域名，不包含端口
 */
@ConfigurationProperties("stratum.gateway.routing")
public record GatewayRoutingProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("http://127.0.0.1:8080") URI upstream,
        @DefaultValue({"/api/**", "/auth/**", "/v3/api-docs/**", "/doc.html", "/webjars/**", "/swagger-ui/**"}) List<String> paths,
        @DefaultValue("false") boolean grayEnabled, URI grayUpstream,
        @DefaultValue("X-Stratum-Release") String grayHeader, @DefaultValue("gray") String grayValue,
        @DefaultValue("") String host) {
    /** 路径模式容量上限，不随请求或角色数量增长。 */
    public static final int MAX_PATHS = 64;

    /**
     * 冻结并校验启动配置，非法上游、模式或灰度参数直接拒绝。
     * @param enabled 是否转发
     * @param upstream 稳定上游
     * @param paths 有界路径模式
     * @param grayEnabled 是否灰度
     * @param grayUpstream 可选灰度上游
     * @param grayHeader Header名称
     * @param grayValue 精确值
     * @param host 可选入口域名
     * @throws IllegalArgumentException 配置非法或灰度启用但缺失目标
     * @apiNote 示例：{@code new GatewayRoutingProperties(true, URI.create("http://127.0.0.1:8080"), List.of("/api/**"), false, null, "X-Stratum-Release", "gray", "")}。
     */
    public GatewayRoutingProperties {
        requireUpstream(Objects.requireNonNull(upstream, "必须配置稳定上游"));
        paths = List.copyOf(Objects.requireNonNull(paths, "路径模式不能为空"));
        if (paths.isEmpty() || paths.size() > MAX_PATHS) throw new IllegalArgumentException("路径模式容量必须在1至64之间");
        for (String path : paths) {
            if (path.length() > 512 || !path.startsWith("/") || path.contains("#") || path.contains("\\")
                    || path.chars().anyMatch(value -> value <= 32 || value == 127)) throw new IllegalArgumentException("路径模式格式无效");
            PathPatternParser.defaultInstance.parse(path);
        }
        if (grayUpstream != null && grayUpstream.toString().isBlank()) grayUpstream = null;
        if (grayUpstream != null) requireUpstream(grayUpstream);
        if (grayEnabled && grayUpstream == null) throw new IllegalArgumentException("启用灰度必须配置固定灰度上游");
        if (grayHeader == null || !grayHeader.matches("[A-Za-z][A-Za-z0-9-]{0,63}")
                || grayValue == null || !grayValue.matches("[A-Za-z0-9._-]{1,64}")) throw new IllegalArgumentException("灰度Header配置无效");
        host = host == null ? "" : host;
        if (!host.isEmpty() && (host.length() > 253 || !host.matches("[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?"))) {
            throw new IllegalArgumentException("入口域名格式无效");
        }
    }

    /**
     * 仅允许无凭据、查询或片段的固定HTTP(S)根地址，不解析请求提供的目标。
     * @param target 运行配置中的地址
     * @throws IllegalArgumentException 地址不满足固定上游约束
     */
    private static void requireUpstream(URI target) {
        String scheme = target.getScheme(); String path = target.getRawPath();
        if (target.toASCIIString().length() > 2048 || scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || target.getHost() == null || target.getRawUserInfo() != null || target.getRawQuery() != null || target.getRawFragment() != null
                || (path != null && !path.isEmpty() && !path.equals("/")) || target.getPort() == 0 || target.getPort() > 65535) {
            throw new IllegalArgumentException("上游必须为无凭据且不含查询、片段或前缀路径的HTTP(S)根地址");
        }
    }
}
