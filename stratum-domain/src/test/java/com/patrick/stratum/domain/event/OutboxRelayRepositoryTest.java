package com.patrick.stratum.domain.event;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.Optional;

import com.patrick.stratum.common.trace.StandardTraceContext.Carrier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证Relay请求/领取证据与退避算法，不把纯契约测试计为实际领取。 */
class OutboxRelayRepositoryTest {
    /** 批次、租期和毫秒精度必须有界。 */
    @Test
    void rejectsUnboundedOrInvalidClaimRequests() {
        for (int size : new int[]{0, 129}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new OutboxRelayRepository.ClaimRequest(size, Duration.ofSeconds(30)));
        }
        for (var duration : new Duration[]{Duration.ZERO, Duration.ofMillis(999),
                Duration.ofMinutes(11), Duration.ofSeconds(1).plusNanos(1)}) {
            assertThrows(IllegalArgumentException.class, () -> new OutboxRelayRepository.ClaimRequest(1, duration));
        }
        assertDoesNotThrow(() -> new OutboxRelayRepository.ClaimRequest(128, Duration.ofMinutes(10)));
    }

    /** 证据可以表示过去的租期，但不能缺失身份或输出正文/令牌。 */
    @Test
    void preservesPersistedClaimWithoutLeakingTokenOrPayload() {
        var delivery = new OutboxPublisher.Delivery(new OutboxMessage(UUID.randomUUID().toString(),
                "erp.material.created", "aggregate", Instant.EPOCH, "{\"secret\":\"value\"}", "relay-trace"), 1);
        UUID token = UUID.randomUUID();
        var claim = new OutboxRelayRepository.Claim(delivery, token, Instant.EPOCH, 0);
        assertSame(delivery, claim.delivery());
        assertTrue(claim.standardTrace().isEmpty());
        assertEquals(Instant.EPOCH, claim.expiresAt());
        assertEquals(token, claim.token());
        assertFalse(claim.toString().contains(token.toString()));
        assertFalse(claim.toString().contains("secret"));
        assertThrows(IllegalArgumentException.class, () -> new OutboxRelayRepository.Claim(delivery, token, Instant.EPOCH, -1));
        assertThrows(NullPointerException.class, () -> new OutboxRelayRepository.Claim(null, token, Instant.EPOCH, 0));
        assertThrows(NullPointerException.class, () -> new OutboxRelayRepository.Claim(delivery, null, Instant.EPOCH, 0));
    }

    /** 标准上下文只属于领取证据，不更改原事件或投递对象；摘要不泄露追踪状态。 */
    @Test
    void preservesOptionalStandardContextWithoutChangingDelivery() {
        var message = new OutboxMessage(UUID.randomUUID().toString(), "erp.material.created", "aggregate", Instant.EPOCH, "{}", "business-id");
        var delivery = new OutboxPublisher.Delivery(message, 3);
        var trace = new Carrier("00-1234567890abcdef1234567890abcdef-1234567890abcdef-01", "tenant=value");
        var claim = new OutboxRelayRepository.Claim(delivery, UUID.randomUUID(), Instant.EPOCH, 0, Optional.of(trace));
        assertSame(delivery, claim.delivery()); assertSame(message, claim.delivery().message());
        assertEquals(trace, claim.standardTrace().orElseThrow());
        assertFalse(claim.toString().contains("tenant=value")); assertFalse(claim.toString().contains(trace.traceparent()));
        assertThrows(NullPointerException.class, () -> new OutboxRelayRepository.Claim(delivery, UUID.randomUUID(), Instant.EPOCH, 0, null));
    }

    /** 前几次失败按指数增长，封顶后对极大次数仍保持有界且不溢出。 */
    @Test
    void calculatesCappedExponentialBackoffWithoutOverflow() {
        var policy = new OutboxRelayRepository.RetryPolicy(5, Duration.ofMillis(100), Duration.ofMillis(500));
        assertEquals(Duration.ofMillis(100), policy.delayForFailure(1));
        assertEquals(Duration.ofMillis(200), policy.delayForFailure(2));
        assertEquals(Duration.ofMillis(400), policy.delayForFailure(3));
        assertEquals(Duration.ofMillis(500), policy.delayForFailure(4));
        assertEquals(Duration.ofMillis(500), policy.delayForFailure(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> policy.delayForFailure(0));
        assertThrows(IllegalArgumentException.class, () -> policy.delayForFailure(-1));
    }

    /** 非法次数、初始延迟、上限及精度在进入存储前拒绝。 */
    @Test
    void rejectsInvalidRetryPolicy() {
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxRelayRepository.RetryPolicy(0, Duration.ofSeconds(1), Duration.ofSeconds(2)));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxRelayRepository.RetryPolicy(101, Duration.ofSeconds(1), Duration.ofSeconds(2)));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxRelayRepository.RetryPolicy(1, Duration.ofMillis(99), Duration.ofSeconds(2)));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxRelayRepository.RetryPolicy(1, Duration.ofSeconds(2), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxRelayRepository.RetryPolicy(1, Duration.ofSeconds(1), Duration.ofHours(2)));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxRelayRepository.RetryPolicy(1, Duration.ofSeconds(1).plusNanos(1), Duration.ofSeconds(2)));
    }
}
