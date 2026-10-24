package com.patrick.stratum.query.web;

import java.util.UUID;

import com.patrick.stratum.application.auth.CurrentIdentity;
import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.error.StratumException;
import com.patrick.stratum.common.trace.TraceIdContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证HTTP查询上下文只使用服务器身份，并拒绝缺少身份或trace的调用。 */
class QueryHttpIdentityTest {
    /** 清理本线程测试trace。 */
    @AfterEach
    void cleanUp() { TraceIdContext.clear(); }

    /** 可信账号与trace完整传递，调用后的身份变化不会改变已建立的快照。 */
    @Test
    void propagatesVerifiedIdentityIntoImmutableQueryContext() {
        TraceIdContext.setTraceId("query-identity-test");
        var identity = new CurrentIdentity.Snapshot(UUID.randomUUID(), UUID.randomUUID(), 2);
        var result = QueryHttpParameters.context(() -> identity);
        assertEquals(identity.getName(), result.operatorId().orElseThrow());
        assertEquals("query-identity-test", result.traceId());
        assertTrue(result.permissions().isEmpty());
        TraceIdContext.setTraceId("another-request");
        assertEquals("query-identity-test", result.traceId());
        assertThrows(UnsupportedOperationException.class, () -> result.permissions().add("forged.permission"));
    }

    /** 缺失身份不回退匿名，缺失trace不构造脱离请求链路的业务查询。 */
    @Test
    void refusesMissingTrustedIdentityAndTrace() {
        TraceIdContext.setTraceId("query-identity-test");
        CurrentIdentity missing = () -> { throw new StratumException(CommonErrorCode.UNAUTHORIZED); };
        var failure = assertThrows(StratumException.class, () -> QueryHttpParameters.context(missing));
        assertEquals(CommonErrorCode.UNAUTHORIZED, failure.getErrorCode());
        TraceIdContext.clear();
        assertThrows(IllegalStateException.class, () -> QueryHttpParameters.context(() ->
                new CurrentIdentity.Snapshot(UUID.randomUUID(), UUID.randomUUID(), 0)));
    }
}
