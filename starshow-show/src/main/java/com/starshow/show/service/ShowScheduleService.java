package com.starshow.show.service;

import com.starshow.api.dto.ShowBasicDTO;
import com.starshow.api.facade.ReservationRescheduleFacade;
import com.starshow.api.facade.ShowReadFacade;
import com.starshow.common.constant.RedisKeys;
import com.starshow.common.exception.BizException;
import com.starshow.common.id.GeneSnowflakeIdGenerator;
import com.starshow.common.result.ErrorCode;
import com.starshow.show.cache.ShowDetailCacheService;
import com.starshow.show.cache.ShowGateEvaluator;
import com.starshow.show.config.ShowProperties;
import com.starshow.show.domain.entity.ShowDO;
import com.starshow.show.domain.entity.ShowTaskDO;
import com.starshow.show.lua.ShowRedisScripts;
import com.starshow.show.mapper.ShowMapper;
import com.starshow.show.mapper.ShowTaskMapper;
import com.starshow.show.util.ShowTimeUtil;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 演出开售排期安全变更服务（贯穿链路一预约提醒与链路二预热/门禁版本的权威配置源）：
 * 1. 将 INIT / PREHEATING / PREHEATED 纳入安全改期范围，已开售/已停售严禁改期重置库存；
 * 2. 与预热、开售共享场次操作锁 starshow:lock:show_op:{showId}；
 * 3. 先执行 show_block_for_reschedule.lua 原子校验未进入开售窗口并置旧版本为 BLOCKED，再执行 DB CAS 推进 version + 1；
 * 4. DB CAS 成功后取消旧版本未完成预热任务、发布新版本 INIT 到 Redis、覆盖刷新 L2 详情并失效 L1；
 * 5. 联动链路一 ReservationRescheduleFacade（monolith 本地调用 / distributed OpenFeign）重排预约提醒并下发 SCHEDULE_CHANGED 通知；
 *    若联动调用临时失败，落盘 t_show_task(RESCHEDULE_SYNC) 由恢复任务自动重试收敛。
 */
@Slf4j
@Service
public class ShowScheduleService {

    private static final Set<String> RESCHEDULABLE_STATUSES = Set.of("INIT", "PREHEATING", "PREHEATED");

    private final ShowMapper showMapper;
    private final ShowReadFacade showReadFacade;
    private final ShowTaskMapper showTaskMapper;
    private final ShowRedisScripts redisScripts;
    private final RedissonClient redissonClient;
    private final ShowDetailCacheService detailCacheService;
    private final ShowGateEvaluator gateEvaluator;
    private final ObjectProvider<ReservationRescheduleFacade> reservationRescheduleFacadeProvider;
    private final GeneSnowflakeIdGenerator idGenerator;
    private final ShowProperties properties;

    @Autowired
    public ShowScheduleService(ShowMapper showMapper,
                               ShowReadFacade showReadFacade,
                               ObjectProvider<ShowTaskMapper> showTaskMapperProvider,
                               ObjectProvider<ShowRedisScripts> redisScriptsProvider,
                               ObjectProvider<RedissonClient> redissonClientProvider,
                               ObjectProvider<ShowDetailCacheService> detailCacheServiceProvider,
                               ObjectProvider<ShowGateEvaluator> gateEvaluatorProvider,
                               ObjectProvider<ReservationRescheduleFacade> reservationRescheduleFacadeProvider,
                               ObjectProvider<GeneSnowflakeIdGenerator> idGeneratorProvider,
                               ObjectProvider<ShowProperties> propertiesProvider) {
        this.showMapper = showMapper;
        this.showReadFacade = showReadFacade;
        this.showTaskMapper = showTaskMapperProvider == null ? null : showTaskMapperProvider.getIfAvailable();
        this.redisScripts = redisScriptsProvider == null ? null : redisScriptsProvider.getIfAvailable();
        this.redissonClient = redissonClientProvider == null ? null : redissonClientProvider.getIfAvailable();
        this.detailCacheService = detailCacheServiceProvider == null ? null : detailCacheServiceProvider.getIfAvailable();
        this.gateEvaluator = gateEvaluatorProvider == null ? null : gateEvaluatorProvider.getIfAvailable();
        this.reservationRescheduleFacadeProvider = reservationRescheduleFacadeProvider;
        this.idGenerator = idGeneratorProvider == null ? null : idGeneratorProvider.getIfAvailable();
        this.properties = propertiesProvider == null ? new ShowProperties() : propertiesProvider.getIfAvailable(ShowProperties::new);
    }

