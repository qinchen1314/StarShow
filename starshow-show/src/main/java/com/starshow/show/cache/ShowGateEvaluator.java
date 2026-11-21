package com.starshow.show.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.starshow.common.constant.RedisKeys;
import com.starshow.show.config.ShowMetrics;
import com.starshow.show.config.ShowProperties;
import com.starshow.show.domain.entity.ShowDO;
import com.starshow.show.domain.model.EntryState;
import com.starshow.show.domain.model.ShowDetailCachePayload;
import com.starshow.show.domain.model.ShowReadyState;
import com.starshow.show.lua.ShowRedisScripts;
import com.starshow.show.mapper.ShowMapper;
import com.starshow.show.util.ShowTimeUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 链路二权威开售门禁与四态状态机评估器：
 * 1. 毫秒级准点开售：只要 starshow:show:ready:{showId} 处于 READY/OPEN 且服务端绝对时间在 [saleStartEpochMs, saleEndEpochMs) 内，
 *    入口即刻开放，绝不等待后台定时任务唤醒；
 * 2. 严格区分五态（PREPARING / OPEN / SOLD_OUT / STOCK_UNAVAILABLE / ENDED），已开售期 Redis 键缺失或异常严禁误报 SOLD_OUT；
 * 3. 当 Redis ready 尚未初始化时，优先复用刚刷新的新鲜详情快照，否则经 Caffeine 短缓存（5s）+ SingleFlight 合并回源，
 *    杜绝开票前热点浏览造成逐次 t_show 行读；改期/预热/生命周期变更时会写入 Redis ready 并主动失效本地短缓存；
 * 4. 可选支持 stock-local-ttl-ms 短时进程内余票快照（仅用于浏览读，保护 Redis 热点分片）。
 */
@Slf4j
@Component
public class ShowGateEvaluator {

    private static final long AUTH_META_FRESH_WINDOW_MS = 5_000L;
    private static final long AUTH_META_WAIT_TIMEOUT_MS = 500L;

    private final ObjectProvider<ShowRedisScripts> redisScriptsProvider;
    private final ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider;
    private final ShowMapper showMapper;
    private final ShowProperties properties;
    private final ShowMetrics metrics;

    private final Cache<Long, AuthoritativeShowMeta> authMetaCache;
    private final ConcurrentHashMap<Long, CompletableFuture<AuthoritativeShowMeta>> authMetaInFlight = new ConcurrentHashMap<>();

    private volatile Cache<Long, LocalStockSnapshot> localStockCache;
    private volatile long configuredLocalStockTtlMs = -1L;

    public ShowGateEvaluator(ObjectProvider<ShowRedisScripts> redisScriptsProvider,
                             ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider,
                             ShowMapper showMapper,
                             ShowProperties properties,
                             ShowMetrics metrics) {
        this.redisScriptsProvider = redisScriptsProvider;
        this.stringRedisTemplateProvider = stringRedisTemplateProvider;
        this.showMapper = showMapper;
        this.properties = properties;
        this.metrics = metrics;
        this.authMetaCache = Caffeine.newBuilder()
                .maximumSize(5000)
                .expireAfterWrite(Duration.ofMillis(AUTH_META_FRESH_WINDOW_MS))
                .build();
    }

    /**
     * 结合静态详情快照与 Redis 权威热态，实时计算场次入口状态与各票档余量状态（默认开启开票前权威元信息合并缓存）
     */
    public GateEvaluation evaluate(ShowDetailCachePayload detail, long nowMs, boolean allowLocalStockCache) {
        return evaluate(detail, nowMs, allowLocalStockCache, true);
    }

