package com.starshow.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshow.common.auth.AuthHeaders;
import com.starshow.common.constant.RedisKeys;
import com.starshow.common.result.ErrorCode;
import com.starshow.common.result.Result;
import com.starshow.common.trace.TraceIdContext;
import com.starshow.gateway.config.SeckillRateLimitProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * 网关抢票写路径（POST /api/v1/seckill/token、POST /api/v1/seckill/orders）限流过滤器：
 * - 运行在 JwtAuthGlobalFilter 之后，使用可信 X-User-Id 执行用户维度限流；
 * - 若携带 X-Show-Id 请求头或 showId 查询参数，同时执行场次维度限流；
 * - 任一维度超限立即返回 HTTP 429 + B0101 + Retry-After，保护下游订单服务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillRateLimitGlobalFilter implements GlobalFilter, Ordered {

    private static final String HEADER_SHOW_ID = "X-Show-Id";
    private static final String PATH_SECKILL_TOKEN = "/api/v1/seckill/token";
    private static final String PATH_SECKILL_ORDERS = "/api/v1/seckill/orders";

    private static final DefaultRedisScript<Long> FIXED_WINDOW_SCRIPT = createWindowScript();

    private final ReactiveStringRedisTemplate reactiveStringRedisTemplate;
    private final SeckillRateLimitProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!properties.isEnabled()) {
            return chain.filter(exchange);
        }
        ServerHttpRequest request = exchange.getRequest();
        if (!HttpMethod.POST.equals(request.getMethod())) {
            return chain.filter(exchange);
        }
        String path = request.getPath().pathWithinApplication().value();
        String pathTag;
        if (PATH_SECKILL_ORDERS.equals(path)) {
            pathTag = "orders";
        } else if (PATH_SECKILL_TOKEN.equals(path)) {
            pathTag = "token";
        } else {
            return chain.filter(exchange);
        }

        String userIdHeader = request.getHeaders().getFirst(AuthHeaders.X_USER_ID);
        if (userIdHeader == null || userIdHeader.isBlank()) {
            return chain.filter(exchange);
        }
        long userId;
        try {
            userId = Long.parseLong(userIdHeader.trim());
        } catch (NumberFormatException ex) {
            return chain.filter(exchange);
        }

        long windowSec = Math.max(1L, properties.getWindowSeconds());
        long userLimit = Math.max(1L, properties.getUserLimit());
        long timeoutMs = Math.max(50L, properties.getRedisTimeoutMs());

        String userKey = RedisKeys.seckillRateLimitUser(userId, pathTag);
        Mono<Boolean> userAllowed = checkLimit(userKey, userLimit, windowSec, timeoutMs);

        Long showId = resolveShowId(request);
        if (showId != null && showId > 0) {
            long showLimit = Math.max(1L, properties.getShowLimit());
            String showKey = RedisKeys.seckillRateLimitShow(showId);
            return userAllowed.flatMap(ok -> {
                if (!Boolean.TRUE.equals(ok)) {
                    return writeTooManyRequests(exchange, windowSec);
                }
                return checkLimit(showKey, showLimit, windowSec, timeoutMs)
                        .flatMap(showOk -> Boolean.TRUE.equals(showOk)
                                ? chain.filter(exchange)
                                : writeTooManyRequests(exchange, windowSec));
            });
        }

        return userAllowed.flatMap(ok -> Boolean.TRUE.equals(ok)
                ? chain.filter(exchange)
                : writeTooManyRequests(exchange, windowSec));
    }

    private Mono<Boolean> checkLimit(String key, long limit, long windowSec, long timeoutMs) {
        return reactiveStringRedisTemplate
                .execute(FIXED_WINDOW_SCRIPT, List.of(key), List.of(String.valueOf(limit), String.valueOf(windowSec)))
                .next()
                .timeout(Duration.ofMillis(timeoutMs))
                .map(val -> val != null && val == 1L)
                .defaultIfEmpty(Boolean.TRUE)
                .onErrorResume(ex -> {
                    log.warn("Gateway seckill rate-limit Redis check failed, failing open for key={}: {}", key, ex.getMessage());
                    return Mono.just(Boolean.TRUE);
                });
    }

    private Long resolveShowId(ServerHttpRequest request) {
        String raw = request.getHeaders().getFirst(HEADER_SHOW_ID);
        if (raw == null || raw.isBlank()) {
            raw = request.getQueryParams().getFirst("showId");
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Mono<Void> writeTooManyRequests(ServerWebExchange exchange, long retryAfterSec) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set("Retry-After", String.valueOf(retryAfterSec));

        String traceId = exchange.getRequest().getHeaders().getFirst(TraceIdContext.HEADER_TRACE_ID);
        if (traceId != null && !traceId.isBlank()) {
            response.getHeaders().set(TraceIdContext.HEADER_TRACE_ID, traceId);
        }
        Result<Void> body = new Result<>(
                ErrorCode.TOO_MANY_REQUESTS.getCode(),
                ErrorCode.TOO_MANY_REQUESTS.getMessage(),
                null,
                traceId,
                System.currentTimeMillis()
        );
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            bytes = ("{\"code\":\"B0101\",\"message\":\"当前抢票人数过多，请稍后再试\"}").getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    private static DefaultRedisScript<Long> createWindowScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText("""
                local cur = redis.call('INCR', KEYS[1])
                if cur == 1 then
                    redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
                end
                if cur > tonumber(ARGV[1]) then
                    return 0
                end
                return 1
                """);
        return script;
    }
}
