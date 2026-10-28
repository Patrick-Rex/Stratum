package com.patrick.stratum.interfaceadapter.web;

import java.io.IOException;
import java.util.UUID;

import com.patrick.stratum.common.api.ApiResponse;
import com.patrick.stratum.common.trace.TraceIdContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

/** 验证请求关联标识的输入边界、请求隔离和分派生命周期。 */
class RequestTraceFilterTest {

    private final RequestTraceFilter filter = new RequestTraceFilter();

    /** 清理测试调用线程，避免断言失败影响后续用例。 */
    @AfterEach
    void clearContext() {
        TraceIdContext.clear();
        MDC.remove(RequestTraceFilter.BUSINESS_TRACE_MDC_KEY);
        MDC.remove("trace_id");
    }

    /** 合法头值同时进入响应头与已有统一响应体。
     * @throws Exception 过滤器执行失败
     */
    @Test
    void propagatesSafeTraceToHeaderAndBody() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(RequestTraceFilter.HEADER, "erp.trace-01");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            assertEquals("erp.trace-01", ApiResponse.success("ok").getTraceId());
            assertEquals("erp.trace-01", MDC.get(RequestTraceFilter.BUSINESS_TRACE_MDC_KEY));
            assertEquals("erp.trace-01", response.getHeader(RequestTraceFilter.HEADER));
        });
        assertTrue(TraceIdContext.getTraceId().isEmpty());
        assertNull(MDC.get(RequestTraceFilter.BUSINESS_TRACE_MDC_KEY));
    }

    /** 缺失、超长或含控制字符的头值生成新的安全标识。
     * @throws Exception 过滤器执行失败
     */
    @Test
    void replacesMissingOrUnsafeValues() throws Exception {
        for (String supplied : new String[]{null, "", "x".repeat(65), "bad\r\nvalue", "中文"}) {
            var request = new MockHttpServletRequest();
            if (supplied != null) request.addHeader(RequestTraceFilter.HEADER, supplied);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> {});
            assertNotNull(UUID.fromString(response.getHeader(RequestTraceFilter.HEADER)));
            assertTrue(TraceIdContext.getTraceId().isEmpty());
        }
    }

    /** 异常传播后恢复外层上下文，不残留请求的标识。 */
    @Test
    void restoresOuterContextAfterFailure() {
        TraceIdContext.setTraceId("outer");
        MDC.put(RequestTraceFilter.BUSINESS_TRACE_MDC_KEY, "outer-mdc");
        MDC.put("trace_id", "existing-standard-trace");
        var request = new MockHttpServletRequest();
        request.addHeader(RequestTraceFilter.HEADER, "inner");
        assertThrows(IOException.class, () -> filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> { throw new IOException("test"); }));
        assertEquals("outer", TraceIdContext.getRequiredTraceId());
        assertEquals("outer-mdc", MDC.get(RequestTraceFilter.BUSINESS_TRACE_MDC_KEY));
        assertEquals("existing-standard-trace", MDC.get("trace_id"));
        MDC.remove("trace_id");
        MDC.remove(RequestTraceFilter.BUSINESS_TRACE_MDC_KEY);
        TraceIdContext.clear();
        assertThrows(IOException.class, () -> filter.doFilter(new MockHttpServletRequest(),
                new MockHttpServletResponse(), (req, res) -> { throw new IOException("test"); }));
        assertTrue(TraceIdContext.getTraceId().isEmpty());
        assertNull(MDC.get(RequestTraceFilter.BUSINESS_TRACE_MDC_KEY));
    }

    /** 响应头写入失败时也要恢复业务日志上下文，避免复用线程残留。 */
    @Test
    void restoresContextWhenResponseHeaderFails() {
        var response = new MockHttpServletResponse() {
            /** {@inheritDoc} */
            @Override public void setHeader(String name, String value) { throw new IllegalStateException("test"); }
        };
        assertThrows(IllegalStateException.class, () -> filter.doFilter(new MockHttpServletRequest(), response, (req, res) -> {}));
        assertTrue(TraceIdContext.getTraceId().isEmpty());
        assertNull(MDC.get(RequestTraceFilter.BUSINESS_TRACE_MDC_KEY));
    }

    /** 异步及错误分派重用请求属性，并在各次分派结束后清理线程。
     * @throws Exception 过滤器执行失败
     */
    @Test
    void reusesTraceAcrossAsyncAndErrorDispatch() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {});
        String original = response.getHeader(RequestTraceFilter.HEADER);
        request.addHeader(RequestTraceFilter.HEADER, "changed");
        for (DispatcherType type : new DispatcherType[]{DispatcherType.ASYNC, DispatcherType.ERROR}) {
            request.setDispatcherType(type);
            if (type == DispatcherType.ERROR) request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/test");
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                assertEquals(original, TraceIdContext.getRequiredTraceId());
                assertEquals(original, MDC.get(RequestTraceFilter.BUSINESS_TRACE_MDC_KEY));
            });
            assertTrue(TraceIdContext.getTraceId().isEmpty());
            assertNull(MDC.get(RequestTraceFilter.BUSINESS_TRACE_MDC_KEY));
        }
    }

    /** 同步链内嵌套的错误分派继续使用原请求标识。
     * @throws Exception 过滤器执行失败
     */
    @Test
    void supportsNestedErrorDispatch() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            String original = TraceIdContext.getRequiredTraceId();
            request.setDispatcherType(DispatcherType.ERROR);
            request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/test");
            filter.doFilter(request, response, (nestedReq, nestedRes) -> {
                assertEquals(original, TraceIdContext.getRequiredTraceId());
                assertEquals(original, MDC.get(RequestTraceFilter.BUSINESS_TRACE_MDC_KEY));
            });
            assertEquals(original, TraceIdContext.getRequiredTraceId());
            assertEquals(original, MDC.get(RequestTraceFilter.BUSINESS_TRACE_MDC_KEY));
        });
        assertTrue(TraceIdContext.getTraceId().isEmpty());
    }

    /** 同一线程处理不同请求时不会复用上次生成的标识。
     * @throws Exception 过滤器执行失败
     */
    @Test
    void isolatesSuccessiveRequests() throws Exception {
        var first = new MockHttpServletResponse();
        var second = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), first, (req, res) -> {});
        filter.doFilter(new MockHttpServletRequest(), second, (req, res) -> {});
        assertNotEquals(first.getHeader(RequestTraceFilter.HEADER), second.getHeader(RequestTraceFilter.HEADER));
    }
}