    /**
     * 兼容 Phase 1 既有单测双参构造
     */
    public ShowScheduleService(ShowMapper showMapper, ShowReadFacade showReadFacade) {
        this.showMapper = showMapper;
        this.showReadFacade = showReadFacade;
        this.showTaskMapper = null;
        this.redisScripts = null;
        this.redissonClient = null;
        this.detailCacheService = null;
        this.gateEvaluator = null;
        this.reservationRescheduleFacadeProvider = null;
        this.idGenerator = null;
        this.properties = new ShowProperties();
    }

    /**
     * 主办方安全调整未开售场次开售时间
     */
    public ShowBasicDTO reschedule(long showId, LocalDateTime newSaleStartTime, LocalDateTime newSaleEndTime) {
        if (newSaleStartTime == null || newSaleEndTime == null || !newSaleEndTime.isAfter(newSaleStartTime)) {
            throw new BizException(ErrorCode.INVALID_PARAM, "停售时间必须晚于开售时间");
        }

        RLock opLock = acquireOpLock(showId);
        try {
            ShowDO current = showMapper.selectById(showId);
            if (current == null) {
                throw new BizException(ErrorCode.SHOW_NOT_FOUND);
            }
            String status = current.getSaleStatus() == null ? "" : current.getSaleStatus().toUpperCase();
            if (!RESCHEDULABLE_STATUSES.contains(status)) {
                throw new BizException(ErrorCode.SHOW_NOT_ON_SALE, "当前场次状态不允许改期: " + current.getSaleStatus());
            }

            int expectedVersion = current.getSaleConfigVersion() == null ? 1 : current.getSaleConfigVersion();
            long nowMs = System.currentTimeMillis();

            // 1. 先在 Redis 原子校验尚未进入开售窗口并将旧配置置为 BLOCKED，阻断在途预热与毫秒级开售
            if (redisScripts != null) {
                long blockCode = redisScripts.blockForReschedule(showId, expectedVersion, nowMs);
                if (blockCode == -1L) {
                    throw new BizException(ErrorCode.SHOW_NOT_ON_SALE, "场次已进入开售窗口或已开售，禁止改期重置库存");
                }
                if (blockCode == -2L) {
                    throw new BizException(ErrorCode.SHOW_CONFIG_VERSION_CHANGED, "场次配置版本已变化，请刷新后重试");
                }
            }

            // 2. 执行 DB 乐观 CAS 推进 sale_config_version + 1（PREHEATING / PREHEATED 回退 INIT）
            int updated = showMapper.casUpdateSchedule(showId, expectedVersion, newSaleStartTime, newSaleEndTime);
            if (updated != 1) {
                throw new BizException(ErrorCode.SHOW_NOT_ON_SALE, "场次状态已变更或存在并发改期冲突，请重试");
            }

            int newVersion = expectedVersion + 1;
            long newStartMs = ShowTimeUtil.toEpochMs(newSaleStartTime);
            long newEndMs = ShowTimeUtil.toEpochMs(newSaleEndTime);

            // 3. 取消旧版本未完成的预热任务
            if (showTaskMapper != null) {
                showTaskMapper.cancelOutdatedTasks(showId, ShowTaskDO.TYPE_PREHEAT, newVersion);
            }

            // 4. 在 Redis 发布新版本 INIT 状态与最新开售/停售时间戳，覆盖刷新 L2 详情并失效本地 L1/短库存缓存
            if (redisScripts != null) {
                try {
                    redisScripts.publishRescheduled(showId, newVersion, newStartMs, newEndMs, System.currentTimeMillis());
                } catch (Exception ex) {
                    log.warn("Failed to publish rescheduled ready state to Redis for showId={}, newVersion={}: {}",
                            showId, newVersion, ex.getMessage());
                }
            }
            if (detailCacheService != null) {
                try {
                    detailCacheService.refreshFromDb(showId);
                } catch (Exception ex) {
                    log.warn("Failed to refresh detail cache after reschedule for showId={}: {}", showId, ex.getMessage());
                    detailCacheService.invalidateLocal(showId);
                }
            }
            if (gateEvaluator != null) {
                gateEvaluator.invalidateLocalStockCache(showId);
            }

            // 5. 登记并执行改期联动提醒重排（monolith 本地直调 / distributed OpenFeign；失败由 t_show_task 恢复重试）
            syncReservationReminders(showId, newVersion);

            ShowBasicDTO latest = showReadFacade.getShowBasic(showId);
            log.info("Show schedule updated: showId={}, version {} -> {}, saleStartTime={}, saleEndTime={}",
                    showId, expectedVersion, latest == null ? newVersion : latest.effectiveSaleConfigVersion(),
                    newSaleStartTime, newSaleEndTime);
            return latest;
        } finally {
            releaseOpLock(opLock);
        }
    }

