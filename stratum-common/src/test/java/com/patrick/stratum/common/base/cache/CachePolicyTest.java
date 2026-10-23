package com.patrick.stratum.common.base.cache;

import java.time.Duration;
import java.util.HashSet;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证空值固定 TTL 及正常值抖动边界，避免抖动产生永久键或负过期。 */
class CachePolicyTest {

    /** 空值无论基础 TTL 和随机源如何变化，始终为 60 秒。 */
    @Test
    void emptyValuesAlwaysLiveForSixtySeconds() {
        var policy = new VersionedCache.Policy(Duration.ofMinutes(5), Duration.ofSeconds(30));
        for (int index = 0; index < 20; index++) {
            assertEquals(Duration.ofSeconds(60), policy.nextTtl(true, new Random(index)));
        }
    }

    /** 固定种子验证抖动分散且始终落在允许区间。 */
    @Test
    void populatedValuesHaveBoundedJitter() {
        var policy = new VersionedCache.Policy(Duration.ofSeconds(100), Duration.ofSeconds(20));
        var values = new HashSet<Duration>();
        var random = new Random(42);
        for (int index = 0; index < 100; index++) {
            Duration ttl = policy.nextTtl(false, random);
            assertTrue(ttl.compareTo(Duration.ofSeconds(80)) >= 0 && ttl.compareTo(Duration.ofSeconds(120)) <= 0);
            values.add(ttl);
        }
        assertTrue(values.size() > 1);
    }

    /** 防止零过期、超长过期或过大抖动进入 Redis。 */
    @Test
    void rejectsInvalidExpiryRanges() {
        assertThrows(IllegalArgumentException.class, () -> new VersionedCache.Policy(Duration.ZERO, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new VersionedCache.Policy(Duration.ofDays(2), Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new VersionedCache.Policy(Duration.ofSeconds(1), Duration.ofSeconds(1)));
    }
}
