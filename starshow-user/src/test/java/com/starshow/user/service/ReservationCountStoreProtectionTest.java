package com.starshow.user.service;

import com.starshow.user.config.ReservationProperties;
import com.starshow.user.mapper.ReservationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 链路一 Phase 1.1 计数回源容量保护单元测试：
 * 1. 同场次 SingleFlight 回源合并（32 并发缓存缺失仅 1 次 MySQL COUNT(*)）；
 * 2. Redis 与 DB 双抖动时优先降级返回未过旧的进程内时间戳快照，过旧则暂隐人数（countAvailable=false）不抛 500。
 */
@ExtendWith(MockitoExtension.class)
class ReservationCountStoreProtectionTest {

    @Mock
    private ReservationMapper reservationMapper;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private HashOperations<String, Object, Object> hashOps;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;

    private ReservationProperties properties;
    private ReservationCountStore countStore;

    @BeforeEach
    void setUp() {
        properties = new ReservationProperties();
        properties.setCountCacheTtlSeconds(30L);
        properties.setCountRebuildLockLeaseSeconds(10L);
        properties.setCountMaxStaleSeconds(60L);
        countStore = new ReservationCountStore(reservationMapper, stringRedisTemplate, redissonClient, properties);
    }

    @Test
    void shouldCoalesceConcurrentCacheMissesIntoSingleDbFallbackViaSingleFlight() throws Exception {
        long showId = 10003L;
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.multiGet(anyString(), anyCollection())).thenReturn(List.of());
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(99L);

        AtomicInteger dbQueries = new AtomicInteger(0);
        when(reservationMapper.selectCount(any())).thenAnswer(inv -> {
            dbQueries.incrementAndGet();
            Thread.sleep(150);
            return 99L;
        });

        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CyclicBarrier barrier = new CyclicBarrier(threads);
            List<Future<ReservationCountStore.CountSnapshot>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return countStore.getCountSnapshot(showId);
                }));
            }
            for (Future<ReservationCountStore.CountSnapshot> f : futures) {
                ReservationCountStore.CountSnapshot snap = f.get(10, TimeUnit.SECONDS);
                assertEquals(99L, snap.count());
                assertFalse(snap.stale());
                assertTrue(snap.countAvailable());
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, dbQueries.get(), "32 concurrent misses for same showId must coalesce into 1 MySQL COUNT(*)");
        assertEquals(1L, countStore.getDbFallbackCount(), "DB fallback metric must record exact 1 fallback");
    }

    @Test
    void shouldDegradeToTimestampedStaleSnapshotAndHideWhenTooOldOnTotalOutage() {
        long showId = 10003L;
        when(stringRedisTemplate.opsForHash()).thenThrow(new RuntimeException("Redis down"));
        when(redissonClient.getLock(anyString())).thenThrow(new RuntimeException("Redisson down"));
        when(reservationMapper.selectCount(any())).thenThrow(new RuntimeException("MySQL transient down"));

        // 1. 存在 10 秒前的有效快照（<= 60s）-> 降级返回 stale=true, countAvailable=true
        long recentTs = System.currentTimeMillis() - 10_000L;
        countStore.seedLocalSnapshotForTest(showId, 128L, recentTs);
        ReservationCountStore.CountSnapshot degraded = countStore.getCountSnapshot(showId);
        assertEquals(128L, degraded.count());
        assertEquals(recentTs, degraded.asOfEpochMs());
        assertTrue(degraded.stale());
        assertTrue(degraded.countAvailable());

        // 2. 快照已超过 60 秒过旧上限 -> 返回 countAvailable=false 暂隐人数，不抛 500
        long expiredTs = System.currentTimeMillis() - 180_000L;
        countStore.seedLocalSnapshotForTest(showId, 128L, expiredTs);
        ReservationCountStore.CountSnapshot hidden = countStore.getCountSnapshot(showId);
        assertNull(hidden.count());
        assertEquals(expiredTs, hidden.asOfEpochMs());
        assertTrue(hidden.stale());
        assertFalse(hidden.countAvailable());
    }
}
