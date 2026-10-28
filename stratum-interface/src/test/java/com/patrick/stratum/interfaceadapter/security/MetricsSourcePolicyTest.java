package com.patrick.stratum.interfaceadapter.security;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证指标直连来源匹配及非法配置失败关闭。 */
class MetricsSourcePolicyTest {
    /** IPv4和IPv6回环匹配对应地址族，外部地址与无效来源被拒绝。 */
    @Test
    void acceptsOnlyConfiguredLiteralNetworks() {
        var policy = policy("127.0.0.0/8", "::1/128", "192.0.2.64/26", "2001:db8:1234::/48");
        assertTrue(policy.allows("127.0.0.1")); assertTrue(policy.allows("127.255.1.2"));
        assertTrue(policy.allows("::1")); assertTrue(policy.allows("192.0.2.127"));
        assertTrue(policy.allows("2001:db8:1234:ffff::1"));
        assertFalse(policy.allows("192.0.2.128")); assertFalse(policy.allows("2001:db8:1235::1"));
        assertFalse(policy.allows("192.0.2.1")); assertFalse(policy.allows("::2"));
        assertFalse(policy.allows(null)); assertFalse(policy.allows("localhost"));
        assertFalse(policy.allows("fe80::1%lo")); assertFalse(policy.allows("[::1]"));
    }

    /** 零前缀只匹配对应地址族，空列表拒绝全部来源。 */
    @Test
    void supportsZeroPrefixesAndEmptyDenyList() {
        var ipv4 = policy("0.0.0.0/0"); var ipv6 = policy("::/0");
        assertTrue(ipv4.allows("203.0.113.4")); assertFalse(ipv4.allows("2001:db8::1"));
        assertTrue(ipv6.allows("2001:db8::1")); assertFalse(ipv6.allows("203.0.113.4"));
        assertFalse(policy().allows("127.0.0.1")); assertFalse(policy().allows("::1"));
    }

    /** 非法前缀、主机名、额外分隔符与区域标识均在启动时拒绝。 */
    @Test
    void refusesInvalidNetworksAndExcessConfiguration() {
        for (String invalid : List.of("localhost/8", "127.0.0.1", "127.0.0.1/33", "::1/129", "::1/-1",
                "127.0.0.1/8/1", "fe80::1%lo/64", "[::1]/128", "127.0.0.1/", "127.0.0.1/0000")) {
            assertThrows(IllegalArgumentException.class, () -> policy(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> policy(""));
        var tooMany = new ArrayList<String>(); for (int index = 0; index < 65; index++) tooMany.add("127.0.0.0/8");
        assertThrows(IllegalArgumentException.class, () -> new MetricsSourcePolicy.Properties(tooMany));
        assertThrows(NullPointerException.class, () -> new MetricsSourcePolicy.Properties(null));
    }

    /**
     * 由固定测试CIDR构造策略。
     * @param cidrs 测试网段
     * @return 来源策略
     */
    private MetricsSourcePolicy policy(String... cidrs) { return new MetricsSourcePolicy(new MetricsSourcePolicy.Properties(List.of(cidrs))); }
}
