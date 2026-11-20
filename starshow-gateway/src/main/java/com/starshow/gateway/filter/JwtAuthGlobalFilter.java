package com.starshow.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshow.common.auth.AuthHeaders;
import com.starshow.common.auth.JwtTokenProvider;
import com.starshow.common.config.StarShowSecurityProperties;
import com.starshow.common.constant.RedisKeys;
import com.starshow.common.result.ErrorCode;
import com.starshow.common.result.Result;
import com.starshow.common.trace.TraceIdContext;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * 网关全局 JWT 鉴权、黑名单拦截与防请求头伪造过滤器：
 * 1. 每次请求无条件剥离客户端传入的 X-User-Id / X-User-Jti / X-User-Token-Exp / X-Internal-Gateway-Secret；
 * 2. 严格按 (HttpMethod, PathPattern) 匹配公开白名单，防止仅按 Path 误放行写接口；
 * 3. 非白名单强制验签 JWT + 查 Reactive Redis 黑名单（超时 Fail-Closed 返回 503）；
 * 4. 通过后注入 4 个可信内部头并透传下游业务服务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthGlobalFilter implements GlobalFilter, Ordered {

    private static final PathPatternParser PARSER = new PathPatternParser();

    private static final List<WhitelistRule> WHITELIST_RULES = List.of(
            WhitelistRule.of(HttpMethod.POST, "/api/v1/auth/register"),
            WhitelistRule.of(HttpMethod.POST, "/api/v1/auth/login"),
            WhitelistRule.of(HttpMethod.GET, "/api/v1/shows/**"),
            WhitelistRule.of(HttpMethod.GET, "/api/v1/reservations/*/count"),
            WhitelistRule.of(HttpMethod.GET, "/actuator/health"),
            WhitelistRule.of(HttpMethod.GET, "/v3/api-docs/**"),
            WhitelistRule.of(HttpMethod.GET, "/doc.html"),
            WhitelistRule.of(HttpMethod.GET, "/webjars/**")
    );

    private final JwtTokenProvider jwtTokenProvider;
    private final ReactiveStringRedisTemplate reactiveStringRedisTemplate;
    private final StarShowSecurityProperties securityProperties;
    private final ObjectMapper objectMapper;

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1. 无条件剥离任何客户端自带的内部可信头，杜绝水平越权伪造
        ServerHttpRequest sanitizedRequest = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(AuthHeaders.X_USER_ID);
                    headers.remove(AuthHeaders.X_USER_JTI);
                    headers.remove(AuthHeaders.X_USER_TOKEN_EXP);
                    headers.remove(AuthHeaders.X_INTERNAL_GATEWAY_SECRET);
                })
                .build();
        ServerWebExchange sanitizedExchange = exchange.mutate().request(sanitizedRequest).build();

        // 2. 按 (HttpMethod, PathPattern) 双向匹配白名单
        HttpMethod method = sanitizedRequest.getMethod();
        PathContainer path = sanitizedRequest.getPath().pathWithinApplication();
        if (HttpMethod.OPTIONS.equals(method) || isWhitelisted(method, path)) {
            return chain.filter(sanitizedExchange);
        }

        // 3. 提取并校验 Bearer JWT
        String authHeader = sanitizedRequest.getHeaders().getFirst(AuthHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith(AuthHeaders.BEARER_PREFIX)) {
            return writeJsonError(sanitizedExchange, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED);
        }
        String token = authHeader.substring(AuthHeaders.BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            return writeJsonError(sanitizedExchange, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED);
        }

        JwtTokenProvider.JwtPayload payload;
        try {
            payload = jwtTokenProvider.parseToken(token);
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("Gateway JWT validation failed: {}", ex.getMessage());
            return writeJsonError(sanitizedExchange, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED);
        }

        // 4. Reactive Redis 黑名单短超时查询（Fail-Closed）
        long timeoutMs = Math.max(50L, securityProperties.getBlacklistTimeoutMs());
        String blacklistKey = RedisKeys.authBlacklist(payload.jti());

        return reactiveStringRedisTemplate.hasKey(blacklistKey)
                .timeout(Duration.ofMillis(timeoutMs))
                .defaultIfEmpty(Boolean.FALSE)
                .flatMap(blacklisted -> {
                    if (Boolean.TRUE.equals(blacklisted)) {
                        return writeJsonError(sanitizedExchange, HttpStatus.UNAUTHORIZED, ErrorCode.TOKEN_REVOKED);
                    }
                    // 5. 注入可信用户上下文与内部密钥头转发下游
                    ServerHttpRequest trustedRequest = sanitizedRequest.mutate()
                            .headers(headers -> {
                                headers.set(AuthHeaders.X_USER_ID, String.valueOf(payload.userId()));
                                headers.set(AuthHeaders.X_USER_JTI, payload.jti());
                                headers.set(AuthHeaders.X_USER_TOKEN_EXP, String.valueOf(payload.expireEpochSec()));
                                headers.set(AuthHeaders.X_INTERNAL_GATEWAY_SECRET, securityProperties.getInternalGatewaySecret());
                            })
                            .build();
                    return chain.filter(sanitizedExchange.mutate().request(trustedRequest).build());
                })
                .onErrorResume(ex -> {
                    log.error("Gateway Redis blacklist check failed (Fail-Closed), jti={}", payload.jti(), ex);
                    return writeJsonError(sanitizedExchange, HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SYSTEM_ERROR);
                });
    }

    private boolean isWhitelisted(HttpMethod method, PathContainer path) {
        if (method == null) {
            return false;
        }
        for (WhitelistRule rule : WHITELIST_RULES) {
            if (rule.matches(method, path)) {
                return true;
            }
        }
        return false;
    }

    private Mono<Void> writeJsonError(ServerWebExchange exchange, HttpStatus status, ErrorCode errorCode) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String traceId = exchange.getRequest().getHeaders().getFirst(TraceIdContext.HEADER_TRACE_ID);
        Result<Void> body = new Result<>(
                errorCode.getCode(),
                errorCode.getMessage(),
                null,
                traceId,
                System.currentTimeMillis()
        );
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            bytes = ("{\"code\":\"" + errorCode.getCode() + "\",\"message\":\"" + errorCode.getMessage() + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    private record WhitelistRule(HttpMethod method, PathPattern pattern) {
        static WhitelistRule of(HttpMethod method, String pathPattern) {
            return new WhitelistRule(method, PARSER.parse(pathPattern));
        }

        boolean matches(HttpMethod reqMethod, PathContainer path) {
            return this.method.equals(reqMethod) && this.pattern.matches(path);
        }
    }
}
