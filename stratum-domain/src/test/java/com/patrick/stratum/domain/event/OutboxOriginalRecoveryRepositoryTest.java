package com.patrick.stratum.domain.event;

import java.util.OptionalInt;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static com.patrick.stratum.domain.event.OutboxOriginalRecoveryRepository.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

/** 验证原事件恢复请求及回执的领域契约，不替代事务与授权验收。 */
class OutboxOriginalRecoveryRepositoryTest {
    private final UUID requestId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();
    private final UUID operatorId = UUID.randomUUID();

    /** 请求和回执不能缺少请求、原事件或操作人身份。 */
    @Test
    void rejectsMissingRequestAndReceiptIdentities() {
        assertThrows(NullPointerException.class, () -> new OutboxOriginalRecoveryRepository.Request(null, eventId, operatorId, "trace"));
        assertThrows(NullPointerException.class, () -> new OutboxOriginalRecoveryRepository.Request(requestId, null, operatorId, "trace"));
        assertThrows(NullPointerException.class, () -> new OutboxOriginalRecoveryRepository.Request(requestId, eventId, null, "trace"));
        assertThrows(NullPointerException.class, () -> receipt(null, eventId, operatorId, "trace", RECOVERED, OptionalInt.of(0)));
        assertThrows(NullPointerException.class, () -> receipt(requestId, null, operatorId, "trace", RECOVERED, OptionalInt.of(0)));
        assertThrows(NullPointerException.class, () -> receipt(requestId, eventId, null, "trace", RECOVERED, OptionalInt.of(0)));
        assertThrows(NullPointerException.class, () -> receipt(requestId, eventId, operatorId, "trace", null, OptionalInt.of(0)));
        assertThrows(NullPointerException.class, () -> receipt(requestId, eventId, operatorId, "trace", RECOVERED, null));
    }

    /** 请求和回执均拒绝缺失、超长及不安全的关联标识。 */
    @Test
    void rejectsInvalidTraceInRequestAndReceipt() {
        for (String invalid : new String[]{null, "", " ", "unsafe\ntrace", "中文", "a".repeat(65)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new OutboxOriginalRecoveryRepository.Request(requestId, eventId, operatorId, invalid));
            assertThrows(IllegalArgumentException.class,
                    () -> receipt(requestId, eventId, operatorId, invalid, RECOVERED, OptionalInt.of(0)));
        }
        assertDoesNotThrow(() -> new OutboxOriginalRecoveryRepository.Request(requestId, eventId, operatorId, "A".repeat(64)));
        assertDoesNotThrow(() -> receipt(requestId, eventId, operatorId, "A".repeat(64), RECOVERED, OptionalInt.of(0)));
    }

    /** 请求及回执准确保留首次请求、事件、操作人和关联标识。 */
    @Test
    void preservesOriginalRequestAndReceiptIdentity() {
        var request = new OutboxOriginalRecoveryRepository.Request(requestId, eventId, operatorId, "first-trace_1");
        var result = receipt(request.requestId(), request.eventId(), request.operatorId(), request.traceId(), RECOVERED, OptionalInt.of(3));
        assertEquals(requestId, request.requestId());
        assertEquals(eventId, request.eventId());
        assertEquals(operatorId, request.operatorId());
        assertEquals("first-trace_1", request.traceId());
        assertEquals(requestId, result.requestId());
        assertEquals(eventId, result.eventId());
        assertEquals(operatorId, result.operatorId());
        assertEquals("first-trace_1", result.traceId());
        assertEquals(RECOVERED, result.outcome());
        assertEquals(OptionalInt.of(3), result.previousFailures());
    }

    /** 实际恢复须保留非负历史失败次数，允许零和整型上限。 */
    @Test
    void recoveredRequiresNonnegativePreviousFailures() {
        assertThrows(IllegalArgumentException.class,
                () -> receipt(requestId, eventId, operatorId, "trace", RECOVERED, OptionalInt.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> receipt(requestId, eventId, operatorId, "trace", RECOVERED, OptionalInt.of(-1)));
        assertDoesNotThrow(() -> receipt(requestId, eventId, operatorId, "trace", RECOVERED, OptionalInt.of(0)));
        assertDoesNotThrow(() -> receipt(requestId, eventId, operatorId, "trace", RECOVERED, OptionalInt.of(Integer.MAX_VALUE)));
    }

    /** 已消费结果不携带恢复前失败次数，避免臆造一次恢复。 */
    @Test
    void alreadyConsumedRequiresAbsentPreviousFailures() {
        var result = receipt(requestId, eventId, operatorId, "trace", ALREADY_CONSUMED, OptionalInt.empty());
        assertEquals(ALREADY_CONSUMED, result.outcome());
        assertTrue(result.previousFailures().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> receipt(requestId, eventId, operatorId, "trace", ALREADY_CONSUMED, OptionalInt.of(0)));
    }

    /**
     * 构造领域回执以验证字段约束。
     * @param request 请求标识
     * @param event 原事件标识
     * @param operator 首次操作人标识
     * @param trace 首次操作关联标识
     * @param outcome 回执结果
     * @param failures 恢复前累计失败次数
     * @return 恢复回执
     */
    private static OutboxOriginalRecoveryRepository.Receipt receipt(UUID request, UUID event, UUID operator,
            String trace, OutboxOriginalRecoveryRepository.Outcome outcome, OptionalInt failures) {
        return new OutboxOriginalRecoveryRepository.Receipt(request, event, operator, trace, outcome, failures);
    }
}
