package com.starshow.user.facade;

import com.starshow.common.constant.RedisKeys;
import com.starshow.user.mapper.UserIdentityMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserFacadeImplTest {

    @Mock
    private UserIdentityMapper userIdentityMapper;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private UserFacadeImpl userFacade;

    @Test
    void shouldHitPositiveRedisCacheWithoutQueryingDb() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeys.userVerified(10001L))).thenReturn("1");

        assertTrue(userFacade.isRealNameVerified(10001L));
        verify(userIdentityMapper, never()).selectCount(any());
    }

    @Test
    void shouldBackfillPositiveCacheWhenDbHasIdentityAndNotCacheNegative() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeys.userVerified(10002L))).thenReturn(null);
        when(userIdentityMapper.selectCount(any())).thenReturn(1L);

        assertTrue(userFacade.isRealNameVerified(10002L));
        verify(valueOperations).set(eq(RedisKeys.userVerified(10002L)), eq("1"), eq(Duration.ofHours(1)));

        // 未实名用户：返回 false 且绝不写入负缓存
        when(valueOperations.get(RedisKeys.userVerified(10003L))).thenReturn(null);
        when(userIdentityMapper.selectCount(any())).thenReturn(0L);

        assertFalse(userFacade.isRealNameVerified(10003L));
        verify(valueOperations, never()).set(eq(RedisKeys.userVerified(10003L)), anyString(), any());
    }
}
