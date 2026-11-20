package com.starshow.user.facade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.starshow.api.facade.UserFacade;
import com.starshow.common.constant.RedisKeys;
import com.starshow.user.domain.entity.UserIdentityDO;
import com.starshow.user.mapper.UserIdentityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 用户域 Facade 本地实现：
 * 先读 Redis 正缓存 (starshow:user:verified:{userId}, TTL 1h)，未命中回源 DB；
 * 不缓存负结果，避免用户刚完成实名时出现短时误判。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserFacadeImpl implements UserFacade {

    private static final Duration VERIFIED_CACHE_TTL = Duration.ofHours(1);

    private final UserIdentityMapper userIdentityMapper;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean isRealNameVerified(long userId) {
        String cacheKey = RedisKeys.userVerified(userId);
        try {
            if ("1".equals(stringRedisTemplate.opsForValue().get(cacheKey))) {
                return true;
            }
        } catch (Exception ex) {
            log.warn("Redis read failed when checking userVerified for userId={}, falling back to DB", userId, ex);
        }

        Long count = userIdentityMapper.selectCount(
                new LambdaQueryWrapper<UserIdentityDO>().eq(UserIdentityDO::getUserId, userId)
        );
        boolean verified = count != null && count > 0;
        if (verified) {
            try {
                stringRedisTemplate.opsForValue().set(cacheKey, "1", VERIFIED_CACHE_TTL);
            } catch (Exception ex) {
                log.warn("Redis backfill failed for userVerified userId={}", userId, ex);
            }
        }
        return verified;
    }
}