    /**
     * 供恢复调度器重试失败的 RESCHEDULE_SYNC 跨链路联动任务
     */
    public boolean retryRescheduleSync(ShowTaskDO task) {
        if (task == null || !ShowTaskDO.TYPE_RESCHEDULE_SYNC.equals(task.getTaskType())) {
            return false;
        }
        ReservationRescheduleFacade facade = reservationRescheduleFacadeProvider != null
                ? reservationRescheduleFacadeProvider.getIfAvailable()
                : null;
        if (facade == null) {
            return false;
        }
        LocalDateTime now = ShowTimeUtil.nowLocalDateTime();
        String execId = UUID.randomUUID().toString();
        if (showTaskMapper != null) {
            int acquired = showTaskMapper.casAcquireLease(task.getTaskId(), execId, now.plusSeconds(30), now);
            if (acquired != 1) {
                return false;
            }
        }
        try {
            facade.rescheduleShowReminders(task.getShowId());
            if (showTaskMapper != null) {
                showTaskMapper.casMarkSucceeded(task.getTaskId(), execId);
            }
            return true;
        } catch (Exception ex) {
            log.warn("Retry reschedule reminder sync failed for showId={}, version={}: {}",
                    task.getShowId(), task.getVersion(), ex.getMessage());
            if (showTaskMapper != null) {
                showTaskMapper.casMarkFailed(task.getTaskId(), execId, "SYNC_USER_REMINDERS", 1,
                        ShowTimeUtil.nowLocalDateTime().plusSeconds(15), ErrorCode.SYSTEM_ERROR.getCode(),
                        truncate(ex.getMessage(), 240));
            }
            return false;
        }
    }

    private void syncReservationReminders(long showId, int newVersion) {
        ReservationRescheduleFacade facade = reservationRescheduleFacadeProvider != null
                ? reservationRescheduleFacadeProvider.getIfAvailable()
                : null;
        if (facade == null) {
            return;
        }
        Long taskId = null;
        String execId = UUID.randomUUID().toString();
        if (showTaskMapper != null && idGenerator != null) {
            LocalDateTime now = ShowTimeUtil.nowLocalDateTime();
            ShowTaskDO syncTask = ShowTaskDO.builder()
                    .taskId(idGenerator.nextId())
                    .showId(showId)
                    .taskType(ShowTaskDO.TYPE_RESCHEDULE_SYNC)
                    .version(newVersion)
                    .executionId(execId)
                    .status(ShowTaskDO.STATUS_RUNNING)
                    .retryable(1)
                    .attempt(1)
                    .nextRetryAt(now)
                    .leaseUntil(now.plusSeconds(30))
                    .deleted(0)
                    .build();
            try {
                showTaskMapper.insert(syncTask);
                taskId = syncTask.getTaskId();
            } catch (DuplicateKeyException ex) {
                ShowTaskDO existing = showTaskMapper.selectByShowTypeVersion(showId, ShowTaskDO.TYPE_RESCHEDULE_SYNC, newVersion);
                if (existing != null) {
                    taskId = existing.getTaskId();
                }
            }
        }

        try {
            facade.rescheduleShowReminders(showId);
            if (showTaskMapper != null && taskId != null) {
                showTaskMapper.casMarkSucceeded(taskId, execId);
            }
        } catch (Exception ex) {
            log.warn("Cross-module reschedule reminder sync deferred for retry: showId={}, version={}, err={}",
                    showId, newVersion, ex.getMessage());
            if (showTaskMapper != null && taskId != null) {
                showTaskMapper.casMarkFailed(taskId, execId, "SYNC_USER_REMINDERS", 1,
                        ShowTimeUtil.nowLocalDateTime().plusSeconds(10), ErrorCode.SYSTEM_ERROR.getCode(),
                        truncate(ex.getMessage(), 240));
            }
        }
    }

    private RLock acquireOpLock(long showId) {
        if (redissonClient == null) {
            return null;
        }
        RLock lock = redissonClient.getLock(RedisKeys.showOpLock(showId));
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

    private void releaseOpLock(RLock lock) {
        if (lock != null && lock.isHeldByCurrentThread()) {
            try {
                lock.unlock();
            } catch (Exception ex) {
                log.warn("Failed to unlock show op lock: {}", ex.getMessage());
            }
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
