package com.patrick.stratum.starter.integration;

import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

import com.patrick.stratum.domain.event.OutboxConsumptionRepository.Group;
import com.patrick.stratum.infrastructure.messaging.RedisDeadLetterBacklogStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** 验证真实隔离Redis的共享采样、过期和迟到结果屏障。 */
class DeadLetterBacklogStoreIntegrationTest extends MariaDbIntegrationTest {
    private final StringRedisTemplate redis;
    private final String namespace = "it-" + UUID.randomUUID();
    private final RedisDeadLetterBacklogStore first;
    private final RedisDeadLetterBacklogStore second;

    /**
     * 构造两份共享同一隔离命名空间的客户端。
     * @param redis 测试Redis
     */
    @Autowired DeadLetterBacklogStoreIntegrationTest(StringRedisTemplate redis) {
        this.redis = redis; first = new RedisDeadLetterBacklogStore(redis, namespace); second = new RedisDeadLetterBacklogStore(redis, namespace);
    }

    /** 仅清理本用例唯一命名空间的三个固定键。 */
    @AfterEach void cleanup() {
        String prefix = namespace + ":{deadletter-backlog}:";
        redis.delete(List.of(prefix + "generation", prefix + "erp_audit", prefix + "cache_invalidation"));
    }

    /** 两实例共享当前代号，零与不可用独立；新采样立即屏蔽旧值并拒绝迟到写入。 */
    @Test void sharesAndFencesSamples() {
        assertTrue(first.read(Group.ERP_AUDIT).isEmpty());
        var older = first.begin();
        assertTrue(first.record(older, Group.ERP_AUDIT, OptionalLong.of(0), Duration.ofSeconds(3)));
        assertTrue(first.record(older, Group.CACHE_INVALIDATION, OptionalLong.empty(), Duration.ofSeconds(3)));
        assertEquals(0, second.read(Group.ERP_AUDIT).orElseThrow()); assertTrue(second.read(Group.CACHE_INVALIDATION).isEmpty());
        var newer = second.begin();
        assertTrue(first.read(Group.ERP_AUDIT).isEmpty());
        assertTrue(second.record(newer, Group.ERP_AUDIT, OptionalLong.of(7), Duration.ofSeconds(3)));
        assertFalse(first.record(older, Group.ERP_AUDIT, OptionalLong.of(9), Duration.ofSeconds(3)));
        assertEquals(7, first.read(Group.ERP_AUDIT).orElseThrow());
        assertTrue(first.read(Group.CACHE_INVALIDATION).isEmpty());
    }

    /** 有效期由Redis执行，过期后不能返回陈旧值；异常输入不会改变有效快照。 */
    @Test void expiresAndRejectsInvalidInput() {
        var sample = first.begin();
        assertTrue(first.record(sample, Group.ERP_AUDIT, OptionalLong.of(3), Duration.ofMillis(300)));
        assertThrows(IllegalArgumentException.class, () -> first.record(sample, Group.ERP_AUDIT, OptionalLong.of(-1), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> first.record(sample, Group.ERP_AUDIT, OptionalLong.of(1), Duration.ZERO));
        await().atMost(Duration.ofSeconds(3)).until(() -> first.read(Group.ERP_AUDIT).isEmpty());
    }

    /** 采样代号丢失后旧代号不能重建结果，损坏数据也不能显示成有效零值。 */
    @Test void rejectsMissingGenerationAndMalformedValue() {
        var sample = first.begin(); String prefix = namespace + ":{deadletter-backlog}:";
        redis.delete(prefix + "generation");
        assertFalse(first.record(sample, Group.ERP_AUDIT, OptionalLong.of(1), Duration.ofSeconds(1)));
        var current = first.begin(); redis.opsForValue().set(prefix + "erp_audit", current + "|invalid", Duration.ofSeconds(2));
        assertTrue(first.read(Group.ERP_AUDIT).isEmpty());
    }
}
