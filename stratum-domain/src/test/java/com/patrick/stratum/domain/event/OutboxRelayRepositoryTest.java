package com.patrick.stratum.domain.event;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

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
        assertEquals(Instant.EPOCH, claim.expiresAt());
        assertEquals(token, claim.token());
        assertFalse(claim.toString().contains(token.toString()));
        assertFalse(claim.toString().contains("secret"));
        assertThrows(IllegalArgumentException.class, () -> new OutboxRelayRepository.Claim(delivery, token, Instant.EPOCH, -1));
        assertThrows(NullPointerException.class, () -> new OutboxRelayRepository.Claim(null, token, Instant.EPOCH, 0));
        assertThrows(NullPointerException.class, () -> new OutboxRelayRepository.Claim(delivery, null, Instant.EPOCH, 0));
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
