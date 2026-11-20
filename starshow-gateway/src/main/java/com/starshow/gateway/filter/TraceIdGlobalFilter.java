package com.starshow.gateway.filter;

import com.starshow.common.trace.TraceIdContext;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 网关全局 TraceId 过滤器：生成或透传 X-Trace-Id 并确保客户端响应头为单值 X-Trace-Id
 */
@Component
public class TraceIdGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(TraceIdContext.HEADER_TRACE_ID);
        String traceId = (incoming == null || incoming.isBlank())
                ? UUID.randomUUID().toString().replace("-", "")
                : incoming.trim();

        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(TraceIdContext.HEADER_TRACE_ID, traceId);
            return Mono.empty();
        });
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .headers(h -> h.set(TraceIdContext.HEADER_TRACE_ID, traceId))
                .build();
        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }
}
