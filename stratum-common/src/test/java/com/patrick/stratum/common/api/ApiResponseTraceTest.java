package com.patrick.stratum.common.api;

import java.util.ArrayList;
import java.util.List;

import com.patrick.stratum.common.error.CommonErrorCode;
import com.patrick.stratum.common.trace.TraceIdContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 显式关联失败工厂的协议、不可变集合和线程状态契约验收，保持原工厂行为兼容。 */
class ApiResponseTraceTest {
    /** 显式失败保持标准五字段语义，数据为空且明细不可从外部修改。 */
    @Test
    void createsExplicitFailureWithImmutableDetails() {
        var details = new ArrayList<>(List.of("安全明细"));
        var response = ApiResponse.failure(CommonErrorCode.INTERNAL_ERROR, "上游暂不可用", details, "gateway-trace");
        details.clear(); assertEquals(50000, response.getCode()); assertEquals("上游暂不可用", response.getMessage());
        assertNull(response.getData()); assertEquals("gateway-trace", response.getTraceId()); assertEquals(List.of("安全明细"), response.getDetails());
        assertThrows(UnsupportedOperationException.class, () -> response.getDetails().add("不允许"));
        var defaults = ApiResponse.failure(CommonErrorCode.BAD_REQUEST, " ", null, "explicit-trace");
        assertEquals(CommonErrorCode.BAD_REQUEST.getDefaultMessage(), defaults.getMessage()); assertTrue(defaults.getDetails().isEmpty());
    }

    /** 显式trace不读取或建立线程上下文，旧工厂仍使用原线程关联值。 */
    @Test
    void avoidsThreadStateAndRetainsLegacyFactories() {
        var previous = TraceIdContext.getTraceId(); TraceIdContext.clear();
        try {
            assertEquals("independent", ApiResponse.failure(CommonErrorCode.INTERNAL_ERROR, null, List.of(), "independent").getTraceId());
            assertTrue(TraceIdContext.getTraceId().isEmpty()); TraceIdContext.setTraceId("thread-trace");
            assertEquals("explicit", ApiResponse.failure(CommonErrorCode.INTERNAL_ERROR, null, List.of(), "explicit").getTraceId());
            assertEquals("thread-trace", TraceIdContext.getTraceId().orElseThrow());
            assertEquals("thread-trace", ApiResponse.failure(CommonErrorCode.INTERNAL_ERROR, List.of()).getTraceId());
            assertEquals("thread-trace", ApiResponse.failure(CommonErrorCode.INTERNAL_ERROR, "既有文案", List.of()).getTraceId());
            assertEquals("thread-trace", ApiResponse.success("成功").getTraceId());
        } finally { if (previous.isPresent()) TraceIdContext.setTraceId(previous.orElseThrow()); else TraceIdContext.clear(); }
    }

    /** 错误码和显式关联输入必须有效，不自动回退到线程或生成隐式关联。 */
    @Test
    void rejectsInvalidExplicitTraceAndMissingErrorCode() {
        assertThrows(IllegalArgumentException.class, () -> ApiResponse.failure(null, null, List.of(), "trace"));
        assertThrows(IllegalArgumentException.class, () -> ApiResponse.failure(CommonErrorCode.INTERNAL_ERROR, null, List.of(), null));
        for (String trace : List.of("", " ", "invalid trace", "a".repeat(65), "bad\r\nvalue")) {
            assertThrows(IllegalArgumentException.class, () -> ApiResponse.failure(CommonErrorCode.INTERNAL_ERROR, null, List.of(), trace));
        }
    }
}
