package com.starshow.show.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
import com.starshow.show.util.ShowTimeUtil;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 链路二（节点 0）：演出详情 Caffeine L1 + Redis L2 逻辑过期多级缓存服务
 * - L1：Caffeine（默认 60s TTL，最大 5000 条）保护热点静态展示字段；
 * - L2：Redis 逻辑过期 JSON（默认 300s + 0~300s 抖动），允许在 detailMaxStaleSeconds（默认 900s）内返回旧值，
 *       同时仅由拿到分布式互斥锁的唯一任务在有界线程池内 Double-Check 并异步回源 MySQL 重建；
 * - 防穿透：布隆目录已就绪时先过滤确定不存在的 showId，回源确认不存在写 30s 空值缓存；
 * - 冷 miss 与故障降级：SingleFlight 合并并发冷读 + 有界等待（默认 150ms，超时 503/B9999）；
 *       Redis 异常时优先使用未超陈旧上限的进程内兜底快照，否则受并发信号量保护同步回源。
 */
@Slf4j
@Service
public class ShowDetailCacheService {

    private final ShowMapper showMapper;
    private final TicketTierMapper ticketTierMapper;
    private final ShowCatalogBloomManager bloomManager;
    private final ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider;
    private final ObjectProvider<ShowRedisScripts> redisScriptsProvider;
    private final ObjectMapper objectMapper;
    private final ShowProperties properties;
    private final ShowMetrics metrics;

    private final Cache<Long, ShowDetailCachePayload> l1Cache;
    private final Cache<Long, ShowDetailCachePayload> staleFallbackCache;
    private final ConcurrentHashMap<Long, CompletableFuture<ShowDetailCachePayload>> coldReadInFlight = new ConcurrentHashMap<>();
    private final Semaphore coldReadPermits;
    private final ThreadPoolExecutor rebuildExecutor;

