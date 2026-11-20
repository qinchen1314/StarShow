package com.starshow.show.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshow.common.constant.RedisKeys;
import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.show.config.ShowMetrics;
import com.starshow.show.config.ShowProperties;
import com.starshow.show.domain.entity.ShowDO;
import com.starshow.show.domain.entity.TicketTierDO;
import com.starshow.show.domain.model.ShowDetailCachePayload;
import com.starshow.show.lua.ShowRedisScripts;
import com.starshow.show.mapper.ShowMapper;
import com.starshow.show.mapper.TicketTierMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * L1 单元测试：Caffeine L1 + Redis L2 逻辑过期、最大陈旧上限拒绝、负缓存 30s、布隆防穿透与冷读超时 503/B9999
 */
@ExtendWith(MockitoExtension.class)
class ShowDetailCacheServiceTest {

    @Mock
    private ShowMapper showMapper;
    @Mock
    private TicketTierMapper ticketTierMapper;
    @Mock
    private ShowCatalogBloomManager bloomManager;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private ShowRedisScripts redisScripts;

    private ObjectMapper objectMapper;
    private ShowProperties properties;
    private ShowMetrics metrics;
    private ShowDetailCacheService cacheService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        properties = new ShowProperties();
        properties.setDetailL2LogicalTtlSeconds(300);
        properties.setDetailL2JitterSeconds(0);
        properties.setDetailMaxStaleSeconds(900);
        properties.setNegativeCacheTtlSeconds(30);
        properties.setColdReadTimeoutMs(80);
        metrics = new ShowMetrics();

        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        cacheService = new ShowDetailCacheService(
                showMapper,
                ticketTierMapper,
                bloomManager,
                providerOf(stringRedisTemplate),
                providerOf(redisScripts),
                objectMapper,
                properties,
                metrics
        );
    }

    @AfterEach
    void tearDown() {
        cacheService.shutdown();
    }

    @Test
    void shouldReturnStaleWithinMaxStaleAndAsyncRebuildOnce() throws Exception {
        long now = System.currentTimeMillis();
        ShowDetailCachePayload expired = ShowDetailCachePayload.builder()
                .showId(10001L)
                .saleConfigVersion(1)
                .title("旧标题快照")
                .refreshedAtEpochMs(now - 400_000L)
                .expireAtEpochMs(now - 10_000L) // 已逻辑过期 10s，在 900s 最大陈旧上限内
                .build();
        AtomicReference<String> redisStore = new AtomicReference<>(objectMapper.writeValueAsString(expired));
        when(valueOps.get(RedisKeys.showDetail(10001L))).thenAnswer(inv -> redisStore.get());
        when(valueOps.setIfAbsent(eq(RedisKeys.showDetailRebuildLock(10001L)), anyString(), any(Duration.class)))
                .thenReturn(true, false, false);
        doAnswer(inv -> {
            redisStore.set(inv.getArgument(1));
            return null;
        }).when(valueOps).set(eq(RedisKeys.showDetail(10001L)), anyString(), any(Duration.class));

        CountDownLatch dbDone = new CountDownLatch(1);
        AtomicInteger dbCalls = new AtomicInteger();
        when(showMapper.selectById(10001L)).thenAnswer(inv -> {
            dbCalls.incrementAndGet();
            try {
                return ShowDO.builder()
                        .id(10001L)
                        .title("重建后最新标题")
                        .saleStartTime(LocalDateTime.now().plusHours(1))
                        .saleEndTime(LocalDateTime.now().plusDays(1))
                        .saleStatus("PREHEATED")
                        .saleConfigVersion(1)
                        .build();
            } finally {
                dbDone.countDown();
            }
        });
        when(ticketTierMapper.selectByShowId(10001L)).thenReturn(List.of(
                TicketTierDO.builder().id(20001L).showId(10001L).tierName("VIP").zoneId("Z1").priceCent(168000L).totalStock(100).build()
        ));

        ShowDetailCachePayload first = cacheService.getDetail(10001L);
        assertEquals("旧标题快照", first.getTitle());
        assertTrue(dbDone.await(3, TimeUnit.SECONDS));

        // 等待异步写回完成
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline && redisStore.get().contains("旧标题快照")) {
            Thread.sleep(20);
        }

        assertEquals(1, dbCalls.get());
        assertEquals(1, metrics.getDetailDbRebuilds());
        ShowDetailCachePayload after = cacheService.getDetail(10001L);
        assertEquals("重建后最新标题", after.getTitle());
    }

    @Test
    void shouldNotServeOverMaxStaleSnapshotAndForceSyncRebuild() throws Exception {
        long now = System.currentTimeMillis();
        ShowDetailCachePayload tooOld = ShowDetailCachePayload.builder()
                .showId(10002L)
                .saleConfigVersion(1)
                .title("超陈旧20分钟快照")
                .refreshedAtEpochMs(now - 2_000_000L)
                .expireAtEpochMs(now - 1_200_000L) // 已过期 1200s > detailMaxStaleSeconds(900s)
                .build();
        when(valueOps.get(RedisKeys.showDetail(10002L))).thenReturn(objectMapper.writeValueAsString(tooOld));
        when(bloomManager.isDefinitelyAbsent(10002L)).thenReturn(false);
        when(showMapper.selectById(10002L)).thenReturn(ShowDO.builder()
                .id(10002L).title("实时DB最新详情").saleStatus("ON_SALE").saleConfigVersion(2)
                .saleStartTime(LocalDateTime.now().minusMinutes(5))
                .saleEndTime(LocalDateTime.now().plusHours(2)).build());
        when(ticketTierMapper.selectByShowId(10002L)).thenReturn(List.of());

        ShowDetailCachePayload fresh = cacheService.getDetail(10002L);
        assertNotNull(fresh);
        assertEquals("实时DB最新详情", fresh.getTitle());
        assertEquals(1, metrics.getDetailDbRebuilds());
    }

    @Test
    void shouldBlockMissingShowByBloomWithZeroSqlAndWriteNegativeCacheOnMiss() {
        when(valueOps.get(RedisKeys.showDetail(888881L))).thenReturn(null);
        when(bloomManager.isDefinitelyAbsent(888881L)).thenReturn(true);

        assertNull(cacheService.getDetail(888881L));
        assertEquals(1, metrics.getDetailBloomRejects());
        verify(showMapper, never()).selectById(888881L);

        // 布隆未拦截但 DB 确实不存在 -> 写 30s 空值缓存，第二次命中 L1 负缓存不查库
        when(valueOps.get(RedisKeys.showDetail(888882L))).thenReturn(null);
        when(bloomManager.isDefinitelyAbsent(888882L)).thenReturn(false);
        when(showMapper.selectById(888882L)).thenReturn(null);

        assertNull(cacheService.getDetail(888882L));
        assertNull(cacheService.getDetail(888882L));
        verify(showMapper, times(1)).selectById(888882L);
        verify(valueOps).set(eq(RedisKeys.showDetail(888882L)), contains("\"notFound\":true"), eq(Duration.ofSeconds(30)));
    }

    @Test
    void shouldReturn503OnColdReadTimeout() throws Exception {
        when(valueOps.get(RedisKeys.showDetail(77777L))).thenReturn(null);
        when(bloomManager.isDefinitelyAbsent(77777L)).thenReturn(false);

        CountDownLatch enteredDb = new CountDownLatch(1);
        CountDownLatch releaseDb = new CountDownLatch(1);
        when(showMapper.selectById(77777L)).thenAnswer(inv -> {
            enteredDb.countDown();
            releaseDb.await(2, TimeUnit.SECONDS);
            return null;
        });

        Thread leader = new Thread(() -> {
            try {
                cacheService.getDetail(77777L);
            } catch (Exception ignored) {
            }
        });
        leader.start();
        assertTrue(enteredDb.await(2, TimeUnit.SECONDS));

        BizException ex = assertThrows(BizException.class, () -> cacheService.getDetail(77777L));
        assertEquals(ErrorCode.SYSTEM_ERROR, ex.getErrorCode());
        assertEquals(503, ex.getHttpStatus());
        releaseDb.countDown();
        leader.join(2000);
    }

    private <T> ObjectProvider<T> providerOf(T instance) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return instance;
            }

            @Override
            public T getIfAvailable() {
                return instance;
            }

            @Override
            public T getIfUnique() {
                return instance;
            }

            @Override
            public T getObject() {
                return instance;
            }
        };
    }
}
