package com.starshow.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshow.common.auth.AuthHeaders;
import com.starshow.common.trace.TraceIdContext;
import com.starshow.gateway.config.SeckillRateLimitProperties;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SeckillRateLimitGlobalFilterTest {

    @Test
    @SuppressWarnings("unchecked")
    void shouldReturn429B0101WhenUserExceedsSeckillRateLimit() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), anyList())).thenReturn(Flux.just(0L));

        SeckillRateLimitProperties props = new SeckillRateLimitProperties();
        props.setEnabled(true);
        props.setUserLimit(2);
        props.setWindowSeconds(1);

        SeckillRateLimitGlobalFilter filter = new SeckillRateLimitGlobalFilter(redis, props, new ObjectMapper());

        MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/seckill/orders")
                .header(AuthHeaders.X_USER_ID, "10086")
                .header(TraceIdContext.HEADER_TRACE_ID, "trace-rl-429")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean passed = new AtomicBoolean(false);
        GatewayFilterChain chain = ex -> {
            passed.set(true);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertFalse(passed.get(), "Rate-limited request must not reach downstream");
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
        assertEquals("1", exchange.getResponse().getHeaders().getFirst("Retry-After"));
        assertEquals("trace-rl-429", exchange.getResponse().getHeaders().getFirst(TraceIdContext.HEADER_TRACE_ID));
        String body = exchange.getResponse().getBodyAsString().block();
        assertNotNull(body);
        assertTrue(body.contains("\"code\":\"B0101\""), "Body must contain B0101: " + body);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldAllowNonSeckillEndpointsAndAllowedSeckillRequests() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), anyList())).thenReturn(Flux.just(1L));

        SeckillRateLimitProperties props = new SeckillRateLimitProperties();
        props.setEnabled(true);
        props.setUserLimit(5);
        props.setShowLimit(100);

        SeckillRateLimitGlobalFilter filter = new SeckillRateLimitGlobalFilter(redis, props, new ObjectMapper());

        MockServerHttpRequest orderReq = MockServerHttpRequest.post("/api/v1/seckill/orders")
                .header(AuthHeaders.X_USER_ID, "10086")
                .header("X-Show-Id", "10001")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(orderReq);

        AtomicBoolean passed = new AtomicBoolean(false);
        filter.filter(exchange, ex -> {
            passed.set(true);
            return Mono.empty();
        }).block();

        assertTrue(passed.get(), "Allowed request should proceed downstream");

        // GET /api/v1/orders 不走抢票写限流
        MockServerHttpRequest getOrders = MockServerHttpRequest.get("/api/v1/orders")
                .header(AuthHeaders.X_USER_ID, "10086")
                .build();
        MockServerWebExchange getExchange = MockServerWebExchange.from(getOrders);
        AtomicBoolean getPassed = new AtomicBoolean(false);
        filter.filter(getExchange, ex -> {
            getPassed.set(true);
            return Mono.empty();
        }).block();
        assertTrue(getPassed.get());
    }
}
