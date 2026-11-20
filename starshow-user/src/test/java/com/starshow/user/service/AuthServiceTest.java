package com.starshow.user.service;

import com.starshow.common.auth.JwtTokenProvider;
import com.starshow.common.auth.LoginUser;
import com.starshow.common.constant.RedisKeys;
import com.starshow.common.exception.BizException;
import com.starshow.common.id.GeneSnowflakeIdGenerator;
import com.starshow.common.result.ErrorCode;
import com.starshow.user.domain.entity.UserDO;
import com.starshow.user.dto.LoginRequest;
import com.starshow.user.dto.RegisterRequest;
import com.starshow.user.mapper.UserMapper;
import com.starshow.user.vo.LoginVO;
import com.starshow.user.vo.RegisterVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private PasswordEncoder passwordEncoder;
    private JwtTokenProvider jwtTokenProvider;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder(4); // 单测用低 cost 加速，生产 Bean 为 cost=10
        jwtTokenProvider = new JwtTokenProvider("auth-svc-test-jwt-secret-9a8b7c6d5e4f3a2b1c0d", 3600);
        GeneSnowflakeIdGenerator idGenerator = new GeneSnowflakeIdGenerator(1);
        authService = new AuthService(userMapper, passwordEncoder, idGenerator, jwtTokenProvider, stringRedisTemplate);
    }

    @Test
    void shouldRegisterWithBcryptHashAndRejectDuplicatePhone() {
        RegisterRequest req = new RegisterRequest();
        req.setPhone("13800138000");
        req.setPassword("Pass123456");

        RegisterVO vo = authService.register(req);
        assertNotNull(vo.getUserId());
        assertEquals("138****8000", vo.getPhoneMasked());
        assertEquals("138****8000", vo.getNickname());

        ArgumentCaptor<UserDO> captor = ArgumentCaptor.forClass(UserDO.class);
        verify(userMapper).insert(captor.capture());
        UserDO inserted = captor.getValue();
        assertNotEquals("Pass123456", inserted.getPasswordHash());
        assertTrue(passwordEncoder.matches("Pass123456", inserted.getPasswordHash()));

        // 并发或重复手机号撞 uk_phone -> A0107
        when(userMapper.insert(any(UserDO.class))).thenThrow(new DuplicateKeyException("Duplicate entry for key 'uk_phone'"));
        BizException ex = assertThrows(BizException.class, () -> authService.register(req));
        assertEquals(ErrorCode.PHONE_ALREADY_EXISTS, ex.getErrorCode());
    }

    @Test
    void shouldLoginAndPreventPhoneEnumeration() {
        UserDO activeUser = UserDO.builder()
                .id(10001L)
                .phone("13900139000")
                .passwordHash(passwordEncoder.encode("SecretPass123"))
                .nickname("StarFan")
                .status(1)
                .build();
        when(userMapper.selectOne(any())).thenReturn(activeUser);

        LoginRequest okReq = new LoginRequest();
        okReq.setPhone("13900139000");
        okReq.setPassword("SecretPass123");
        LoginVO loginVO = authService.login(okReq);
        assertEquals(10001L, loginVO.getUserId());
        assertEquals("StarFan", loginVO.getNickname());
        assertEquals(10001L, jwtTokenProvider.parseToken(loginVO.getToken()).userId());

        // 密码错误 -> A0108
        LoginRequest wrongPass = new LoginRequest();
        wrongPass.setPhone("13900139000");
        wrongPass.setPassword("WrongPass123");
        BizException wrongEx = assertThrows(BizException.class, () -> authService.login(wrongPass));
        assertEquals(ErrorCode.LOGIN_FAILED, wrongEx.getErrorCode());

        // 用户不存在 -> 同样返回 A0108（防账号枚举）
        when(userMapper.selectOne(any())).thenReturn(null);
        BizException missingEx = assertThrows(BizException.class, () -> authService.login(okReq));
        assertEquals(ErrorCode.LOGIN_FAILED, missingEx.getErrorCode());
    }

    @Test
    void shouldWriteJtiToBlacklistWithRemainingTtlOnLogout() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        long futureExp = Instant.now().getEpochSecond() + 1200;
        LoginUser user = new LoginUser(10001L, "jti-abc-123", futureExp);

        authService.logout(user);

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(eq(RedisKeys.authBlacklist("jti-abc-123")), eq("1"), ttlCaptor.capture());
        assertTrue(ttlCaptor.getValue().getSeconds() > 1100 && ttlCaptor.getValue().getSeconds() <= 1200);
    }
}