    /**
     * 结合静态详情快照与 Redis 权威热态，实时计算场次入口状态与各票档余量状态：
     * - coalesceAuthMeta=true（公网浏览路径）：ready 未初始化时复用 5s 新鲜快照或经 Caffeine+SingleFlight 合并，杜绝逐次读库；
     * - coalesceAuthMeta=false（内部强一致调度路径）：ready 存在仍直读 Redis，ready 缺失时直查权威 DB 保障即时可见。
     */
    public GateEvaluation evaluate(ShowDetailCachePayload detail, long nowMs,
                                   boolean allowLocalStockCache, boolean coalesceAuthMeta) {
        long showId = detail.getShowId();
        List<ShowDetailCachePayload.CachedTier> tiers = detail.getTiers() == null ? Collections.emptyList() : detail.getTiers();

        // 1. 读取权威 Redis ready Hash
        ShowReadyState ready = null;
        boolean redisHealthy = true;
        ShowRedisScripts scripts = redisScriptsProvider.getIfAvailable();
        if (scripts != null) {
            try {
                ready = scripts.readReadyState(showId);
            } catch (Exception ex) {
                redisHealthy = false;
                log.warn("Failed to read show ready state from Redis for showId={}: {}", showId, ex.getMessage());
            }
        } else {
            redisHealthy = false;
        }

        // 2. 解析权威版本与开售/停售时间戳（绝不盲信陈旧详情副本，同时避免无 ready 时的逐次 DB 回源）
        int version = detail.getSaleConfigVersion() == null ? 1 : detail.getSaleConfigVersion();
        long saleStartMs = detail.getSaleStartEpochMs() == null ? 0L : detail.getSaleStartEpochMs();
        long saleEndMs = detail.getSaleEndEpochMs() == null ? 0L : detail.getSaleEndEpochMs();
        String saleStatus = detail.getSaleStatus() == null ? "INIT" : detail.getSaleStatus().toUpperCase();

        if (ready != null && ready.getVersion() != null && ready.getVersion() >= version) {
            version = ready.getVersion();
            if (ready.getSaleStartEpochMs() != null && ready.getSaleStartEpochMs() > 0) {
                saleStartMs = ready.getSaleStartEpochMs();
            }
            if (ready.getSaleEndEpochMs() != null && ready.getSaleEndEpochMs() > 0) {
                saleEndMs = ready.getSaleEndEpochMs();
            }
            if (ShowReadyState.STAGE_OPEN.equals(ready.getStage())) {
                saleStatus = "ON_SALE";
            } else if (ShowReadyState.STAGE_ENDED.equals(ready.getStage())) {
                saleStatus = "ENDED";
            } else if (ShowReadyState.STAGE_READY.equals(ready.getStage()) && "INIT".equals(saleStatus)) {
                saleStatus = "PREHEATED";
            } else if (ShowReadyState.STAGE_PREPARING.equals(ready.getStage())) {
                saleStatus = "PREHEATING";
            }
        } else if (ready == null && redisHealthy
                && ("INIT".equals(saleStatus) || "PREHEATING".equals(saleStatus) || "PREHEATED".equals(saleStatus))) {
            if (coalesceAuthMeta) {
                // 公网浏览路径：优先复用 5s 内新鲜刷新的详情快照；否则经 Caffeine 5s 短缓存 + SingleFlight 合并回源
                boolean detailJustRefreshed = detail.getRefreshedAtEpochMs() > 0L
                        && (nowMs - detail.getRefreshedAtEpochMs()) <= AUTH_META_FRESH_WINDOW_MS;
                if (!detailJustRefreshed) {
                    AuthoritativeShowMeta authoritative = getAuthoritativeMetaCoalesced(showId);
                    if (authoritative != null) {
                        version = authoritative.saleConfigVersion();
                        saleStartMs = authoritative.saleStartEpochMs();
                        saleEndMs = authoritative.saleEndEpochMs();
                        saleStatus = authoritative.saleStatus();
                    }
                }
            } else {
                // 内部 Facade 强一致路径：ready 未初始化时直接读取权威 DB 元信息
                ShowDO authoritative = showMapper.selectById(showId);
                if (authoritative != null) {
                    version = authoritative.getSaleConfigVersion() == null ? 1 : authoritative.getSaleConfigVersion();
                    saleStartMs = ShowTimeUtil.toEpochMs(authoritative.getSaleStartTime());
                    saleEndMs = ShowTimeUtil.toEpochMs(authoritative.getSaleEndTime());
                    saleStatus = authoritative.getSaleStatus() == null ? "INIT" : authoritative.getSaleStatus().toUpperCase();
                }
            }
        }

        // 3. 判定是否已停售（左闭右开 [saleStartMs, saleEndMs)，到 saleEndMs 即停售）
        if ("ENDED".equals(saleStatus)
                || (ready != null && ready.isEnded())
                || (saleEndMs > 0L && nowMs >= saleEndMs)) {
            return buildUniformResult(showId, version, saleStatus, saleStartMs, saleEndMs, nowMs, false,
                    EntryState.ENDED, ready, tiers, true);
        }

        // 4. 未到开售时间 -> PREPARING
        if (saleStartMs > 0L && nowMs < saleStartMs) {
            return buildUniformResult(showId, version, saleStatus, saleStartMs, saleEndMs, nowMs, false,
                    EntryState.PREPARING, ready, tiers, true);
        }

        // 5. 处于售卖时间窗 [saleStartMs, saleEndMs) 内
        // 5a. 改期中 / 预热中 -> PREPARING（阻止交易）
        if (ready != null && ready.isPreparingOrBlocked()) {
            return buildUniformResult(showId, version, saleStatus, saleStartMs, saleEndMs, nowMs, false,
                    EntryState.PREPARING, ready, tiers, true);
        }

        // 5b. Redis 故障或已开售但 ready 键丢失
        if (!redisHealthy) {
            metrics.recordStockMissingKey();
            return buildUniformResult(showId, version, saleStatus, saleStartMs, saleEndMs, nowMs, false,
                    EntryState.STOCK_UNAVAILABLE, null, tiers, false);
        }

        if (ready == null) {
            // DB 已正式开售(ON_SALE/SOLD_OUT)但 Redis ready 门禁丢失 -> STOCK_UNAVAILABLE，绝不误报 SOLD_OUT！
            if ("ON_SALE".equals(saleStatus) || "SOLD_OUT".equals(saleStatus)) {
                metrics.recordStockMissingKey();
                return buildUniformResult(showId, version, saleStatus, saleStartMs, saleEndMs, nowMs, false,
                        EntryState.STOCK_UNAVAILABLE, null, tiers, false);
            }
            // DB 尚未预热完成（INIT/PREHEATING/PREHEATED 但 Redis 未就绪）-> PREPARING
            return buildUniformResult(showId, version, saleStatus, saleStartMs, saleEndMs, nowMs, false,
                    EntryState.PREPARING, null, tiers, true);
        }

        // 5c. 毫秒级准点开放条件：ready.stage IN ('READY','OPEN') 且版本匹配且在 [saleStartMs, saleEndMs) 内
        boolean versionMatches = ready.getVersion() != null && ready.getVersion() == version;
        if (!ready.isReadyOrOpen() || !versionMatches) {
            return buildUniformResult(showId, version, saleStatus, saleStartMs, saleEndMs, nowMs, false,
                    EntryState.PREPARING, ready, tiers, true);
        }

        // 6. 读取各票档实时库存（可选走 stock-local-ttl-ms 浏览短快照）
        StockReadBatch stockBatch = readTierStocks(showId, tiers, nowMs, allowLocalStockCache);
        List<TierStockEval> tierEvals = new ArrayList<>(tiers.size());
        int openCount = 0;
        int validZeroCount = 0;
        int unavailableCount = 0;

        for (ShowDetailCachePayload.CachedTier tier : tiers) {
            Integer avail = stockBatch.tierAvailable.get(tier.getTierId());
            if (avail == null) {
                unavailableCount++;
                metrics.recordStockMissingKey();
                tierEvals.add(TierStockEval.builder()
                        .tierId(tier.getTierId())
                        .available(null)
                        .stockState(EntryState.STOCK_UNAVAILABLE)
                        .build());
            } else if (avail > 0) {
                openCount++;
                tierEvals.add(TierStockEval.builder()
                        .tierId(tier.getTierId())
                        .available(avail)
                        .stockState(EntryState.OPEN)
                        .build());
            } else {
                validZeroCount++;
                tierEvals.add(TierStockEval.builder()
                        .tierId(tier.getTierId())
                        .available(0)
                        .stockState(EntryState.SOLD_OUT)
                        .build());
            }
        }

        EntryState entryState;
        if (openCount > 0) {
            entryState = EntryState.OPEN;
        } else if (!tiers.isEmpty() && validZeroCount == tiers.size() && unavailableCount == 0) {
            entryState = EntryState.SOLD_OUT;
            metrics.recordStockSoldOutResponse();
        } else {
            entryState = EntryState.STOCK_UNAVAILABLE;
        }

        boolean hasAnyValidTier = (openCount + validZeroCount) > 0;
        return GateEvaluation.builder()
                .showId(showId)
                .saleConfigVersion(version)
                .saleStatus(saleStatus)
                .saleStartEpochMs(saleStartMs)
                .saleEndEpochMs(saleEndMs)
                .asOfEpochMs(stockBatch.asOfEpochMs)
                .stale(stockBatch.stale)
                .entryState(entryState)
                .readyState(ready)
                .tiers(tierEvals)
                .anyTierValid(hasAnyValidTier)
                .redisHealthy(true)
                .build();
    }

