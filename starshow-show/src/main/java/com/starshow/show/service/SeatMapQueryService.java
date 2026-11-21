package com.starshow.show.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.starshow.common.constant.RedisKeys;
import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.show.cache.ShowDetailCacheService;
import com.starshow.show.cache.ShowGateEvaluator;
import com.starshow.show.config.ShowMetrics;
import com.starshow.show.config.ShowProperties;
import com.starshow.show.domain.entity.SeatDO;
import com.starshow.show.domain.model.EntryState;
import com.starshow.show.domain.model.ShowDetailCachePayload;
import com.starshow.show.lua.ShowRedisScripts;
import com.starshow.show.mapper.SeatMapper;
import com.starshow.show.util.ShowTimeUtil;
import com.starshow.show.vo.SeatMapVO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * 链路二（节点 1）：自主选座分区 Bitmap 实时座位图服务
 * - 静态物理布局（rowNo/colNo/seatIndex/segmentId/tierId/status）在售卖期冻结，按 (showId, zoneId, layoutVersion)
 *   经 Caffeine L1 + Redis L2 + SingleFlight 合并缓存，彻底避免开售后反复选座造成高频 t_seat 行读；
 * - 实时座位占用每次 100% 直读 Redis Bitmap（starshow:seat:bitmap:{showId}:<zoneId>），按 MSB 最高位优先解码；
 * - 售卖期位图缺失、长度不足或 Redis 不可读时，直接返回 HTTP 503 + B0201，严禁返回虚假可选快照或误报售罄。
 */
@Slf4j
@Service
public class SeatMapQueryService {

    private static final Duration L1_LAYOUT_TTL = Duration.ofMinutes(5);
    private static final Duration L2_LAYOUT_TTL = Duration.ofHours(24);

    private final ShowDetailCacheService detailCacheService;
    private final ShowGateEvaluator gateEvaluator;
    private final SeatMapper seatMapper;
    private final ObjectProvider<ShowRedisScripts> redisScriptsProvider;
    private final ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider;
    private final ObjectMapper objectMapper;
    private final ShowProperties properties;
    private final ShowMetrics metrics;

    private final Cache<SeatLayoutKey, CachedStaticZoneLayout> staticLayoutL1Cache;
    private final ConcurrentHashMap<SeatLayoutKey, CompletableFuture<CachedStaticZoneLayout>> layoutLoadInFlight = new ConcurrentHashMap<>();

    @Autowired
    public SeatMapQueryService(ShowDetailCacheService detailCacheService,
                               ShowGateEvaluator gateEvaluator,
                               SeatMapper seatMapper,
                               ObjectProvider<ShowRedisScripts> redisScriptsProvider,
                               ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider,
                               ObjectProvider<ObjectMapper> objectMapperProvider,
                               ShowProperties properties,
                               ShowMetrics metrics) {
        this.detailCacheService = detailCacheService;
        this.gateEvaluator = gateEvaluator;
        this.seatMapper = seatMapper;
        this.redisScriptsProvider = redisScriptsProvider;
        this.stringRedisTemplateProvider = stringRedisTemplateProvider;
        this.objectMapper = objectMapperProvider == null ? new ObjectMapper() : objectMapperProvider.getIfAvailable(ObjectMapper::new);
        this.properties = properties;
        this.metrics = metrics;
        this.staticLayoutL1Cache = Caffeine.newBuilder()
                .maximumSize(2000)
                .expireAfterWrite(L1_LAYOUT_TTL)
                .build();
    }

    /**
     * 兼容既有 6 参单测构造
     */
    public SeatMapQueryService(ShowDetailCacheService detailCacheService,
                               ShowGateEvaluator gateEvaluator,
                               SeatMapper seatMapper,
                               ObjectProvider<ShowRedisScripts> redisScriptsProvider,
                               ShowProperties properties,
                               ShowMetrics metrics) {
        this(detailCacheService, gateEvaluator, seatMapper, redisScriptsProvider, null, null, properties, metrics);
    }

