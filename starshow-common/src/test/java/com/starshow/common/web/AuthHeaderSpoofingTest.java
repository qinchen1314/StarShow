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
import com.starshow.common.trace.TraceIdContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthHeaderSpoofingTest {

    private static final String INTERNAL_SECRET = "spoof-test-internal-sec-7f8e9d6c5b4a";
    private JwtTokenProvider jwtTokenProvider;
    private Set<String> blacklistedJtis;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        StarShowSecurityProperties props = new StarShowSecurityProperties();
        props.setJwtSecret("spoof-test-jwt-secret-key-9f8e7d6c5b4a3f2e1d0c");
        props.setInternalGatewaySecret(INTERNAL_SECRET);

        jwtTokenProvider = new JwtTokenProvider(props.getJwtSecret(), props.getJwtExpireSeconds());
        blacklistedJtis = new HashSet<>();
        TokenBlacklistChecker checker = blacklistedJtis::contains;

        TraceIdFilter traceIdFilter = new TraceIdFilter();
        AuthContextServletFilter authFilter = new AuthContextServletFilter(
                jwtTokenProvider, props, checker, new ObjectMapper()
        );

        mockMvc = MockMvcBuilders.standaloneSetup(new ProtectedEchoController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(traceIdFilter, authFilter)
                .build();
    }

    @Test
    void shouldRejectSpoofedXUserIdWithoutInternalSecretOrJwt() throws Exception {
        mockMvc.perform(get("/test/protected")
                        .header(AuthHeaders.X_USER_ID, "999")
                        .header(AuthHeaders.X_USER_JTI, "spoofed-jti"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(TraceIdContext.HEADER_TRACE_ID))
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));

        assertNull(UserContext.get());
        assertNull(TraceIdContext.get());
    }

    @Test
    void shouldRejectSpoofedXUserIdWithWrongInternalSecret() throws Exception {
        mockMvc.perform(get("/test/protected")
                        .header(AuthHeaders.X_USER_ID, "999")
                        .header(AuthHeaders.X_INTERNAL_GATEWAY_SECRET, "wrong-secret"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));
    }

    @Test
    void shouldTrustHeadersWhenInternalGatewaySecretMatches() throws Exception {
        mockMvc.perform(get("/test/protected")
                        .header(AuthHeaders.X_INTERNAL_GATEWAY_SECRET, INTERNAL_SECRET)
                        .header(AuthHeaders.X_USER_ID, "10086")
                        .header(AuthHeaders.X_USER_JTI, "gw-jti-1")
                        .header(AuthHeaders.X_USER_TOKEN_EXP, "1999999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data").value("10086:gw-jti-1:1999999999"));

        assertNull(UserContext.get());
    }

    @Test
    void shouldVerifyBearerJwtDirectlyInMonolithModeAndEnforceBlacklist() throws Exception {
        JwtTokenProvider.GeneratedToken generated = jwtTokenProvider.generateToken(20001L);

        // 正常 JWT 通过
        mockMvc.perform(get("/test/protected")
                        .header(AuthHeaders.AUTHORIZATION, AuthHeaders.BEARER_PREFIX + generated.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data").value("20001:" + generated.jti() + ":" + generated.expireEpochSec()));

        // 拉黑 jti 后立刻返回 401 + A0102
        blacklistedJtis.add(generated.jti());
        mockMvc.perform(get("/test/protected")
                        .header(AuthHeaders.AUTHORIZATION, AuthHeaders.BEARER_PREFIX + generated.token()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.TOKEN_REVOKED.getCode()));
    }

    @RestController
    static class ProtectedEchoController {
        @GetMapping("/test/protected")
        public Result<String> echo() {
            LoginUser user = UserContext.requireLoginUser();
            return Result.ok(user.userId() + ":" + user.jti() + ":" + user.expireEpochSec());
        }
    }
}
