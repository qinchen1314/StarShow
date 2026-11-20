package com.starshow.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.starshow.common.auth.JwtTokenProvider;
import com.starshow.common.auth.LoginUser;
import com.starshow.common.constant.RedisKeys;
import com.starshow.common.crypto.CryptoUtil;
import com.starshow.common.exception.BizException;
import com.starshow.common.id.GeneSnowflakeIdGenerator;
import com.starshow.common.result.ErrorCode;
import com.starshow.user.domain.entity.UserDO;
import com.starshow.user.dto.LoginRequest;
import com.starshow.user.dto.RegisterRequest;
import com.starshow.user.mapper.UserMapper;
import com.starshow.user.vo.LoginVO;
import com.starshow.user.vo.RegisterVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * 链路一（节点 1）：用户注册、登录签发 JWT 与主动登出（JTI Redis 黑名单）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int USER_STATUS_ACTIVE = 1;

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final GeneSnowflakeIdGenerator idGenerator;
    private final JwtTokenProvider jwtTokenProvider;
    private final StringRedisTemplate stringRedisTemplate;

    public RegisterVO register(RegisterRequest request) {
        String phone = request.getPhone().trim();
        String nickname = (request.getNickname() == null || request.getNickname().isBlank())
                ? CryptoUtil.maskPhone(phone)
                : request.getNickname().trim();

        long userId = idGenerator.nextId();
        UserDO user = UserDO.builder()
                .id(userId)
                .phone(phone)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .nickname(nickname)
                .status(USER_STATUS_ACTIVE)
                .deleted(0)
                .build();
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException ex) {
            throw new BizException(ErrorCode.PHONE_ALREADY_EXISTS);
        }

        log.info("User registered successfully: userId={}", userId);
        return RegisterVO.builder()
                .userId(userId)
                .phoneMasked(CryptoUtil.maskPhone(phone))
                .nickname(nickname)
                .build();
    }

    public LoginVO login(LoginRequest request) {
        String phone = request.getPhone().trim();
        UserDO user = userMapper.selectOne(
                new LambdaQueryWrapper<UserDO>().eq(UserDO::getPhone, phone)
        );
        // 防手机号枚举：账号不存在、被冻结或密码不匹配一律统一返回 LOGIN_FAILED (A0108)
        if (user == null
                || !Integer.valueOf(USER_STATUS_ACTIVE).equals(user.getStatus())
                || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.LOGIN_FAILED);
        }

        JwtTokenProvider.GeneratedToken generated = jwtTokenProvider.generateToken(user.getId());
        log.info("User login succeeded: userId={}, jti={}", user.getId(), generated.jti());
        return LoginVO.builder()
                .userId(user.getId())
                .nickname(user.getNickname())
                .token(generated.token())
                .expireAt(generated.expireEpochSec())
                .build();
    }

    public void logout(LoginUser loginUser) {
        if (loginUser == null || loginUser.jti() == null || loginUser.jti().isBlank()) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        long remainSec = loginUser.expireEpochSec() - Instant.now().getEpochSecond();
        if (remainSec > 0) {
            stringRedisTemplate.opsForValue().set(
                    RedisKeys.authBlacklist(loginUser.jti()),
                    "1",
                    Duration.ofSeconds(remainSec)
            );
            log.info("User logged out and token revoked: userId={}, jti={}, ttlSec={}",
                    loginUser.userId(), loginUser.jti(), remainSec);
        }
    }
}
