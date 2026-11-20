package com.starshow.show.service;

import com.starshow.common.constant.RedisKeys;
import com.starshow.show.cache.ShowDetailCacheService;
import com.starshow.show.config.ShowProperties;
import com.starshow.show.domain.entity.ShowDO;
import com.starshow.show.domain.model.ShowReadyState;
import com.starshow.show.lua.ShowRedisScripts;
import com.starshow.show.mapper.ShowMapper;
import com.starshow.show.util.ShowTimeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 链路二（节点 3）：开售与停售后台异步状态推进服务
 * - 注意：毫秒级准点开售已由 ShowGateEvaluator 直接依据 stage IN ('READY','OPEN') 与 [saleStartEpochMs, saleEndEpochMs) 实时开放，
 *   本任务绝不阻塞用户 20:00:00 准点抢票；
 * - 本任务负责：
 *   1. 将已 PREHEATED 且到点的场次异步 CAS 推进 DB 为 ON_SALE，并把 Redis stage 推进为 OPEN（锁定不可改期，已预热库存原样保留）；
 *   2. 兜底补齐 DB 已 ON_SALE 但 Redis stage 仍为 READY 的场次；
 *   3. 将到达 saleEndTime 的场次单向 CAS 推进为 ENDED（绝不重新开放已停售场次）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShowSaleLifecycleService {

    private final ShowMapper showMapper;
    private final ObjectProvider<ShowRedisScripts> redisScriptsProvider;
    private final ObjectProvider<RedissonClient> redissonClientProvider;
    private final ShowDetailCacheService detailCacheService;
    private final ShowProperties properties;

    @Scheduled(fixedDelayString = "${starshow.show.lifecycle-sync-interval-ms:5000}")
    public void scheduledSyncSaleLifecycle() {
        if (!properties.isLifecycleSyncEnabled()) {
            return;
        }
        try {
            syncSaleLifecycle();
        } catch (Exception ex) {
            log.warn("ShowSaleLifecycleService scheduled sync error: {}", ex.getMessage());
        }
    }

    /**
     * 执行一轮开售/停售异步状态收敛，返回本次推进场次总数
     */
    public int syncSaleLifecycle() {
        ShowRedisScripts scripts = redisScriptsProvider.getIfAvailable();
        if (scripts == null) {
            return 0;
        }
        LocalDateTime now = ShowTimeUtil.nowLocalDateTime();
        long nowMs = System.currentTimeMillis();
        int batchLimit = Math.max(10, properties.getTaskRecoveryBatchSize());
        int progressed = 0;

        // 1. PREHEATED 且到点 -> DB CAS ON_SALE + Redis stage=OPEN（原样保留已预热库存）
        List<ShowDO> dueForOpen = showMapper.selectDueForOnSale(now, batchLimit);
        if (dueForOpen != null) {
            for (ShowDO show : dueForOpen) {
                if (promoteToOnSale(show, scripts, nowMs)) {
                    progressed++;
                }
            }
        }

        // 2. DB 已 ON_SALE 但 Redis 仍为 READY -> 仅补推进 Redis stage=OPEN（不动库存）
        List<ShowDO> activeOnSale = showMapper.selectActiveOnSaleShows(now, batchLimit);
        if (activeOnSale != null) {
            for (ShowDO show : activeOnSale) {
                int ver = show.getSaleConfigVersion() == null ? 1 : show.getSaleConfigVersion();
                ShowReadyState ready = scripts.readReadyState(show.getId());
                if (ready != null && ShowReadyState.STAGE_READY.equals(ready.getStage())
                        && ready.getVersion() != null && ready.getVersion() == ver) {
                    if (scripts.markStage(show.getId(), ver, ShowReadyState.STAGE_OPEN, nowMs)) {
                        progressed++;
                    }
                }
            }
        }

        // 3. 到点停售 -> DB CAS ENDED + Redis stage=ENDED（单向不可逆）
        List<ShowDO> dueForEnd = showMapper.selectDueForEnded(now, batchLimit);
        if (dueForEnd != null) {
            for (ShowDO show : dueForEnd) {
                if (promoteToEnded(show, scripts, nowMs)) {
                    progressed++;
                }
            }
        }
        return progressed;
    }

    private boolean promoteToOnSale(ShowDO show, ShowRedisScripts scripts, long nowMs) {
        long showId = show.getId();
        int version = show.getSaleConfigVersion() == null ? 1 : show.getSaleConfigVersion();
        RLock lock = tryAcquireOpLock(showId);
        if (lock == null && redissonClientProvider.getIfAvailable() != null) {
            return false;
        }
        try {
            ShowReadyState ready = scripts.readReadyState(showId);
            if (ready == null || !ready.isReadyOrOpen()
                    || ready.getVersion() == null || ready.getVersion() != version) {
                return false;
            }
            int updated = showMapper.casMarkOnSale(showId, version);
            if (updated == 1) {
                scripts.markStage(showId, version, ShowReadyState.STAGE_OPEN, nowMs);
                detailCacheService.refreshFromDb(showId);
                log.info("Show promoted to ON_SALE: showId={}, version={}", showId, version);
                return true;
            }
            return false;
        } finally {
            releaseOpLock(lock);
        }
    }

    private boolean promoteToEnded(ShowDO show, ShowRedisScripts scripts, long nowMs) {
        long showId = show.getId();
        int version = show.getSaleConfigVersion() == null ? 1 : show.getSaleConfigVersion();
        RLock lock = tryAcquireOpLock(showId);
        if (lock == null && redissonClientProvider.getIfAvailable() != null) {
            return false;
        }
        try {
            int updated = showMapper.casMarkEnded(showId, version);
            if (updated == 1) {
                scripts.markStage(showId, version, ShowReadyState.STAGE_ENDED, nowMs);
                detailCacheService.refreshFromDb(showId);
                log.info("Show promoted to ENDED: showId={}, version={}", showId, version);
                return true;
            }
            return false;
        } finally {
            releaseOpLock(lock);
        }
    }

    private RLock tryAcquireOpLock(long showId) {
        RedissonClient redisson = redissonClientProvider.getIfAvailable();
        if (redisson == null) {
            return null;
        }
        RLock lock = redisson.getLock(RedisKeys.showOpLock(showId));
        try {
            return lock.tryLock(500L, properties.getShowOpLockLeaseMs(), TimeUnit.MILLISECONDS) ? lock : null;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private void releaseOpLock(RLock lock) {
        if (lock != null && lock.isHeldByCurrentThread()) {
            try {
                lock.unlock();
            } catch (Exception ex) {
                log.warn("Failed to unlock show op lock: {}", ex.getMessage());
            }
        }
    }
}