    public SeatMapVO getZoneSeatMap(long showId, String zoneId) {
        if (zoneId == null || zoneId.isBlank()) {
            throw new BizException(ErrorCode.INVALID_PARAM, "zoneId 不能为空");
        }
        String normalizedZone = zoneId.trim();
        ShowDetailCachePayload detail = detailCacheService.getDetail(showId);
        if (detail == null) {
            throw new BizException(ErrorCode.SHOW_NOT_FOUND);
        }

        long nowMs = System.currentTimeMillis();
        ShowGateEvaluator.GateEvaluation eval = gateEvaluator.evaluate(detail, nowMs, false);
        int layoutVersion = eval.getSaleConfigVersion();

        // 按 (showId, zoneId, layoutVersion) 读取版本化静态座位布局（L1 + L2 + SingleFlight），零重复扫表
        CachedStaticZoneLayout staticLayout = loadStaticZoneLayout(showId, normalizedZone, layoutVersion);
        if (staticLayout == null || staticLayout.getSeats() == null || staticLayout.getSeats().isEmpty()) {
            throw new BizException(ErrorCode.TIER_NOT_FOUND, "该场次不存在指定座位分区: " + normalizedZone);
        }

        List<StaticSeatRecord> seats = staticLayout.getSeats();
        long tierId = staticLayout.getTierId();
        int minBytesRequired = ShowRedisScripts.requiredBitmapBytes(staticLayout.getMaxSeatIndex());

        // 未到开售 / 预热中 / 已停售：返回结构化布局，但全部座位不可选
        if (eval.getEntryState() == EntryState.PREPARING || eval.getEntryState() == EntryState.ENDED) {
            return SeatMapVO.builder()
                    .showId(showId)
                    .zoneId(normalizedZone)
                    .tierId(tierId)
                    .layoutVersion(layoutVersion)
                    .asOf(ShowTimeUtil.toOffsetDateTime(nowMs))
                    .available(false)
                    .stockState(eval.getEntryState().name())
                    .seats(buildNonSelectableSeats(seats))
                    .build();
        }

        // 在售窗口：必须确认该票档与分区位图均有效可读
        ShowGateEvaluator.TierStockEval tierEval = eval.findTier(tierId);
        if (!eval.isRedisHealthy()
                || (tierEval != null && tierEval.getStockState() == EntryState.STOCK_UNAVAILABLE)
                || (tierEval == null && eval.getEntryState() == EntryState.STOCK_UNAVAILABLE)) {
            throw BizException.serviceUnavailable(
                    ErrorCode.STOCK_UNAVAILABLE,
                    properties.getRetryAfterMs(),
                    "分区座位状态暂不可用，请稍后刷新"
            );
        }

        ShowRedisScripts scripts = redisScriptsProvider.getIfAvailable();
        if (scripts == null) {
            metrics.recordStockMissingKey();
            throw BizException.serviceUnavailable(
                    ErrorCode.STOCK_UNAVAILABLE,
                    properties.getRetryAfterMs(),
                    "分区座位位图暂不可用，请稍后刷新"
            );
        }

        // 实时占用始终直读 Redis 原始二进制 Bitmap，绝不走本地缓存
        byte[] bitmapBytes;
        try {
            bitmapBytes = scripts.readRawSeatBitmap(showId, normalizedZone);
        } catch (Exception ex) {
            metrics.recordStockMissingKey();
            log.warn("Failed to read seat bitmap for showId={}, zoneId={}: {}", showId, normalizedZone, ex.getMessage());
            throw BizException.serviceUnavailable(
                    ErrorCode.STOCK_UNAVAILABLE,
                    properties.getRetryAfterMs(),
                    "分区座位位图读取异常，请稍后刷新"
            );
        }

        if (bitmapBytes == null || bitmapBytes.length < minBytesRequired) {
            metrics.recordStockMissingKey();
            log.warn("Seat bitmap missing or truncated: showId={}, zoneId={}, actualBytes={}, requiredBytes={}",
                    showId, normalizedZone, bitmapBytes == null ? -1 : bitmapBytes.length, minBytesRequired);
            throw BizException.serviceUnavailable(
                    ErrorCode.STOCK_UNAVAILABLE,
                    properties.getRetryAfterMs(),
                    "分区座位位图缺失或异常，请稍后刷新"
            );
        }

        boolean tierSoldOut = (tierEval != null && tierEval.getStockState() == EntryState.SOLD_OUT)
                || eval.getEntryState() == EntryState.SOLD_OUT;
        EntryState effectiveZoneState = tierSoldOut ? EntryState.SOLD_OUT : EntryState.OPEN;

        int selectableCount = 0;
        List<SeatMapVO.SeatItem> seatItems = new ArrayList<>(seats.size());
        for (StaticSeatRecord seat : seats) {
            boolean occupiedInBitmap = ShowRedisScripts.isBitSetMsb(bitmapBytes, seat.getSeatIndex());
            boolean selectable = !tierSoldOut && seat.isPhysicallyEnabled() && !occupiedInBitmap;
            if (selectable) {
                selectableCount++;
            }
            seatItems.add(SeatMapVO.SeatItem.builder()
                    .seatIndex(seat.getSeatIndex())
                    .rowNo(seat.getRowNo())
                    .colNo(seat.getColNo())
                    .segmentId(seat.getSegmentId())
                    .selectable(selectable)
                    .build());
        }

        return SeatMapVO.builder()
                .showId(showId)
                .zoneId(normalizedZone)
                .tierId(tierId)
                .layoutVersion(layoutVersion)
                .asOf(ShowTimeUtil.toOffsetDateTime(nowMs))
                .available(selectableCount > 0)
                .stockState(effectiveZoneState.name())
                .seats(seatItems)
                .build();
    }

    public void clearStaticLayoutCache() {
        staticLayoutL1Cache.invalidateAll();
    }

