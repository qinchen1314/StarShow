package com.starshow.user.security;

import com.starshow.common.auth.TokenBlacklistChecker;
import com.starshow.common.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 基于 Redis 的 JWT 黑名单检查器（实现 starshow-common 的 TokenBlacklistChecker SPI）
 */
@Component
@RequiredArgsConstructor
public class RedisTokenBlacklistChecker implements TokenBlacklistChecker {

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean isBlacklisted(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(RedisKeys.authBlacklist(jti)));
    }
}
