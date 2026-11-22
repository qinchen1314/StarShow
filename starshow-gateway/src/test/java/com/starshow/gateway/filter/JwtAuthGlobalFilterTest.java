package com.starshow.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshow.common.auth.AuthHeaders;
import com.starshow.common.auth.JwtTokenProvider;
import com.starshow.common.config.StarShowSecurityProperties;
import com.starshow.common.constant.RedisKeys;
import com.starshow.common.result.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtAuthGlobalFilterTest {

    private static final String INTERNAL_SECRET = "gw-filter-internal-sec-9a8b7c6d5e4f";

    @Mock
    private ReactiveStringRedisTemplate reactiveStringRedisTemplate;

    private JwtTokenProvider jwtTokenProvider;
    private JwtAuthGlobalFilter filter;

    @BeforeEach
    void setUp() {
        StarShowSecurityProperties props = new StarShowSecurityProperties();
        props.setJwtSecret("gw-filter-jwt-secret-key-9a8b7c6d5e4f3a2b1c0d");
        props.setInternalGatewaySecret(INTERNAL_SECRET);
        props.setBlacklistTimeoutMs(500L);

        jwtTokenProvider = new JwtTokenProvider(props.getJwtSecret(), props.getJwtExpireSeconds());
        filter = new JwtAuthGlobalFilter(jwtTokenProvider, reactiveStringRedisTemplate, props, new ObjectMapper());
    }

    @Test
    void shouldAllowWhitelistedMethodPathAndStripSpoofedHeaders() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/reservations/10003/count")
                .header(AuthHeaders.X_USER_ID, "999")
                .header(AuthHeaders.X_INTERNAL_GATEWAY_SECRET, "fake-secret")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            forwarded.set(ex);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertNotNull(forwarded.get(), "GET /api/v1/reservations/{id}/count must pass whitelist");
        assertNull(forwarded.get().getRequest().getHeaders().getFirst(AuthHeaders.X_USER_ID));
        assertNull(forwarded.get().getRequest().getHeaders().getFirst(AuthHeaders.X_INTERNAL_GATEWAY_SECRET));
    }

    @Test
    void shouldBlockPostReservationEvenThoughGetCountIsWhitelisted() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/reservations")
                .header(AuthHeaders.X_USER_ID, "999")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = ex -> {
            fail("Unauthenticated POST /api/v1/reservations must NOT reach downstream");
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        String body = exchange.getResponse().getBodyAsString().block();
        assertNotNull(body);
        assertTrue(body.contains(ErrorCode.UNAUTHORIZED.getCode()));
    }

    @Test
    void shouldForwardFourTrustedHeadersWhenJwtValidAndNotBlacklisted() {
        JwtTokenProvider.GeneratedToken token = jwtTokenProvider.generateToken(88001L);
        when(reactiveStringRedisTemplate.hasKey(RedisKeys.authBlacklist(token.jti())))
                .thenReturn(Mono.just(Boolean.FALSE));

        MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/users/identity")
                .header(AuthHeaders.AUTHORIZATION, AuthHeaders.BEARER_PREFIX + token.token())
                .header(AuthHeaders.X_USER_ID, "999") // 客户端企图伪造其他用户
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            forwarded.set(ex);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertNotNull(forwarded.get());
        var headers = forwarded.get().getRequest().getHeaders();
        assertEquals("88001", headers.getFirst(AuthHeaders.X_USER_ID));
        assertEquals(token.jti(), headers.getFirst(AuthHeaders.X_USER_JTI));
        assertEquals(String.valueOf(token.expireEpochSec()), headers.getFirst(AuthHeaders.X_USER_TOKEN_EXP));
        assertEquals(INTERNAL_SECRET, headers.getFirst(AuthHeaders.X_INTERNAL_GATEWAY_SECRET));
    }

    @Test
    void shouldRejectRevokedTokenWith401AndA0102() {
        JwtTokenProvider.GeneratedToken token = jwtTokenProvider.generateToken(88001L);
        when(reactiveStringRedisTemplate.hasKey(RedisKeys.authBlacklist(token.jti())))
                .thenReturn(Mono.just(Boolean.TRUE));

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/users/identity")
                .header(AuthHeaders.AUTHORIZATION, AuthHeaders.BEARER_PREFIX + token.token())
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, ex -> {
            fail("Blacklisted token must not reach downstream");
            return Mono.empty();
        }).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        String body = exchange.getResponse().getBodyAsString().block();
        assertNotNull(body);
        assertTrue(body.contains(ErrorCode.TOKEN_REVOKED.getCode()));
    }

    @Test
    void shouldFailClosedWith503WhenRedisBlacklistCheckErrors() {
        JwtTokenProvider.GeneratedToken token = jwtTokenProvider.generateToken(88001L);
        when(reactiveStringRedisTemplate.hasKey(RedisKeys.authBlacklist(token.jti())))
                .thenReturn(Mono.error(new RuntimeException("Redis connection refused")));

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/users/identity")
                .header(AuthHeaders.AUTHORIZATION, AuthHeaders.BEARER_PREFIX + token.token())
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, ex -> {
            fail("Redis error must fail-closed");
            return Mono.empty();
        }).block();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        String body = exchange.getResponse().getBodyAsString().block();
        assertNotNull(body);
        assertTrue(body.contains(ErrorCode.SYSTEM_ERROR.getCode()));
    }

    @Test
    void waitingRoomReadIsPrivateAndQueueCredentialCannotJoin() {
        String queueUrl = "/api/v1/shows/15001/queue";
        var without = MockServerWebExchange.from(MockServerHttpRequest.get(queueUrl + "/me").build());
        filter.filter(without, ex -> { throw new AssertionError("Unauthenticated queue read"); }).block();
        assertEquals(HttpStatus.UNAUTHORIZED, without.getResponse().getStatusCode());
        assertEquals("no-store", without.getResponse().getHeaders().getCacheControl());

        var read = MockServerWebExchange.from(MockServerHttpRequest.get(queueUrl + "/me")
                .header(AuthHeaders.X_QUEUE_READ_TOKEN, "fixture-read-only")
                .header(AuthHeaders.X_USER_ID, "999").build());
        filter.filter(read, ex -> {
            assertNull(ex.getRequest().getHeaders().getFirst(AuthHeaders.X_USER_ID));
            assertEquals("fixture-read-only", ex.getRequest().getHeaders().getFirst(AuthHeaders.X_QUEUE_READ_TOKEN));
            return Mono.empty();
        }).block();
        assertEquals("no-store", read.getResponse().getHeaders().getCacheControl());

        var join = MockServerWebExchange.from(MockServerHttpRequest.post(queueUrl)
                .header(AuthHeaders.X_QUEUE_READ_TOKEN, "fixture-read-only").build());
        filter.filter(join, ex -> { throw new AssertionError("Queue credential attempted to join"); }).block();
        assertEquals(HttpStatus.UNAUTHORIZED, join.getResponse().getStatusCode());
        verifyNoInteractions(reactiveStringRedisTemplate);
    }

    @Test
    void queueReadWithAnIdentityStillChecksTheJwtBlacklist() {
        var token = jwtTokenProvider.generateToken(88001);
        when(reactiveStringRedisTemplate.hasKey(RedisKeys.authBlacklist(token.jti()))).thenReturn(Mono.just(true));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/shows/15001/queue/me")
                .header(AuthHeaders.X_QUEUE_READ_TOKEN, "fixture-read-only")
                .header(AuthHeaders.AUTHORIZATION, AuthHeaders.BEARER_PREFIX + token.token()).build());

        filter.filter(exchange, ex -> { throw new AssertionError("Revoked JWT reached waiting room"); }).block();
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        verify(reactiveStringRedisTemplate).hasKey(RedisKeys.authBlacklist(token.jti()));
    }
}