    public ShowDetailCacheService(ShowMapper showMapper,
                                  TicketTierMapper ticketTierMapper,
                                  ShowCatalogBloomManager bloomManager,
                                  ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider,
                                  ObjectProvider<ShowRedisScripts> redisScriptsProvider,
                                  ObjectMapper objectMapper,
                                  ShowProperties properties,
                                  ShowMetrics metrics) {
        this.showMapper = showMapper;
        this.ticketTierMapper = ticketTierMapper;
        this.bloomManager = bloomManager;
        this.stringRedisTemplateProvider = stringRedisTemplateProvider;
        this.redisScriptsProvider = redisScriptsProvider;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.metrics = metrics;

        long l1Ttl = Math.max(1L, properties.getDetailL1TtlSeconds());
        long l1Max = Math.max(100L, properties.getDetailL1MaxSize());
        long staleTtl = Math.max(l1Ttl, properties.getDetailMaxStaleSeconds());
        this.l1Cache = Caffeine.newBuilder()
                .maximumSize(l1Max)
                .expireAfterWrite(Duration.ofSeconds(l1Ttl))
                .build();
        this.staleFallbackCache = Caffeine.newBuilder()
                .maximumSize(l1Max)
                .expireAfterWrite(Duration.ofSeconds(staleTtl))
                .build();
        this.coldReadPermits = new Semaphore(Math.max(1, properties.getColdReadMaxConcurrent()));
        int poolSize = Math.max(1, properties.getRebuildThreadPoolSize());
        int queueCap = Math.max(8, properties.getRebuildQueueCapacity());
        this.rebuildExecutor = new ThreadPoolExecutor(
                Math.min(2, poolSize),
                poolSize,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCap),
                r -> {
                    Thread t = new Thread(r, "show-detail-rebuild");
                    t.setDaemon(true);
                    return t;
                }
        );
    }

    @PreDestroy
    public void shutdown() {
        rebuildExecutor.shutdownNow();
    }

    /**
     * 读取演出静态详情快照（默认启用布隆防穿透，供公网浏览接口使用；返回 null 表示确定不存在）
     */
    public ShowDetailCachePayload getDetail(long showId) {
        return getDetail(showId, true);
    }

    /**
     * 读取演出静态详情快照：
     * - useBloom=true：公网读路径启用有效场次目录布隆防穿透；
     * - useBloom=false：内部 Facade 强可见性读路径（仍走 L1/L2 逻辑过期与 30s 负缓存，避免新入库场次被滞后布隆误杀，查到后自动反哺布隆）。
     */
    public ShowDetailCachePayload getDetail(long showId, boolean useBloom) {
        long nowMs = System.currentTimeMillis();

        // 1. 先查 L1（仅返回有效且未逻辑过期、或尚在负缓存有效期的条目）
        ShowDetailCachePayload l1 = l1Cache.getIfPresent(showId);
        if (l1 != null) {
            if (l1.isNotFound()) {
                if (nowMs - l1.getRefreshedAtEpochMs() <= properties.getNegativeCacheTtlSeconds() * 1000L) {
                    metrics.recordDetailL1Hit();
                    return null;
                }
                l1Cache.invalidate(showId);
            } else if (!l1.isLogicallyExpired(nowMs)) {
                metrics.recordDetailL1Hit();
                return l1;
            }
        }

        // 2. 查 L2 Redis 逻辑过期快照
        StringRedisTemplate redis = stringRedisTemplateProvider.getIfAvailable();
        boolean redisAvailable = (redis != null);
        if (redisAvailable) {
            try {
                ShowDetailCachePayload l2 = readFromRedis(redis, showId);
                if (l2 != null) {
                    if (l2.isNotFound()) {
                        metrics.recordDetailL2Hit();
                        l1Cache.put(showId, l2);
                        return null;
                    }
                    if (!l2.isLogicallyExpired(nowMs)) {
                        metrics.recordDetailL2Hit();
                        putLocalCaches(showId, l2);
                        return l2;
                    }
                    // 逻辑过期：在最大允许陈旧窗口内，触发单锁异步重建并立刻返回旧快照
                    if (l2.isWithinMaxStale(nowMs, properties.getDetailMaxStaleSeconds())) {
                        metrics.recordDetailL2StaleHit();
                        staleFallbackCache.put(showId, l2);
                        triggerAsyncRebuild(showId, redis);
                        return l2;
                    }
                    // 超过最大陈旧上限，禁止把旧展示详情当现值，转入冷读合并回源
                }
            } catch (Exception ex) {
                redisAvailable = false;
                log.warn("Redis L2 detail read failed for showId={}, trying stale fallback / bounded DB read: {}",
                        showId, ex.getMessage());
                ShowDetailCachePayload fallback = staleFallbackCache.getIfPresent(showId);
                if (fallback != null && !fallback.isNotFound()
                        && fallback.isWithinMaxStale(nowMs, properties.getDetailMaxStaleSeconds())) {
                    metrics.recordDetailL2StaleHit();
                    return fallback;
                }
            }
        }

        // 3. 缓存未命中：公网路径先过有效场次目录布隆（仅布隆已就绪且明确否定时才拦截）
        if (useBloom && redisAvailable && bloomManager.isDefinitelyAbsent(showId)) {
            metrics.recordDetailBloomReject();
            return null;
        }

        // 4. 冷 miss / 超陈旧：本机 SingleFlight 合并 + 有界等待超时 503/B9999
        return coldReadWithSingleFlight(showId, redisAvailable ? redis : null);
    }

    /**
     * 主动从 MySQL 重建并覆盖刷新 L2 与 L1（改期、预热、生命周期状态变更时调用）
     */
    public ShowDetailCachePayload refreshFromDb(long showId) {
        ShowDetailCachePayload fresh = loadAndCacheFromDb(showId, stringRedisTemplateProvider.getIfAvailable());
        if (fresh != null && !fresh.isNotFound()) {
            bloomManager.registerShowId(showId);
            return fresh;
        }
        return null;
    }

    public void invalidateLocal(long showId) {
        l1Cache.invalidate(showId);
    }

    public void clearLocalCaches() {
        l1Cache.invalidateAll();
        staleFallbackCache.invalidateAll();
    }

    private void triggerAsyncRebuild(long showId, StringRedisTemplate redis) {
        String lockKey = RedisKeys.showDetailRebuildLock(showId);
        String token = UUID.randomUUID().toString();
        long leaseMs = Math.max(1000L, properties.getRebuildLockLeaseSeconds() * 1000L);
        Boolean acquired;
        try {
            acquired = redis.opsForValue().setIfAbsent(lockKey, token, Duration.ofMillis(leaseMs));
        } catch (Exception ex) {
            log.warn("Failed to acquire detail rebuild lock for showId={}: {}", showId, ex.getMessage());
            return;
        }
        if (!Boolean.TRUE.equals(acquired)) {
            metrics.recordDetailRebuildLockConflict();
            return;
        }

        try {
            rebuildExecutor.execute(() -> {
                try {
                    // 拿锁后 Double-Check：避免前一次刚刷新完毕后尾随线程重复查 MySQL
                    ShowDetailCachePayload latest = readFromRedis(redis, showId);
                    long checkNow = System.currentTimeMillis();
                    if (latest != null && !latest.isNotFound() && !latest.isLogicallyExpired(checkNow)) {
                        putLocalCaches(showId, latest);
                        return;
                    }
                    loadAndCacheFromDb(showId, redis);
                } catch (Exception ex) {
                    log.warn("Async detail rebuild failed for showId={}: {}", showId, ex.getMessage());
                } finally {
                    releaseLock(lockKey, token);
                }
            });
        } catch (RejectedExecutionException rejected) {
            metrics.recordDetailRebuildQueueReject();
            releaseLock(lockKey, token);
        }
    }

    private ShowDetailCachePayload coldReadWithSingleFlight(long showId, StringRedisTemplate redis) {
        CompletableFuture<ShowDetailCachePayload> created = new CompletableFuture<>();
        CompletableFuture<ShowDetailCachePayload> existing = coldReadInFlight.putIfAbsent(showId, created);
        if (existing != null) {
            return awaitColdRead(existing, showId);
        }

        boolean permitAcquired = coldReadPermits.tryAcquire();
        if (!permitAcquired) {
            coldReadInFlight.remove(showId, created);
            BizException busy = BizException.serviceUnavailable(
                    ErrorCode.SYSTEM_ERROR, properties.getRetryAfterMs(), "演出详情并发加载繁忙，请稍后重试");
            created.completeExceptionally(busy);
            throw busy;
        }

        try {
            // Double-check Redis L2 避免并发边界重复回源
            if (redis != null) {
                try {
                    ShowDetailCachePayload l2 = readFromRedis(redis, showId);
                    long nowMs = System.currentTimeMillis();
                    if (l2 != null) {
                        if (l2.isNotFound()) {
                            l1Cache.put(showId, l2);
                            created.complete(null);
                            return null;
                        }
                        if (!l2.isLogicallyExpired(nowMs)
                                || l2.isWithinMaxStale(nowMs, properties.getDetailMaxStaleSeconds())) {
                            putLocalCaches(showId, l2);
                            created.complete(l2);
                            return l2;
                        }
                    }
                } catch (Exception ex) {
                    log.debug("Cold read L2 double-check failed for showId={}: {}", showId, ex.getMessage());
                }
            }

            ShowDetailCachePayload loaded = loadAndCacheFromDb(showId, redis);
            ShowDetailCachePayload result = (loaded != null && !loaded.isNotFound()) ? loaded : null;
            created.complete(result);
            return result;
        } catch (Throwable ex) {
            created.completeExceptionally(ex);
            if (ex instanceof BizException biz) {
                throw biz;
            }
            throw BizException.serviceUnavailable(
                    ErrorCode.SYSTEM_ERROR, properties.getRetryAfterMs(), "演出详情暂不可用，请稍后重试");
        } finally {
            coldReadPermits.release();
            coldReadInFlight.remove(showId, created);
        }
    }

    private ShowDetailCachePayload awaitColdRead(CompletableFuture<ShowDetailCachePayload> future, long showId) {
        long timeoutMs = Math.max(20L, properties.getColdReadTimeoutMs());
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            log.warn("Cold detail read timed out after {}ms for showId={}", timeoutMs, showId);
            throw BizException.serviceUnavailable(
                    ErrorCode.SYSTEM_ERROR, properties.getRetryAfterMs(), "演出详情加载超时，请稍后重试");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw BizException.serviceUnavailable(
                    ErrorCode.SYSTEM_ERROR, properties.getRetryAfterMs(), "演出详情加载中断，请重试");
        } catch (Exception ex) {
            Throwable cause = (ex instanceof CompletionException || ex.getCause() != null) ? ex.getCause() : ex;
            if (cause instanceof BizException biz) {
                throw biz;
            }
            throw BizException.serviceUnavailable(
                    ErrorCode.SYSTEM_ERROR, properties.getRetryAfterMs(), "演出详情暂不可用，请稍后重试");
        }
    }

    private ShowDetailCachePayload loadAndCacheFromDb(long showId, StringRedisTemplate redis) {
        metrics.recordDetailDbRebuild();
        ShowDO show = showMapper.selectById(showId);
        long nowMs = System.currentTimeMillis();
        if (show == null) {
            ShowDetailCachePayload negative = ShowDetailCachePayload.builder()
                    .notFound(true)
                    .showId(showId)
                    .refreshedAtEpochMs(nowMs)
                    .expireAtEpochMs(nowMs + properties.getNegativeCacheTtlSeconds() * 1000L)
                    .build();
            l1Cache.put(showId, negative);
            if (redis != null) {
                writeToRedis(redis, showId, negative, Duration.ofSeconds(Math.max(5L, properties.getNegativeCacheTtlSeconds())));
            }
            return negative;
        }

        List<TicketTierDO> tierList = ticketTierMapper.selectByShowId(showId);
        List<ShowDetailCachePayload.CachedTier> cachedTiers = tierList == null
                ? Collections.emptyList()
                : tierList.stream()
                .map(t -> ShowDetailCachePayload.CachedTier.builder()
                        .tierId(t.getId())
                        .tierName(t.getTierName())
                        .zoneId(t.getZoneId())
                        .priceCent(t.getPriceCent())
                        .totalStock(t.getTotalStock())
                        .build())
                .toList();

        long baseLogicalSec = Math.max(5L, properties.getDetailL2LogicalTtlSeconds());
        long jitterBound = Math.max(0L, properties.getDetailL2JitterSeconds());
        long jitterSec = jitterBound > 0 ? ThreadLocalRandom.current().nextLong(jitterBound + 1) : 0L;
        long expireAtMs = nowMs + (baseLogicalSec + jitterSec) * 1000L;
        long physicalTtlSec = baseLogicalSec + jitterSec + Math.max(60L, properties.getDetailMaxStaleSeconds()) + 3600L;

        ShowDetailCachePayload payload = ShowDetailCachePayload.builder()
                .notFound(false)
                .showId(show.getId())
                .saleConfigVersion(show.getSaleConfigVersion() == null ? 1 : show.getSaleConfigVersion())
                .title(show.getTitle())
                .artist(show.getArtist())
                .city(show.getCity())
                .venue(show.getVenue())
                .showTimeEpochMs(ShowTimeUtil.toEpochMs(show.getShowTime()))
                .saleStartEpochMs(ShowTimeUtil.toEpochMs(show.getSaleStartTime()))
                .saleEndEpochMs(ShowTimeUtil.toEpochMs(show.getSaleEndTime()))
                .saleStatus(show.getSaleStatus())
                .seatMode(show.getSeatMode())
                .posterUrl(show.getPosterUrl())
                .description(show.getDescription())
                .tiers(cachedTiers)
                .refreshedAtEpochMs(nowMs)
                .expireAtEpochMs(expireAtMs)
                .build();

        putLocalCaches(showId, payload);
        if (redis != null) {
            writeToRedis(redis, showId, payload, Duration.ofSeconds(physicalTtlSec));
        }
        bloomManager.registerShowId(showId);
        return payload;
    }

    private void putLocalCaches(long showId, ShowDetailCachePayload payload) {
        l1Cache.put(showId, payload);
        if (!payload.isNotFound()) {
            staleFallbackCache.put(showId, payload);
        }
    }

    private ShowDetailCachePayload readFromRedis(StringRedisTemplate redis, long showId) {
        String raw = redis.opsForValue().get(RedisKeys.showDetail(showId));
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, ShowDetailCachePayload.class);
        } catch (JsonProcessingException ex) {
            log.warn("Corrupted detail cache JSON for showId={}, ignoring: {}", showId, ex.getMessage());
            return null;
        }
    }

    private void writeToRedis(StringRedisTemplate redis, long showId, ShowDetailCachePayload payload, Duration ttl) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            redis.opsForValue().set(RedisKeys.showDetail(showId), json, ttl);
        } catch (Exception ex) {
            log.warn("Failed to write detail L2 cache for showId={}: {}", showId, ex.getMessage());
        }
    }

    private void releaseLock(String lockKey, String token) {
        ShowRedisScripts scripts = redisScriptsProvider.getIfAvailable();
        if (scripts != null) {
            scripts.unlockIfOwner(lockKey, token);
        }
    }
}
