package com.patrick.stratum.gateway.ratelimit;

import java.net.InetAddress;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import io.lettuce.core.ClientOptions;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** 通过有界Lua状态原子消费直连IP令牌，所有实例共享Redis时钟、容量及策略版本。 */
@Component
@EnableConfigurationProperties(RedisGatewayRateLimiter.Properties.class)
public class RedisGatewayRateLimiter {
    private static final RedisScript<List> SCRIPT = RedisScript.of(new ClassPathResource("redis/gateway-rate-limit.lua"), List.class);
    private final ReactiveStringRedisTemplate redis;
    private final Properties properties;
    private final List<String> keys;
    private final List<String> policy;

    /**
     * 绑定固定四键及不可变策略，单个命名空间中的IP状态数量有硬上界。
     * @param redis 响应式Redis客户端
     * @param properties 已校验配置
     * @apiNote 示例：由Spring装配后调用{@code consume(InetAddress.ofLiteral("127.0.0.1"))}。
     */
    public RedisGatewayRateLimiter(ReactiveStringRedisTemplate redis, Properties properties) {
        this.redis = Objects.requireNonNull(redis, "redis"); this.properties = Objects.requireNonNull(properties, "properties");
        String prefix = "{" + properties.namespace() + "}:rate:";
        keys = List.of(prefix + "tokens", prefix + "times", prefix + "expires", prefix + "policy");
        long ttl = Math.max(60, Math.ceilDiv(2L * properties.burstCapacity(), properties.replenishRate()));
        policy = List.of(Integer.toString(properties.replenishRate()), Integer.toString(properties.burstCapacity()),
                Integer.toString(properties.maximumClients()), Long.toString(ttl),
                "v1:" + properties.replenishRate() + ":" + properties.burstCapacity() + ":" + properties.maximumClients() + ":" + ttl);
    }

    /**
     * 保留官方已有客户端选项，限制待执行命令并在断连时拒绝排队，避免超时取消后积累命令。
     * @return 应用于本独立网关Redis工厂的定制器
     * @apiNote 示例：由Boot自动收集，无需业务代码手工调用；静态工厂避免Redis装配循环依赖。
     */
    @Bean
    public static LettuceClientConfigurationBuilderCustomizer gatewayRedisClientBounds() {
        return builder -> builder.clientOptions(builder.build().getClientOptions().orElseGet(ClientOptions::create).mutate()
                .requestQueueSize(256).disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS).build());
    }

    /**
     * 对已解析的真实IP消耗一个令牌，错误或超时保持失败，不做本地放行。
     * @param client 服务器确认的直连IP，不接受主机名或任意客户端键
     * @return 原子决定；容量不足与配额不足明确区分
     * @throws NullPointerException IP缺失
     * @apiNote 示例：{@code limiter.consume(peer.getAddress()).flatMap(decision -> ...)}，不得在事件线程阻塞。
     */
    public Mono<Decision> consume(InetAddress client) {
        Objects.requireNonNull(client, "client");
        var arguments = new java.util.ArrayList<String>(6); arguments.add(HexFormat.of().formatHex(client.getAddress())); arguments.addAll(policy);
        return redis.execute(SCRIPT, keys, arguments).single().map(this::decision).timeout(properties.commandTimeout());
    }

    /**
     * 严格读取可信脚本的四项整数结果，损坏或策略不一致不转换为允许。
     * @param result 脚本结果
     * @return 限流决定
     * @throws IllegalStateException 结果结构异常或共享策略不一致
     */
    private Decision decision(List<?> result) {
        if (result.size() != 4 || result.stream().anyMatch(value -> !(value instanceof Long))) throw new IllegalStateException("入口限流存储结果无效");
        long allowed = (Long) result.get(0); long remaining = (Long) result.get(1); long retry = (Long) result.get(2); long reason = (Long) result.get(3);
        if (reason == 3) throw new IllegalStateException("入口限流共享策略不一致");
        if (remaining < 0 || remaining > properties.burstCapacity() || retry < 0 || retry > 1000
                || (allowed != 0 && allowed != 1) || reason < 0 || reason > 2
                || (allowed == 1 && (reason != 0 || retry != 0)) || (allowed == 0 && reason == 0)) {
            throw new IllegalStateException("入口限流存储结果无效");
        }
        return new Decision(allowed == 1, remaining, retry, reason == 2);
    }

    /**
     * 原子消费结果，客户端容量不足不能误报为该IP已经耗尽自身配额。
     * @param allowed 是否放行
     * @param remaining 剩余整令牌
     * @param retryAfterMillis 建议等待毫秒
     * @param capacityExhausted 是否新IP槽位耗尽
     */
    public record Decision(boolean allowed, long remaining, long retryAfterMillis, boolean capacityExhausted) { }

    /**
     * 有界共享桶启动配置，相同命名空间的所有实例必须保持策略一致。
     * @param replenishRate 每秒补充令牌
     * @param burstCapacity 最大令牌
     * @param maximumClients 活跃IP槽位上限
     * @param namespace 固定受信命名空间
     * @param commandTimeout 远程命令时限
     */
    @ConfigurationProperties("stratum.gateway.rate-limit")
    public record Properties(@DefaultValue("20") int replenishRate, @DefaultValue("40") int burstCapacity,
            @DefaultValue("4096") int maximumClients, @DefaultValue("stratum-gateway") String namespace,
            @DefaultValue("500ms") Duration commandTimeout) {
        /**
         * 拒绝无限容量、外部控制键及过大命令等待。
         * @param replenishRate 1至10000
         * @param burstCapacity 1至20000
         * @param maximumClients 1至65536
         * @param namespace 小写字母开头、最多40个字母数字连字符
         * @param commandTimeout 50毫秒至2秒
         * @throws IllegalArgumentException 配置超界
         * @throws NullPointerException 命名空间或时限缺失
         * @apiNote 示例：{@code new Properties(20, 40, 4096, "stratum-gateway", Duration.ofMillis(500))}。
         */
        public Properties {
            Objects.requireNonNull(namespace, "namespace"); Objects.requireNonNull(commandTimeout, "commandTimeout");
            if (replenishRate < 1 || replenishRate > 10000 || burstCapacity < 1 || burstCapacity > 20000 || maximumClients < 1 || maximumClients > 65536
                    || !namespace.matches("[a-z][a-z0-9-]{0,39}") || commandTimeout.compareTo(Duration.ofMillis(50)) < 0 || commandTimeout.compareTo(Duration.ofSeconds(2)) > 0) {
                throw new IllegalArgumentException("入口限流配置超界");
            }
        }
    }
}
