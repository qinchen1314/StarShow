package com.starshow.show.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshow.common.constant.RedisKeys;
import com.starshow.common.exception.BizException;
import com.starshow.common.id.GeneSnowflakeIdGenerator;
import com.starshow.common.result.ErrorCode;
import com.starshow.show.cache.ShowCatalogBloomManager;
import com.starshow.show.cache.ShowDetailCacheService;
import com.starshow.show.cache.ShowGateEvaluator;
import com.starshow.show.config.ShowMetrics;
import com.starshow.show.config.ShowProperties;
import com.starshow.show.domain.entity.SeatDO;
import com.starshow.show.domain.entity.ShowDO;
import com.starshow.show.domain.entity.ShowTaskDO;
import com.starshow.show.domain.entity.TicketTierDO;
import com.starshow.show.domain.model.SeatLayoutMeta;
import com.starshow.show.domain.model.ShowReadyState;
import com.starshow.show.lua.ShowRedisScripts;
import com.starshow.show.mapper.SeatMapper;
import com.starshow.show.mapper.ShowMapper;
import com.starshow.show.mapper.ShowTaskMapper;
import com.starshow.show.mapper.TicketTierMapper;
import com.starshow.show.util.ShowTimeUtil;
import com.starshow.show.vo.ShowPreheatResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 链路二（节点 2）：版本化预热与 Fencing Token 防旧覆盖服务
 * 1. 按 (showId, saleConfigVersion) 幂等执行，同版本并发或重复调用收敛至同一条 t_show_task；
 * 2. 每次执行分配唯一 executionId (Fencing Token)，每批库存与分区 Bitmap 写入 Lua 均强制校验
 *    stage=PREPARING && version && executionId，失去租约的旧任务与旧版本任务一律原子拒绝（0 覆盖）；
 * 3. 全量回读 Redis 库存与 Bitmap 校验匹配后才原子发布 READY 并 DB CAS 为 PREHEATED；
 * 4. 严禁对已开售（ON_SALE / SOLD_OUT / ENDED 或已过开售时刻且已 READY）场次重复写入初始库存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShowPreheatService {

    private static final String SEAT_MODE_MANUAL = "MANUAL_SEAT";

    private final ShowMapper showMapper;
    private final TicketTierMapper ticketTierMapper;
    private final SeatMapper seatMapper;
    private final ShowTaskMapper showTaskMapper;
    private final ShowRedisScripts redisScripts;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectProvider<RedissonClient> redissonClientProvider;
    private final ShowDetailCacheService detailCacheService;
    private final ShowGateEvaluator gateEvaluator;
    private final ShowCatalogBloomManager bloomManager;
    private final GeneSnowflakeIdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final ShowProperties properties;
    private final ShowMetrics metrics;

    /**
     * 触发或查询指定版本的演出预热任务
     */
    public ShowPreheatResultVO preheat(long showId, Integer requestedVersion) {
        long startNs = System.nanoTime();
        ShowDO show = showMapper.selectById(showId);
        if (show == null) {
            throw new BizException(ErrorCode.SHOW_NOT_FOUND);
        }
        int currentVersion = show.getSaleConfigVersion() == null ? 1 : show.getSaleConfigVersion();
        if (requestedVersion != null && requestedVersion != currentVersion) {
            throw new BizException(
                    ErrorCode.SHOW_CONFIG_VERSION_CHANGED,
                    "预热版本过时：请求版本=" + requestedVersion + "，当前权威版本=" + currentVersion
            );
        }

        String saleStatus = show.getSaleStatus() == null ? "INIT" : show.getSaleStatus().toUpperCase();
        if ("ENDED".equals(saleStatus)) {
            throw new BizException(ErrorCode.SHOW_NOT_ON_SALE, "已停售场次不允许预热");
        }
        if ("ON_SALE".equals(saleStatus) || "SOLD_OUT".equals(saleStatus)) {
            // 已开售严禁重写初始库存：若同版本已预热成功，仅只读返回 READY；否则拒绝
            ShowTaskDO existing = showTaskMapper.selectByShowTypeVersion(showId, ShowTaskDO.TYPE_PREHEAT, currentVersion);
            if (existing != null && ShowTaskDO.STATUS_SUCCEEDED.equals(existing.getStatus())) {
                return ShowPreheatResultVO.builder()
                        .showId(showId)
                        .saleConfigVersion(currentVersion)
                        .taskStatus("READY")
                        .retryable(false)
                        .executionId(existing.getExecutionId())
                        .build();
            }
            throw new BizException(ErrorCode.SHOW_NOT_ON_SALE, "场次已开售，禁止执行初始库存预热覆盖");
        }

        // 获取或创建 (showId, PREHEAT, currentVersion) 唯一任务记录
        ShowTaskDO task = getOrCreateTask(showId, currentVersion);

        // 若任务已成功且 DB 已 PREHEATED 且 Redis 已 READY/OPEN，直接幂等返回
        if (ShowTaskDO.STATUS_SUCCEEDED.equals(task.getStatus()) && "PREHEATED".equals(saleStatus)) {
            ShowReadyState ready = redisScripts.readReadyState(showId);
            if (ready != null && ready.isReadyOrOpen()
                    && ready.getVersion() != null && ready.getVersion() == currentVersion) {
                return ShowPreheatResultVO.builder()
                        .showId(showId)
                        .saleConfigVersion(currentVersion)
                        .taskStatus("READY")
                        .retryable(false)
                        .executionId(task.getExecutionId())
                        .build();
            }
        }

        // 抢占执行租约（新分配唯一 Fencing Token: executionId）
        LocalDateTime now = ShowTimeUtil.nowLocalDateTime();
        String newExecutionId = UUID.randomUUID().toString();
        LocalDateTime leaseUntil = now.plusSeconds(Math.max(5L, properties.getPreheatLeaseSeconds()));
        int acquired = showTaskMapper.casAcquireLease(task.getTaskId(), newExecutionId, leaseUntil, now);
        if (acquired != 1) {
            ShowTaskDO latest = showTaskMapper.selectByShowTypeVersion(showId, ShowTaskDO.TYPE_PREHEAT, currentVersion);
            return mapTaskToResult(showId, currentVersion, latest);
        }

        return executeWithOpLock(showId, currentVersion, task.getTaskId(), newExecutionId, startNs);
    }

    /**
     * 供恢复调度器续跑指定的崩溃/失败预热任务
     */
    public ShowPreheatResultVO recoverTask(ShowTaskDO task) {
        if (task == null || !ShowTaskDO.TYPE_PREHEAT.equals(task.getTaskType())) {
            return null;
        }
        ShowDO show = showMapper.selectById(task.getShowId());
        if (show == null) {
            showTaskMapper.cancelOutdatedTasks(task.getShowId(), ShowTaskDO.TYPE_PREHEAT, Integer.MAX_VALUE);
            return null;
        }
        int currentVersion = show.getSaleConfigVersion() == null ? 1 : show.getSaleConfigVersion();
        if (task.getVersion() == null || task.getVersion() < currentVersion) {
            showTaskMapper.cancelOutdatedTasks(task.getShowId(), ShowTaskDO.TYPE_PREHEAT, currentVersion);
            return null;
        }
        String status = show.getSaleStatus() == null ? "" : show.getSaleStatus().toUpperCase();
        if ("ON_SALE".equals(status) || "SOLD_OUT".equals(status) || "ENDED".equals(status)) {
            return null;
        }
        return preheat(task.getShowId(), currentVersion);
    }

    private ShowPreheatResultVO executeWithOpLock(long showId, int version, long taskId,
                                                  String executionId, long startNs) {
        RLock opLock = acquireShowOpLock(showId);
        try {
            return executePreheatBody(showId, version, taskId, executionId, startNs);
        } finally {
            releaseShowOpLock(opLock);
        }
    }

    private ShowPreheatResultVO executePreheatBody(long showId, int version, long taskId,
                                                   String executionId, long startNs) {
        String failedStage = "VALIDATE";
        try {
            ShowDO latestShow = showMapper.selectById(showId);
            if (latestShow == null) {
                return failTask(showId, version, taskId, executionId, "SHOW_MISSING", false,
                        ErrorCode.SHOW_NOT_FOUND.getCode(), "演出不存在", startNs);
            }
            int dbVersion = latestShow.getSaleConfigVersion() == null ? 1 : latestShow.getSaleConfigVersion();
            if (dbVersion != version) {
                return failTask(showId, version, taskId, executionId, "VERSION_CHANGED", false,
                        ErrorCode.SHOW_CONFIG_VERSION_CHANGED.getCode(),
                        "版本已变更为 " + dbVersion + "，中止旧版本预热", startNs);
            }
            String dbStatus = latestShow.getSaleStatus() == null ? "INIT" : latestShow.getSaleStatus().toUpperCase();
            if ("ON_SALE".equals(dbStatus) || "SOLD_OUT".equals(dbStatus) || "ENDED".equals(dbStatus)) {
                return failTask(showId, version, taskId, executionId, "ALREADY_ON_SALE", false,
                        ErrorCode.SHOW_NOT_ON_SALE.getCode(), "场次已开售或结束，禁止预热写入", startNs);
            }

            long saleStartMs = ShowTimeUtil.toEpochMs(latestShow.getSaleStartTime());
            long saleEndMs = ShowTimeUtil.toEpochMs(latestShow.getSaleEndTime());
            long nowMs = System.currentTimeMillis();

            // 1. DB CAS: INIT / PREHEATING -> PREHEATING（若已 PREHEATED 但 Redis 未就绪则允许修复校验）
            failedStage = "DB_MARK_PREHEATING";
            if ("INIT".equals(dbStatus) || "PREHEATING".equals(dbStatus)) {
                int marked = showMapper.casMarkPreheating(showId, version);
                if (marked != 1) {
                    return failTask(showId, version, taskId, executionId, failedStage, true,
                            ErrorCode.SHOW_CONFIG_VERSION_CHANGED.getCode(), "DB CAS PREHEATING 失败", startNs);
                }
            }

            // 2. Redis preheat_begin.lua：原子校验未开售/未升版本并抢占 executionId
            failedStage = "REDIS_BEGIN";
            long beginCode = redisScripts.preheatBegin(showId, version, executionId, saleStartMs, saleEndMs, nowMs);
            if (beginCode == -1L) {
                return failTask(showId, version, taskId, executionId, "REDIS_ALREADY_OPEN", false,
                        ErrorCode.SHOW_NOT_ON_SALE.getCode(), "Redis 门禁已进入在售窗口或停售，拒绝覆盖", startNs);
            }
            if (beginCode == -2L) {
                return failTask(showId, version, taskId, executionId, "REDIS_NEWER_VERSION", false,
                        ErrorCode.SHOW_CONFIG_VERSION_CHANGED.getCode(), "Redis 已存在更高版本，拒绝旧版本预热", startNs);
            }
            if (beginCode == 2L) {
                // Redis 同版本已 READY（例如上次提交 READY 后 DB CAS 前崩溃）：直接补齐 DB CAS 即可，不重写库存
                failedStage = "DB_CATCHUP_PREHEATED";
                if (!"PREHEATED".equals(dbStatus)) {
                    showMapper.casMarkPreheated(showId, version);
                }
                detailCacheService.refreshFromDb(showId);
                gateEvaluator.invalidateLocalStockCache(showId);
                bloomManager.registerShowId(showId);
                showTaskMapper.casMarkSucceeded(taskId, executionId);
                metrics.recordPreheatSuccess(elapsedMs(startNs));
                return ShowPreheatResultVO.builder()
                        .showId(showId)
                        .saleConfigVersion(version)
                        .taskStatus("READY")
                        .retryable(false)
                        .executionId(executionId)
                        .build();
            }
            if (beginCode != 1L) {
                return failTask(showId, version, taskId, executionId, failedStage, true,
                        ErrorCode.SYSTEM_ERROR.getCode(), "preheat_begin 返回异常码: " + beginCode, startNs);
            }

            // 3. 构建权威预热清单（票档库存 + 自主选座分区 Bitmap/Layout）
            failedStage = "BUILD_MANIFEST";
            List<TicketTierDO> tiers = ticketTierMapper.selectByShowId(showId);
            if (tiers == null || tiers.isEmpty()) {
                return failTask(showId, version, taskId, executionId, failedStage, false,
                        ErrorCode.TIER_NOT_FOUND.getCode(), "场次无有效票档配置", startNs);
            }

            boolean manualSeat = SEAT_MODE_MANUAL.equalsIgnoreCase(latestShow.getSeatMode());
            Map<String, ZonePreheatPlan> zonePlans = manualSeat
                    ? buildZonePlans(showId, version)
                    : Collections.emptyMap();

            Map<Long, Integer> expectedTierStocks = buildExpectedTierStocks(tiers, manualSeat, zonePlans);

            // 4. 分批带 Fencing Token 写入票档库存
            failedStage = "WRITE_STOCK";
            int batchSize = Math.max(1, properties.getPreheatBatchSize());
            List<Map.Entry<Long, Integer>> stockEntries = new ArrayList<>(expectedTierStocks.entrySet());
            for (int i = 0; i < stockEntries.size(); i += batchSize) {
                Map<Long, Integer> batch = new LinkedHashMap<>();
                int end = Math.min(i + batchSize, stockEntries.size());
                for (int j = i; j < end; j++) {
                    batch.put(stockEntries.get(j).getKey(), stockEntries.get(j).getValue());
                }
                boolean ok = redisScripts.preheatWriteStockBatch(showId, version, executionId, batch);
                if (!ok) {
                    return failTask(showId, version, taskId, executionId, "FENCING_REJECTED_STOCK", true,
                            ErrorCode.SHOW_CONFIG_VERSION_CHANGED.getCode(), "写入库存时失去租约或版本已变更", startNs);
                }
            }

            // 5. 自主选座：带 Fencing Token 逐分区写入 Bitmap 与 Layout
            failedStage = "WRITE_SEAT_BITMAP";
            for (ZonePreheatPlan plan : zonePlans.values()) {
                String layoutJson = objectMapper.writeValueAsString(plan.meta);
                boolean ok = redisScripts.preheatWriteSeatZone(
                        showId, plan.zoneId, version, executionId, plan.bitmapBytes, layoutJson);
                if (!ok) {
                    return failTask(showId, version, taskId, executionId, "FENCING_REJECTED_SEAT", true,
                            ErrorCode.SHOW_CONFIG_VERSION_CHANGED.getCode(), "写入座位图时失去租约或版本已变更", startNs);
                }
            }

            // 6. 全量回读校验（确保 Redis 库存与位图与清单逐项 100% 匹配后才发布 READY）
            failedStage = "VERIFY_STOCK";
            for (Map.Entry<Long, Integer> entry : expectedTierStocks.entrySet()) {
                String val = stringRedisTemplate.opsForValue().get(RedisKeys.stock(showId, entry.getKey()));
                if (val == null || !val.equals(String.valueOf(entry.getValue()))) {
                    return failTask(showId, version, taskId, executionId, failedStage, true,
                            ErrorCode.SYSTEM_ERROR.getCode(),
                            "回读票档库存不匹配: tierId=" + entry.getKey() + ", expected=" + entry.getValue() + ", actual=" + val,
                            startNs);
                }
            }

            failedStage = "VERIFY_SEAT_BITMAP";
            for (ZonePreheatPlan plan : zonePlans.values()) {
                byte[] actualBitmap = redisScripts.readRawSeatBitmap(showId, plan.zoneId);
                if (actualBitmap == null || !Arrays.equals(plan.bitmapBytes, actualBitmap)) {
                    return failTask(showId, version, taskId, executionId, failedStage, true,
                            ErrorCode.SYSTEM_ERROR.getCode(), "回读分区位图不匹配: zoneId=" + plan.zoneId, startNs);
                }
                String actualLayoutJson = redisScripts.readSeatLayoutJson(showId, plan.zoneId);
                if (actualLayoutJson == null) {
                    return failTask(showId, version, taskId, executionId, failedStage, true,
                            ErrorCode.SYSTEM_ERROR.getCode(), "回读分区布局元数据缺失: zoneId=" + plan.zoneId, startNs);
                }
                SeatLayoutMeta actualMeta = objectMapper.readValue(actualLayoutJson, SeatLayoutMeta.class);
                if (actualMeta.getVersion() == null || actualMeta.getVersion() != version
                        || actualMeta.getByteLength() != plan.meta.getByteLength()) {
                    return failTask(showId, version, taskId, executionId, failedStage, true,
                            ErrorCode.SYSTEM_ERROR.getCode(), "回读分区布局版本不匹配: zoneId=" + plan.zoneId, startNs);
                }
            }

            // 7. 原子发布 Redis READY（校验 Fencing Token，固化 saleStartEpochMs / saleEndEpochMs）
            failedStage = "COMMIT_REDIS_READY";
            boolean committed = redisScripts.preheatCommitReady(
                    showId, version, executionId, saleStartMs, saleEndMs, System.currentTimeMillis());
            if (!committed) {
                return failTask(showId, version, taskId, executionId, "FENCING_REJECTED_COMMIT", true,
                        ErrorCode.SHOW_CONFIG_VERSION_CHANGED.getCode(), "提交 READY 时失去租约或已被改期", startNs);
            }

            // 8. DB CAS: PREHEATING -> PREHEATED
            failedStage = "DB_MARK_PREHEATED";
            if (!"PREHEATED".equals(dbStatus)) {
                int updated = showMapper.casMarkPreheated(showId, version);
                if (updated != 1) {
                    ShowDO check = showMapper.selectById(showId);
                    if (check == null || check.getSaleConfigVersion() != version
                            || (!"PREHEATED".equals(check.getSaleStatus()) && !"ON_SALE".equals(check.getSaleStatus()))) {
                        return failTask(showId, version, taskId, executionId, failedStage, true,
                                ErrorCode.SYSTEM_ERROR.getCode(), "DB CAS PREHEATED 失败", startNs);
                    }
                }
            }

            // 9. 刷新详情 L2 缓存 + 同步目录布隆 + 标记任务成功
            detailCacheService.refreshFromDb(showId);
            gateEvaluator.invalidateLocalStockCache(showId);
            bloomManager.registerShowId(showId);
            showTaskMapper.casMarkSucceeded(taskId, executionId);

            long durationMs = elapsedMs(startNs);
            metrics.recordPreheatSuccess(durationMs);
            log.info("Show preheat READY: showId={}, version={}, executionId={}, tiers={}, zones={}, durationMs={}",
                    showId, version, executionId, expectedTierStocks.size(), zonePlans.size(), durationMs);

            return ShowPreheatResultVO.builder()
                    .showId(showId)
                    .saleConfigVersion(version)
                    .taskStatus("READY")
                    .failedStage(null)
                    .retryable(false)
                    .executionId(executionId)
                    .build();
        } catch (Exception ex) {
            log.error("Show preheat failed at stage={}: showId={}, version={}, executionId={}",
                    failedStage, showId, version, executionId, ex);
            return failTask(showId, version, taskId, executionId, failedStage, true,
                    ErrorCode.SYSTEM_ERROR.getCode(), truncate(ex.getMessage(), 240), startNs);
        }
    }

    private Map<String, ZonePreheatPlan> buildZonePlans(long showId, int version) {
        List<SeatDO> allSeats = seatMapper.selectByShowId(showId);
        if (allSeats == null || allSeats.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, List<SeatDO>> byZone = new LinkedHashMap<>();
        for (SeatDO seat : allSeats) {
            byZone.computeIfAbsent(seat.getZoneId(), k -> new ArrayList<>()).add(seat);
        }
        Map<String, ZonePreheatPlan> plans = new LinkedHashMap<>();
        for (Map.Entry<String, List<SeatDO>> entry : byZone.entrySet()) {
            String zoneId = entry.getKey();
            List<SeatDO> zoneSeats = entry.getValue();
            int maxSeatIndex = zoneSeats.stream().mapToInt(SeatDO::getSeatIndex).max().orElse(0);
            int byteLen = ShowRedisScripts.requiredBitmapBytes(maxSeatIndex);
            byte[] bitmap = new byte[byteLen];
            int selectableCount = 0;
            for (SeatDO seat : zoneSeats) {
                if (seat.getStatus() != null && seat.getStatus() != 0) {
                    ShowRedisScripts.setBitMsb(bitmap, seat.getSeatIndex());
                } else {
                    selectableCount++;
                }
            }
            long tierId = zoneSeats.get(0).getTierId();
            SeatLayoutMeta meta = SeatLayoutMeta.builder()
                    .showId(showId)
                    .zoneId(zoneId)
                    .tierId(tierId)
                    .version(version)
                    .maxSeatIndex(maxSeatIndex)
                    .byteLength(byteLen)
                    .totalSeatCount(zoneSeats.size())
                    .initialSelectableCount(selectableCount)
                    .build();
            plans.put(zoneId, new ZonePreheatPlan(zoneId, tierId, bitmap, meta));
        }
        return plans;
    }

    private Map<Long, Integer> buildExpectedTierStocks(List<TicketTierDO> tiers, boolean manualSeat,
                                                       Map<String, ZonePreheatPlan> zonePlans) {
        Map<Long, Integer> tierSeatSelectable = new LinkedHashMap<>();
        if (manualSeat && !zonePlans.isEmpty()) {
            for (ZonePreheatPlan plan : zonePlans.values()) {
                tierSeatSelectable.merge(plan.tierId, plan.meta.getInitialSelectableCount(), Integer::sum);
            }
        }
        Map<Long, Integer> stocks = new LinkedHashMap<>();
        for (TicketTierDO tier : tiers) {
            int initStock;
            if (manualSeat && tierSeatSelectable.containsKey(tier.getId())) {
                initStock = tierSeatSelectable.get(tier.getId());
            } else {
                initStock = tier.getAvailableStock() == null ? 0 : Math.max(0, tier.getAvailableStock());
            }
            stocks.put(tier.getId(), initStock);
        }
        return stocks;
    }

    private ShowTaskDO getOrCreateTask(long showId, int version) {
        ShowTaskDO existing = showTaskMapper.selectByShowTypeVersion(showId, ShowTaskDO.TYPE_PREHEAT, version);
        if (existing != null) {
            return existing;
        }
        LocalDateTime now = ShowTimeUtil.nowLocalDateTime();
        ShowTaskDO created = ShowTaskDO.builder()
                .taskId(idGenerator.nextId())
                .showId(showId)
                .taskType(ShowTaskDO.TYPE_PREHEAT)
                .version(version)
                .executionId("")
                .status(ShowTaskDO.STATUS_PENDING)
                .retryable(1)
                .attempt(0)
                .nextRetryAt(now)
                .deleted(0)
                .build();
        try {
            showTaskMapper.insert(created);
            return created;
        } catch (DuplicateKeyException ex) {
            ShowTaskDO raced = showTaskMapper.selectByShowTypeVersion(showId, ShowTaskDO.TYPE_PREHEAT, version);
            if (raced != null) {
                return raced;
            }
            throw ex;
        }
    }

    private ShowPreheatResultVO failTask(long showId, int version, long taskId, String executionId,
                                         String failedStage, boolean retryable,
                                         String errorCode, String errorMsg, long startNs) {
        LocalDateTime nextRetry = ShowTimeUtil.nowLocalDateTime().plusSeconds(10);
        showTaskMapper.casMarkFailed(taskId, executionId, failedStage, retryable ? 1 : 0, nextRetry, errorCode, errorMsg);
        metrics.recordPreheatFailure(elapsedMs(startNs));
        return ShowPreheatResultVO.builder()
                .showId(showId)
                .saleConfigVersion(version)
                .taskStatus("FAILED")
                .failedStage(failedStage)
                .retryable(retryable)
                .executionId(executionId)
                .build();
    }

    private ShowPreheatResultVO mapTaskToResult(long showId, int version, ShowTaskDO task) {
        if (task == null) {
            return ShowPreheatResultVO.builder()
                    .showId(showId)
                    .saleConfigVersion(version)
                    .taskStatus("PREPARING")
                    .retryable(true)
                    .build();
        }
        String mappedStatus = switch (task.getStatus()) {
            case ShowTaskDO.STATUS_SUCCEEDED -> "READY";
            case ShowTaskDO.STATUS_FAILED, ShowTaskDO.STATUS_CANCELLED -> "FAILED";
            default -> "PREPARING";
        };
        return ShowPreheatResultVO.builder()
                .showId(showId)
                .saleConfigVersion(version)
                .taskStatus(mappedStatus)
                .failedStage(task.getFailedStage())
                .retryable(task.getRetryable() != null && task.getRetryable() == 1)
                .executionId(task.getExecutionId())
                .build();
    }

    private RLock acquireShowOpLock(long showId) {
        RedissonClient redisson = redissonClientProvider.getIfAvailable();
        if (redisson == null) {
            return null;
        }
        RLock lock = redisson.getLock(RedisKeys.showOpLock(showId));
        try {
            boolean locked = lock.tryLock(properties.getShowOpLockWaitMs(), properties.getShowOpLockLeaseMs(), TimeUnit.MILLISECONDS);
            if (!locked) {
                throw new BizException(ErrorCode.LOCK_ACQUIRE_FAILED, "场次操作锁争用，请稍后重试");
            }
            return lock;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.LOCK_ACQUIRE_FAILED, "获取场次操作锁中断");
        }
    }

    private void releaseShowOpLock(RLock lock) {
        if (lock != null && lock.isHeldByCurrentThread()) {
            try {
                lock.unlock();
            } catch (Exception ex) {
                log.warn("Failed to unlock show op lock: {}", ex.getMessage());
            }
        }
    }

    private long elapsedMs(long startNs) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startNs));
    }

    private String truncate(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    private record ZonePreheatPlan(String zoneId, long tierId, byte[] bitmapBytes, SeatLayoutMeta meta) {
    }
}
