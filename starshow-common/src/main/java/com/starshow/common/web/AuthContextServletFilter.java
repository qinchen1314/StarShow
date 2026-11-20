package com.starshow.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshow.common.auth.AuthHeaders;
import com.starshow.common.auth.JwtTokenProvider;
import com.starshow.common.auth.LoginUser;
import com.starshow.common.auth.TokenBlacklistChecker;
import com.starshow.common.auth.UserContext;
import com.starshow.common.config.StarShowSecurityProperties;
import com.starshow.common.result.ErrorCode;
import com.starshow.common.result.Result;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Servlet 端统一鉴权上下文过滤器（兼顾分布式网关透传与单进程 boot-all 直调）：
 * 1. 若携带合法的 X-Internal-Gateway-Secret（恒定时间比对），信任网关透传的 X-User-Id / X-User-Jti / X-User-Token-Exp；
 * 2. 否则彻底忽略客户端自带的任何 X-User-Id 等内部头，防止水平越权伪造；
 * 3. 未命中内部密钥但携带 Authorization: Bearer <jwt> 时，若本进程启用了 JwtTokenProvider 则直验 JWT + 黑名单；
 * 4. 请求结束强制 UserContext.clear() 防止线程池复用串联用户上下文。
 */
@Slf4j
public class AuthContextServletFilter extends OncePerRequestFilter implements Ordered {

    private final JwtTokenProvider jwtTokenProvider;
    private final StarShowSecurityProperties securityProperties;
    private final TokenBlacklistChecker blacklistChecker;
    private final ObjectMapper objectMapper;

    public AuthContextServletFilter(JwtTokenProvider jwtTokenProvider,
                                    StarShowSecurityProperties securityProperties,
                                    TokenBlacklistChecker blacklistChecker,
                                    ObjectMapper objectMapper) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.securityProperties = securityProperties;
        this.blacklistChecker = blacklistChecker;
        this.objectMapper = objectMapper;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            if (tryResolveFromTrustedGateway(request)) {
                filterChain.doFilter(request, response);
                return;
            }

            String authHeader = request.getHeader(AuthHeaders.AUTHORIZATION);
            if (authHeader != null && authHeader.startsWith(AuthHeaders.BEARER_PREFIX)) {
                if (jwtTokenProvider == null) {
                    writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHORIZED);
                    return;
                }
                String token = authHeader.substring(AuthHeaders.BEARER_PREFIX.length()).trim();
                if (token.isEmpty()) {
                    writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHORIZED);
                    return;
                }
                JwtTokenProvider.JwtPayload payload;
                try {
                    payload = jwtTokenProvider.parseToken(token);
                } catch (JwtException | IllegalArgumentException ex) {
                    log.debug("JWT verification failed: {}", ex.getMessage());
                    writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHORIZED);
                    return;
                }

                if (blacklistChecker != null) {
                    try {
                        if (blacklistChecker.isBlacklisted(payload.jti())) {
                            writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.TOKEN_REVOKED);
                            return;
                        }
                    } catch (Exception ex) {
                        log.error("Blacklist check failed, failing closed for jti={}", payload.jti(), ex);
                        writeJsonError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, ErrorCode.SYSTEM_ERROR);
                        return;
                    }
                }

                UserContext.set(new LoginUser(payload.userId(), payload.jti(), payload.expireEpochSec()));
            }

            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }

    private boolean tryResolveFromTrustedGateway(HttpServletRequest request) {
        String providedSecret = request.getHeader(AuthHeaders.X_INTERNAL_GATEWAY_SECRET);
        String expectedSecret = securityProperties.getInternalGatewaySecret();
        if (providedSecret == null || expectedSecret == null || expectedSecret.isBlank()) {
            return false;
        }
        boolean matched = MessageDigest.isEqual(
                providedSecret.getBytes(StandardCharsets.UTF_8),
                expectedSecret.getBytes(StandardCharsets.UTF_8)
        );
        if (!matched) {
            return false;
        }

        String userIdHeader = request.getHeader(AuthHeaders.X_USER_ID);
        if (userIdHeader == null || userIdHeader.isBlank()) {
            return true;
        }
        try {
            long userId = Long.parseLong(userIdHeader.trim());
            String jti = request.getHeader(AuthHeaders.X_USER_JTI);
            String expHeader = request.getHeader(AuthHeaders.X_USER_TOKEN_EXP);
            long exp = (expHeader == null || expHeader.isBlank()) ? 0L : Long.parseLong(expHeader.trim());
            UserContext.set(new LoginUser(userId, jti == null ? "" : jti.trim(), exp));
            return true;
        } catch (NumberFormatException ex) {
            log.warn("Invalid gateway forwarded user headers");
            return false;
        }
    }

    private void writeJsonError(HttpServletResponse response, int httpStatus, ErrorCode errorCode) throws IOException {
        response.setStatus(httpStatus);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(errorCode)));
    }
}