    private CachedStaticZoneLayout loadStaticZoneLayout(long showId, String zoneId, int layoutVersion) {
        SeatLayoutKey cacheKey = new SeatLayoutKey(showId, zoneId, layoutVersion);
        CachedStaticZoneLayout l1 = staticLayoutL1Cache.getIfPresent(cacheKey);
        if (l1 != null) {
            return l1;
        }

        StringRedisTemplate redis = stringRedisTemplateProvider == null ? null : stringRedisTemplateProvider.getIfAvailable();
        String redisKey = RedisKeys.seatStaticLayout(showId, zoneId, layoutVersion);
        if (redis != null) {
            try {
                String json = redis.opsForValue().get(redisKey);
                if (json != null && !json.isBlank()) {
                    CachedStaticZoneLayout fromRedis = objectMapper.readValue(json, new TypeReference<>() {});
                    if (fromRedis != null && fromRedis.getSeats() != null && !fromRedis.getSeats().isEmpty()) {
                        staticLayoutL1Cache.put(cacheKey, fromRedis);
                        return fromRedis;
                    }
                }
            } catch (Exception ex) {
                log.debug("Failed to read static seat layout L2 for {}: {}", redisKey, ex.getMessage());
            }
        }

        // SingleFlight 合并并发冷读，同一 (showId, zoneId, layoutVersion) 只查一次 MySQL t_seat
        CompletableFuture<CachedStaticZoneLayout> created = new CompletableFuture<>();
        CompletableFuture<CachedStaticZoneLayout> existing = layoutLoadInFlight.putIfAbsent(cacheKey, created);
        if (existing != null) {
            try {
                return existing.get(2, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw layoutUnavailable();
            } catch (ExecutionException | TimeoutException ex) {
                log.warn("Coalesced static seat layout load failed for {}", cacheKey);
                throw layoutUnavailable();
            }
        }

        try {
            CachedStaticZoneLayout rechecked = staticLayoutL1Cache.getIfPresent(cacheKey);
            if (rechecked != null) {
                created.complete(rechecked);
                return rechecked;
            }
            metrics.recordSeatLayoutDbRead();
            List<SeatDO> rows;
            try {
                rows = seatMapper.selectByShowAndZone(showId, zoneId);
            } catch (org.springframework.dao.DataAccessException ex) {
                log.warn("Static seat layout query failed for {}", cacheKey);
                throw layoutUnavailable();
            }
            if (rows == null || rows.isEmpty()) {
                created.complete(null);
                return null;
            }
            long tierId = rows.get(0).getTierId();
            int maxSeatIndex = 0;
            List<StaticSeatRecord> records = new ArrayList<>(rows.size());
            for (SeatDO row : rows) {
                maxSeatIndex = Math.max(maxSeatIndex, row.getSeatIndex());
                records.add(new StaticSeatRecord(
                        row.getSeatIndex(),
                        row.getRowNo(),
                        row.getColNo(),
                        row.getSegmentId() == null ? "S1" : row.getSegmentId(),
                        row.getStatus() == null || row.getStatus() == 0
                ));
            }
            CachedStaticZoneLayout loaded = new CachedStaticZoneLayout(tierId, maxSeatIndex, records);
            staticLayoutL1Cache.put(cacheKey, loaded);
            if (redis != null) {
                try {
                    redis.opsForValue().set(redisKey, objectMapper.writeValueAsString(loaded), L2_LAYOUT_TTL);
                } catch (Exception ex) {
                    log.debug("Failed to write static seat layout L2 for {}: {}", redisKey, ex.getMessage());
                }
            }
            created.complete(loaded);
            return loaded;
        } catch (Throwable ex) {
            created.completeExceptionally(ex);
            throw ex;
        } finally {
            layoutLoadInFlight.remove(cacheKey, created);
        }
    }

    private BizException layoutUnavailable() {
        return BizException.serviceUnavailable(ErrorCode.STOCK_UNAVAILABLE, 1000L, "座位图暂不可用，请稍后重试");
    }

    private List<SeatMapVO.SeatItem> buildNonSelectableSeats(List<StaticSeatRecord> seats) {
        List<SeatMapVO.SeatItem> items = new ArrayList<>(seats.size());
        for (StaticSeatRecord seat : seats) {
            items.add(SeatMapVO.SeatItem.builder()
                    .seatIndex(seat.getSeatIndex())
                    .rowNo(seat.getRowNo())
                    .colNo(seat.getColNo())
                    .segmentId(seat.getSegmentId())
                    .selectable(false)
                    .build());
        }
        return items;
    }

    private record SeatLayoutKey(long showId, String zoneId, int layoutVersion) {
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class CachedStaticZoneLayout {
        private long tierId;
        private int maxSeatIndex;
        private List<StaticSeatRecord> seats;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class StaticSeatRecord {
        private int seatIndex;
        private int rowNo;
        private int colNo;
        private String segmentId;
        private boolean physicallyEnabled;
    }
}