    public void invalidateLocalStockCache(long showId) {
        authMetaCache.invalidate(showId);
        Cache<Long, LocalStockSnapshot> cache = this.localStockCache;
        if (cache != null) {
            cache.invalidate(showId);
        }
    }

    public void clearLocalCaches() {
        authMetaCache.invalidateAll();
        Cache<Long, LocalStockSnapshot> cache = this.localStockCache;
        if (cache != null) {
            cache.invalidateAll();
        }
    }

    private AuthoritativeShowMeta getAuthoritativeMetaCoalesced(long showId) {
        AuthoritativeShowMeta cached = authMetaCache.getIfPresent(showId);
        if (cached != null) {
            return cached;
        }

        CompletableFuture<AuthoritativeShowMeta> created = new CompletableFuture<>();
        CompletableFuture<AuthoritativeShowMeta> existing = authMetaInFlight.putIfAbsent(showId, created);
        if (existing != null) {
            try {
                return existing.get(AUTH_META_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception ex) {
                log.debug("Coalesced auth-meta wait skipped for showId={}: {}", showId, ex.getMessage());
                return null;
            }
        }

        try {
            AuthoritativeShowMeta doubleChecked = authMetaCache.getIfPresent(showId);
            if (doubleChecked != null) {
                created.complete(doubleChecked);
                return doubleChecked;
            }
            metrics.recordGateAuthDbRead();
            ShowDO authoritative = showMapper.selectById(showId);
            AuthoritativeShowMeta meta = null;
            if (authoritative != null) {
                meta = new AuthoritativeShowMeta(
                        authoritative.getSaleConfigVersion() == null ? 1 : authoritative.getSaleConfigVersion(),
                        ShowTimeUtil.toEpochMs(authoritative.getSaleStartTime()),
                        ShowTimeUtil.toEpochMs(authoritative.getSaleEndTime()),
                        authoritative.getSaleStatus() == null ? "INIT" : authoritative.getSaleStatus().toUpperCase()
                );
                authMetaCache.put(showId, meta);
            }
            created.complete(meta);
            return meta;
        } catch (Throwable ex) {
            created.completeExceptionally(ex);
            log.warn("Failed to load authoritative show meta for showId={}: {}", showId, ex.getMessage());
            return null;
        } finally {
            authMetaInFlight.remove(showId, created);
        }
    }

    private StockReadBatch readTierStocks(long showId, List<ShowDetailCachePayload.CachedTier> tiers,
                                          long nowMs, boolean allowLocalStockCache) {
        if (tiers.isEmpty()) {
            return new StockReadBatch(Collections.emptyMap(), nowMs, false);
        }
        long localTtlMs = properties.getStockLocalTtlMs();
        if (allowLocalStockCache && localTtlMs > 0) {
            Cache<Long, LocalStockSnapshot> cache = getOrBuildLocalStockCache(localTtlMs);
            LocalStockSnapshot snap = cache.getIfPresent(showId);
            if (snap != null && (nowMs - snap.asOfEpochMs) <= localTtlMs) {
                return new StockReadBatch(snap.tierAvailable, snap.asOfEpochMs, true);
            }
        }

        StringRedisTemplate redis = stringRedisTemplateProvider.getIfAvailable();
        if (redis == null) {
            return new StockReadBatch(Collections.emptyMap(), nowMs, false);
        }
        List<String> keys = new ArrayList<>(tiers.size());
        for (ShowDetailCachePayload.CachedTier tier : tiers) {
            keys.add(RedisKeys.stock(showId, tier.getTierId()));
        }

        Map<Long, Integer> resultMap = new java.util.LinkedHashMap<>();
        try {
            List<String> rawValues = redis.opsForValue().multiGet(keys);
            for (int i = 0; i < tiers.size(); i++) {
                Long tierId = tiers.get(i).getTierId();
                String raw = (rawValues != null && i < rawValues.size()) ? rawValues.get(i) : null;
                resultMap.put(tierId, parseNonNegativeStock(raw));
            }
        } catch (Exception ex) {
            log.warn("Redis multiGet stock failed for showId={}: {}", showId, ex.getMessage());
            for (ShowDetailCachePayload.CachedTier tier : tiers) {
                resultMap.put(tier.getTierId(), null);
            }
        }

        if (allowLocalStockCache && localTtlMs > 0) {
            getOrBuildLocalStockCache(localTtlMs).put(showId, new LocalStockSnapshot(resultMap, nowMs));
        }
        return new StockReadBatch(resultMap, nowMs, false);
    }

    private Cache<Long, LocalStockSnapshot> getOrBuildLocalStockCache(long ttlMs) {
        if (localStockCache == null || configuredLocalStockTtlMs != ttlMs) {
            synchronized (this) {
                if (localStockCache == null || configuredLocalStockTtlMs != ttlMs) {
                    localStockCache = Caffeine.newBuilder()
                            .maximumSize(2000)
                            .expireAfterWrite(Duration.ofMillis(ttlMs))
                            .build();
                    configuredLocalStockTtlMs = ttlMs;
                }
            }
        }
        return localStockCache;
    }

    private Integer parseNonNegativeStock(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int val = Integer.parseInt(raw.trim());
            return Math.max(0, val);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private GateEvaluation buildUniformResult(long showId, int version, String saleStatus,
                                              long saleStartMs, long saleEndMs, long nowMs, boolean stale,
                                              EntryState state, ShowReadyState ready,
                                              List<ShowDetailCachePayload.CachedTier> tiers,
                                              boolean redisHealthy) {
        List<TierStockEval> tierEvals = new ArrayList<>(tiers.size());
        for (ShowDetailCachePayload.CachedTier tier : tiers) {
            tierEvals.add(TierStockEval.builder()
                    .tierId(tier.getTierId())
                    .available(null)
                    .stockState(state)
                    .build());
        }
        return GateEvaluation.builder()
                .showId(showId)
                .saleConfigVersion(version)
                .saleStatus(saleStatus)
                .saleStartEpochMs(saleStartMs)
                .saleEndEpochMs(saleEndMs)
                .asOfEpochMs(nowMs)
                .stale(stale)
                .entryState(state)
                .readyState(ready)
                .tiers(tierEvals)
                .anyTierValid(state != EntryState.STOCK_UNAVAILABLE)
                .redisHealthy(redisHealthy)
                .build();
    }

    public static String entryHintFor(EntryState state) {
        return switch (state) {
            case PREPARING -> "开售准备中，请按官方开售时间准时抢票";
            case OPEN -> "正在热售，立即购票";
            case SOLD_OUT -> "当前暂无余票，未支付关单或退票后可能有余票回流，请留意变化";
            case STOCK_UNAVAILABLE -> "库存暂不可用，请稍后刷新";
            case ENDED -> "本场演出已停止售票";
        };
    }

    private record AuthoritativeShowMeta(int saleConfigVersion, long saleStartEpochMs, long saleEndEpochMs, String saleStatus) {
    }

    private record LocalStockSnapshot(Map<Long, Integer> tierAvailable, long asOfEpochMs) {
    }

    private record StockReadBatch(Map<Long, Integer> tierAvailable, long asOfEpochMs, boolean stale) {
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GateEvaluation {
        private long showId;
        private int saleConfigVersion;
        private String saleStatus;
        private long saleStartEpochMs;
        private long saleEndEpochMs;
        private long asOfEpochMs;
        private boolean stale;
        private EntryState entryState;
        private ShowReadyState readyState;
        private List<TierStockEval> tiers;
        private boolean anyTierValid;
        private boolean redisHealthy;

        public TierStockEval findTier(long tierId) {
            if (tiers == null) {
                return null;
            }
            for (TierStockEval t : tiers) {
                if (t.getTierId() == tierId) {
                    return t;
                }
            }
            return null;
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TierStockEval {
        private long tierId;
        private Integer available;
        private EntryState stockState;
    }
}
