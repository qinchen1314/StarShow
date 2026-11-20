package com.starshow.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.starshow.common.auth.AuthHeaders;
import com.starshow.common.auth.JwtTokenProvider;
import com.starshow.common.config.StarShowSecurityProperties;
import com.starshow.common.result.ErrorCode;
import com.starshow.common.trace.TraceIdContext;
import com.starshow.common.validator.IdCardValidator;
import com.starshow.common.web.AuthContextServletFilter;
import com.starshow.common.web.GlobalExceptionHandler;
import com.starshow.common.web.TraceIdFilter;
import com.starshow.user.service.AuthService;
import com.starshow.user.service.ReservationService;
import com.starshow.user.service.UserIdentityService;
import com.starshow.user.vo.IdentityVO;
import com.starshow.user.vo.LoginVO;
import com.starshow.user.vo.RegisterVO;
import com.starshow.user.vo.ReservationCountVO;
import com.starshow.user.vo.ReservationVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 链路一 Web 协议层端到端 MockMvc 验证：
 * 校验路由契约、@Valid 参数校验、64 位 Long -> String 序列化、TraceId 回写、鉴权过滤器与黑名单踢出闭环。
 */
@ExtendWith(MockitoExtension.class)
class Chain1WebFlowMockMvcTest {

    @Mock
    private AuthService authService;
    @Mock
    private UserIdentityService userIdentityService;
    @Mock
    private ReservationService reservationService;

    private JwtTokenProvider jwtTokenProvider;
    private Set<String> revokedJtis;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        StarShowSecurityProperties props = new StarShowSecurityProperties();
        props.setJwtSecret("webflow-test-jwt-secret-9a8b7c6d5e4f3a2b1c0d");
        props.setInternalGatewaySecret("webflow-test-internal-sec-7f8e9d6c");

        jwtTokenProvider = new JwtTokenProvider(props.getJwtSecret(), props.getJwtExpireSeconds());
        revokedJtis = new HashSet<>();

        ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json()
                .modules(new JavaTimeModule())
                .serializerByType(Long.class, ToStringSerializer.instance)
                .serializerByType(Long.TYPE, ToStringSerializer.instance)
                .serializerByType(BigInteger.class, ToStringSerializer.instance)
                .build();

        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AuthController(authService),
                        new UserIdentityController(userIdentityService),
                        new ReservationController(reservationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .addFilters(
                        new TraceIdFilter(),
                        new AuthContextServletFilter(jwtTokenProvider, props, revokedJtis::contains, objectMapper)
                )
                .build();
    }

    @Test
    void shouldCompleteChain1HttpContractAndSerializeLongIdsAsJsonStrings() throws Exception {
        long userId = 1900000000000123456L; // 超过 JS Number 安全精度 (2^53-1)，验证必须输出为 String
        long reservationId = 1900000000000999888L;
        JwtTokenProvider.GeneratedToken generated = jwtTokenProvider.generateToken(userId);

        when(authService.register(any())).thenReturn(RegisterVO.builder()
                .userId(userId)
                .phoneMasked("138****8000")
                .nickname("StarFan")
                .build());
        when(authService.login(any())).thenReturn(LoginVO.builder()
                .userId(userId)
                .nickname("StarFan")
                .token(generated.token())
                .expireAt(generated.expireEpochSec())
                .build());
        when(userIdentityService.verifyIdentity(eq(userId), any())).thenReturn(IdentityVO.builder()
                .verified(true)
                .idCardMasked("3101**********001X")
                .verifiedTime(LocalDateTime.of(2026, 11, 20, 18, 0))
                .build());
        when(reservationService.reserve(userId, 10003L)).thenReturn(ReservationVO.builder()
                .reservationId(reservationId)
                .showId(10003L)
                .currentCount(1L)
                .remindScheduled(true)
                .build());
        when(reservationService.getReservationCount(10003L)).thenReturn(ReservationCountVO.builder()
                .showId(10003L)
                .count(1L)
                .build());
        doAnswer(inv -> {
            revokedJtis.add(generated.jti());
            return null;
        }).when(authService).logout(any());

        // 1. 注册：验证 64 位 userId 以 String 返回
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800138000\",\"password\":\"Pass123456\",\"nickname\":\"StarFan\"}"))
                .andExpect(status().isOk())
                .andExpect(header().exists(TraceIdContext.HEADER_TRACE_ID))
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.userId", is(String.valueOf(userId))));

        // 2. 非法参数校验：短密码返回 400 + A0103
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800138000\",\"password\":\"123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAM.getCode()));

        // 3. 登录
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800138000\",\"password\":\"Pass123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").value(generated.token()));

        // 4. 实名认证（携带 Bearer JWT）
        String validIdCard = buildSampleIdCard();
        mockMvc.perform(post("/api/v1/users/identity")
                        .header(AuthHeaders.AUTHORIZATION, AuthHeaders.BEARER_PREFIX + generated.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"realName\":\"张星演\",\"idCard\":\"" + validIdCard + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verified").value(true))
                .andExpect(jsonPath("$.data.idCardMasked").value("3101**********001X"));

        // 5. 预约与公开查询人数
        mockMvc.perform(post("/api/v1/reservations")
                        .header(AuthHeaders.AUTHORIZATION, AuthHeaders.BEARER_PREFIX + generated.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"showId\":10003}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reservationId", is(String.valueOf(reservationId))))
                .andExpect(jsonPath("$.data.remindScheduled").value(true));

        mockMvc.perform(get("/api/v1/reservations/10003/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count", is("1")));

        // 6. 主动登出 -> 复用旧 JWT 请求实名查询必须返 401 + A0102
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header(AuthHeaders.AUTHORIZATION, AuthHeaders.BEARER_PREFIX + generated.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));

        mockMvc.perform(get("/api/v1/users/identity")
                        .header(AuthHeaders.AUTHORIZATION, AuthHeaders.BEARER_PREFIX + generated.token()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.TOKEN_REVOKED.getCode()));
    }

    private String buildSampleIdCard() {
        String base = "31010119950505001";
        return base + IdCardValidator.computeCheckDigit(base);
    }
}
