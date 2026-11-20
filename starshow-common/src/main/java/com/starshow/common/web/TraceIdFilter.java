package com.starshow.common.web;

import com.starshow.common.trace.TraceIdContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 全链路 TraceId 透传过滤器（最高优先级，请求结束强制清理 MDC 防线程复用污染）
 */
public class TraceIdFilter extends OncePerRequestFilter implements Ordered {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String incomingTraceId = request.getHeader(TraceIdContext.HEADER_TRACE_ID);
        TraceIdContext.set(incomingTraceId);
        String traceId = TraceIdContext.get();
        response.setHeader(TraceIdContext.HEADER_TRACE_ID, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            TraceIdContext.clear();
        }
    }
}
