package com.patrick.stratum.interfaceadapter.web;

import java.io.IOException;

import com.patrick.stratum.common.trace.TraceIdContext;
import com.patrick.stratum.common.trace.CorrelationIdPolicy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/** HTTP 请求关联标识过滤器，在每次分派内绑定上下文并可靠恢复调用线程。 */
public final class RequestTraceFilter extends OncePerRequestFilter {

    public static final String HEADER = CorrelationIdPolicy.TRACE_HEADER;
    private static final String ATTRIBUTE = RequestTraceFilter.class.getName() + ".traceId";

    /** {@inheritDoc} */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = (String) request.getAttribute(ATTRIBUTE);
        if (traceId == null) {
            String supplied = request.getHeader(HEADER);
            traceId = CorrelationIdPolicy.resolve(supplied);
            request.setAttribute(ATTRIBUTE, traceId);
        }
        String previous = TraceIdContext.getTraceId().orElse(null);
        TraceIdContext.setTraceId(traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            if (previous == null) {
                TraceIdContext.clear();
            } else {
                TraceIdContext.setTraceId(previous);
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    /** {@inheritDoc} */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    /** {@inheritDoc} */
    @Override
    protected void doFilterNestedErrorDispatch(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        doFilterInternal(request, response, chain);
    }
}
