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
import com.starshow.show.queue.WaitingRoomScheduleProjection;
import com.starshow.show.util.ShowTimeUtil;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 演出开售排期安全变更服务（贯穿链路一预约提醒与链路二预热/门禁版本的权威配置源）：
 * 1. 将 INIT / PREHEATING / PREHEATED 纳入安全改期范围，已开售/已停售严禁改期重置库存；
 * 2. 与预热、开售共享场次操作锁 starshow:lock:show_op:{showId}；
 * 3. 先执行 show_block_for_reschedule.lua 原子校验未进入开售窗口并将旧配置置为 BLOCKED；
 * 4. 在同一个 MySQL 本地事务内原子提交：DB CAS 推进 version + 1、取消旧版本未完成任务、登记 RESCHEDULE_SYNC(PENDING) 持久待办；
 *    物理杜绝“DB 已升版本但恢复任务为 0 条”的崩溃丢失窗口；
 * 5. 事务提交后统一走 executeRescheduleSyncTask（与 ShowTaskRecoveryScheduler 崩溃续跑共用完全一致的幂等收敛路径）：
 *    发布 Redis 新版本 INIT 与最新开售时间 -> 覆盖刷新详情 L2 并失效本地缓存 -> 联动链路一重排提醒并下发 SCHEDULE_CHANGED 通知；
 *    任一步失败均可由后台恢复器自动重试直至全部收敛。
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
    private final TransactionTemplate transactionTemplate;
    private WaitingRoomScheduleProjection waitingRoom;

    @Autowired(required = false)
    void setWaitingRoom(WaitingRoomScheduleProjection waitingRoom) {
        this.waitingRoom = waitingRoom;
    }

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
                               ObjectProvider<ShowProperties> propertiesProvider,
                               ObjectProvider<TransactionTemplate> transactionTemplateProvider) {
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
        this.transactionTemplate = transactionTemplateProvider == null ? null : transactionTemplateProvider.getIfAvailable();
    }

    /**
     * 兼容既有 10 参单测构造
     */
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
        this(showMapper, showReadFacade, showTaskMapperProvider, redisScriptsProvider, redissonClientProvider,
                detailCacheServiceProvider, gateEvaluatorProvider, reservationRescheduleFacadeProvider,
                idGeneratorProvider, propertiesProvider, null);
    }

    /**
     * 兼容 Phase 1 既有双参单测构造
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
        this.transactionTemplate = null;
    }

    /**
     * 主办方安全调整未开售场次开售时间
     */
    public ShowBasicDTO reschedule(long showId, LocalDateTime newSaleStartTime, LocalDateTime newSaleEndTime) {
        RLock opLock = acquireOpLock(showId);
        try {
            RescheduleCommitResult committed = commitRescheduleWithOutbox(showId, newSaleStartTime, newSaleEndTime);
            // 事务提交后立即执行同步收敛（即使此处进程崩溃，t_show_task 中的 PENDING 待办也可由恢复调度器完整续跑）
            executeRescheduleSyncInternal(
                    committed.showId(),
                    committed.newVersion(),
                    committed.syncTask(),
                    newSaleStartTime,
                    newSaleEndTime
            );
            ShowBasicDTO latest = showReadFacade.getShowBasic(showId);
            log.info("Show schedule updated: showId={}, version {} -> {}, saleStartTime={}, saleEndTime={}",
                    showId, committed.previousVersion(),
                    latest == null ? committed.newVersion() : latest.effectiveSaleConfigVersion(),
                    newSaleStartTime, newSaleEndTime);
            return latest;
        } finally {
            releaseOpLock(opLock);
        }
    }

    /**
     * 仅执行“Redis BLOCKED + 同事务 DB CAS 与 RESCHEDULE_SYNC 待办落库”（同事务原子提交边界）
     */
    public RescheduleCommitResult commitRescheduleWithOutbox(long showId,
                                                             LocalDateTime newSaleStartTime,
                                                             LocalDateTime newSaleEndTime) {
        if (!hasWaitingRoom(showId)) {
            return commitReschedule(showId, newSaleStartTime, newSaleEndTime);
        }
        RLock lock = acquireOpLock(showId);
        try {
            return commitReschedule(showId, newSaleStartTime, newSaleEndTime);
        } finally {
            releaseOpLock(lock);
        }
    }

    private RescheduleCommitResult commitReschedule(long showId,
                                                    LocalDateTime newSaleStartTime,
                                                    LocalDateTime newSaleEndTime) {
        if (newSaleStartTime == null || newSaleEndTime == null || !newSaleEndTime.isAfter(newSaleStartTime)) {
            throw new BizException(ErrorCode.INVALID_PARAM, "停售时间必须晚于开售时间");
        }
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

        if (hasWaitingRoom(showId)) {
            waitingRoom.pause(current);
        }

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

        // 2. 同一个 MySQL 本地事务内原子提交：DB CAS 改期 + 取消旧版本未完成任务 + 持久化 RESCHEDULE_SYNC(PENDING) 待办
        return executeInTransaction(() -> {
            int updated = showMapper.casUpdateSchedule(showId, expectedVersion, newSaleStartTime, newSaleEndTime);
            if (updated != 1) {
                throw new BizException(ErrorCode.SHOW_NOT_ON_SALE, "场次状态已变更或存在并发改期冲突，请重试");
            }
            int newVersion = expectedVersion + 1;
            ShowTaskDO syncTask = null;
            if (showTaskMapper != null) {
                showTaskMapper.cancelOutdatedTasks(showId, ShowTaskDO.TYPE_PREHEAT, newVersion);
                showTaskMapper.cancelOutdatedTasks(showId, ShowTaskDO.TYPE_RESCHEDULE_SYNC, newVersion);
                if (idGenerator != null) {
                    LocalDateTime immediateRetryAt = ShowTimeUtil.nowLocalDateTime()
                            .truncatedTo(ChronoUnit.SECONDS)
                            .minusSeconds(1);
                    ShowTaskDO pendingTask = ShowTaskDO.builder()
                            .taskId(idGenerator.nextId())
                            .showId(showId)
                            .taskType(ShowTaskDO.TYPE_RESCHEDULE_SYNC)
                            .version(newVersion)
                            .executionId("")
                            .status(ShowTaskDO.STATUS_PENDING)
                            .retryable(1)
                            .attempt(0)
                            .nextRetryAt(immediateRetryAt)
                            .deleted(0)
                            .build();
                    try {
                        showTaskMapper.insert(pendingTask);
                        syncTask = pendingTask;
                    } catch (DuplicateKeyException dup) {
                        syncTask = showTaskMapper.selectByShowTypeVersion(showId, ShowTaskDO.TYPE_RESCHEDULE_SYNC, newVersion);
                    }
                }
            }
            return new RescheduleCommitResult(showId, expectedVersion, newVersion, syncTask);
        });
    }

    /**
     * 供恢复调度器续跑中断或失败的 RESCHEDULE_SYNC 任务：
     * 完整收敛 Redis 新版本时间、L2 详情刷新与链路一预约提醒重排。
     */
    public boolean retryRescheduleSync(ShowTaskDO task) {
        if (task == null || !ShowTaskDO.TYPE_RESCHEDULE_SYNC.equals(task.getTaskType())) {
            return false;
        }
        ShowDO latest = showMapper.selectById(task.getShowId());
        if (latest == null) {
            if (showTaskMapper != null) {
                showTaskMapper.cancelOutdatedTasks(task.getShowId(), ShowTaskDO.TYPE_RESCHEDULE_SYNC, Integer.MAX_VALUE);
            }
            return false;
        }
        int dbVersion = latest.getSaleConfigVersion() == null ? 1 : latest.getSaleConfigVersion();
        if (task.getVersion() != null && task.getVersion() < dbVersion) {
            if (showTaskMapper != null) {
                showTaskMapper.cancelOutdatedTasks(task.getShowId(), ShowTaskDO.TYPE_RESCHEDULE_SYNC, dbVersion);
            }
            return true;
        }
        return executeRescheduleSyncInternal(
                task.getShowId(),
                dbVersion,
                task,
                latest.getSaleStartTime(),
                latest.getSaleEndTime()
        );
    }

    private boolean executeRescheduleSyncInternal(long showId,
                                                  int targetVersion,
                                                  ShowTaskDO syncTask,
                                                  LocalDateTime saleStartTime,
                                                  LocalDateTime saleEndTime) {
        String execId = UUID.randomUUID().toString();
        Long taskId = syncTask == null ? null : syncTask.getTaskId();
        if (showTaskMapper != null && taskId != null) {
            LocalDateTime now = ShowTimeUtil.nowLocalDateTime();
            int acquired = showTaskMapper.casAcquireLease(taskId, execId, now.plusSeconds(30), now);
            if (acquired != 1) {
                return false;
            }
        }

        String stage = "PUBLISH_REDIS";
        try {
            long newStartMs = ShowTimeUtil.toEpochMs(saleStartTime);
            long newEndMs = ShowTimeUtil.toEpochMs(saleEndTime);

            // Step A: 发布新版本 INIT 状态与最新开售/停售时间戳到 Redis
            if (redisScripts != null) {
                redisScripts.publishRescheduled(showId, targetVersion, newStartMs, newEndMs, System.currentTimeMillis());
            }
            stage = "PUBLISH_WAITING_ROOM";
            if (hasWaitingRoom(showId)) {
                waitingRoom.publishCommitted(showId, targetVersion, saleStartTime, saleEndTime);
            }

            // Step B: 覆盖刷新详情 L2 并清理本机 L1 / 权威短缓存 / 短库存缓存
            stage = "REFRESH_DETAIL_CACHE";
            if (detailCacheService != null) {
                detailCacheService.refreshFromDb(showId);
            }
            if (gateEvaluator != null) {
                gateEvaluator.invalidateLocalStockCache(showId);
            }

            // Step C: 联动链路一重排预约提醒并下发 SCHEDULE_CHANGED 站内通知
            stage = "SYNC_USER_REMINDERS";
            ReservationRescheduleFacade facade = reservationRescheduleFacadeProvider != null
                    ? reservationRescheduleFacadeProvider.getIfAvailable()
                    : null;
            if (facade != null) {
                facade.rescheduleShowReminders(showId);
            }

            if (showTaskMapper != null && taskId != null) {
                showTaskMapper.casMarkSucceeded(taskId, execId);
            }
            return true;
        } catch (Exception ex) {
            log.warn("Reschedule sync deferred at stage={} for showId={}, version={}: {}",
                    stage, showId, targetVersion, ex.getMessage());
            if (detailCacheService != null) {
                detailCacheService.invalidateLocal(showId);
            }
            if (gateEvaluator != null) {
                gateEvaluator.invalidateLocalStockCache(showId);
            }
            if (showTaskMapper != null && taskId != null) {
                showTaskMapper.casMarkFailed(
                        taskId,
                        execId,
                        stage,
                        1,
                        ShowTimeUtil.nowLocalDateTime().truncatedTo(ChronoUnit.SECONDS).plusSeconds(5),
                        ErrorCode.SYSTEM_ERROR.getCode(),
                        truncate(ex.getMessage(), 240)
                );
            }
            return false;
        }
    }

    private <T> T executeInTransaction(java.util.function.Supplier<T> action) {
        if (transactionTemplate == null) {
            return action.get();
        }
        return transactionTemplate.execute(status -> action.get());
    }

    private RLock acquireOpLock(long showId) {
        if (redissonClient == null) {
            if (hasWaitingRoom(showId)) {
                throw BizException.serviceUnavailable(ErrorCode.QUEUE_UNAVAILABLE, 2000, "场次操作锁暂不可用，请稍后重试");
            }
            return null;
        }
        RLock lock = redissonClient.getLock(RedisKeys.showOpLock(showId));
        try {
            boolean locked = hasWaitingRoom(showId)
                    ? lock.tryLock(properties.getShowOpLockWaitMs(), TimeUnit.MILLISECONDS)
                    : lock.tryLock(properties.getShowOpLockWaitMs(), properties.getShowOpLockLeaseMs(), TimeUnit.MILLISECONDS);
            if (!locked) {
                throw new BizException(ErrorCode.LOCK_ACQUIRE_FAILED, "场次操作锁争用，请稍后重试");
            }
            return lock;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.LOCK_ACQUIRE_FAILED, "获取场次操作锁中断");
        }
    }

    private boolean hasWaitingRoom(long showId) {
        return waitingRoom != null && waitingRoom.includes(showId);
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

    public record RescheduleCommitResult(long showId, int previousVersion, int newVersion, ShowTaskDO syncTask) {
    }
}
