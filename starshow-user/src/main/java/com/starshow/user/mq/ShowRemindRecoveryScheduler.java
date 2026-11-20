package com.starshow.user.mq;

import com.starshow.api.dto.ShowBasicDTO;
import com.starshow.api.facade.ShowReadFacade;
import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.user.config.ReservationProperties;
import com.starshow.user.domain.entity.ReservationDO;
import com.starshow.user.mapper.ReservationMapper;
import com.starshow.user.service.UserNotificationService;
import com.starshow.user.vo.ShowRescheduleResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 链路一（节点 4）：开票延迟提醒入窗补投、失败恢复与改期重排调度器（ISSUE-004 / ISSUE-009 / Phase 1.1）
 * 1. 按联合索引 idx_remind_retry (remind_status, next_retry_time, id) 拉取 next_retry_time <= now 的到期候选；
 * 2. 以 id > afterId 游标单轮连续推进多批（上限 remindRecoveryMaxBatchesPerRun），杜绝超窗记录造成队头饥饿；
 * 3. 单轮内按 showId 缓存演出元数据，并对单场次 ShowReadFacade 查询异常做独立隔离；
 * 4. 支持主办方改期后按最新 saleConfigVersion 重置该场次预约、即时落盘 SCHEDULE_CHANGED 变更通知并立即联动重排提醒。
 */
@Slf4j
@Component
public class ShowRemindRecoveryScheduler {

    private static final String SHOW_STATUS_ENDED = "ENDED";
    private static final Object SHOW_LOOKUP_FAILED = new Object();
    private static final Object SHOW_NOT_FOUND = new Object();

    private final ReservationMapper reservationMapper;
    private final ShowReadFacade showReadFacade;
    private final ShowRemindProducer showRemindProducer;
    private final ReservationProperties properties;
    private final UserNotificationService userNotificationService;

    @Autowired
    public ShowRemindRecoveryScheduler(ReservationMapper reservationMapper,
                                       ShowReadFacade showReadFacade,
                                       ShowRemindProducer showRemindProducer,
                                       ReservationProperties properties,
                                       UserNotificationService userNotificationService) {
        this.reservationMapper = reservationMapper;
        this.showReadFacade = showReadFacade;
        this.showRemindProducer = showRemindProducer;
        this.properties = properties;
        this.userNotificationService = userNotificationService;
    }

    public ShowRemindRecoveryScheduler(ReservationMapper reservationMapper,
                                       ShowReadFacade showReadFacade,
                                       ShowRemindProducer showRemindProducer,
                                       ReservationProperties properties) {
        this(reservationMapper, showReadFacade, showRemindProducer, properties, null);
    }

    @Scheduled(fixedDelayString = "${starshow.reservation.remind-recovery-interval-ms:60000}")
    public void scheduledRecover() {
        if (!properties.isRecoveryEnabled() || !properties.isMqEnabled()) {
            return;
        }
        int batchSize = Math.max(1, properties.getRemindRecoveryBatchSize());
        int maxBatches = Math.max(1, properties.getRemindRecoveryMaxBatchesPerRun());
        int recovered = recoverDueReminders(batchSize, maxBatches);
        if (recovered > 0) {
            log.info("ShowRemindRecoveryScheduler recovered {} reservation reminders", recovered);
        }
    }

    /**
     * 兼容调用入口：按默认上限连续推进到期批次
     */
    public int recoverBatch(int batchSize) {
        return recoverDueReminders(batchSize, Math.max(1, properties.getRemindRecoveryMaxBatchesPerRun()));
    }

    /**
     * 主办方改期联动（Phase 1.1）：
     * 1. 拉取演出权威最新配置（含最新 saleConfigVersion 与 saleStartTime）；
     * 2. 将该场次旧版本的全部有效预约原子重置为 remind_status=0、remind_config_version=newVersion、next_retry_time=now；
     * 3. 游标遍历该场次已预约用户，幂等落盘 SCHEDULE_CHANGED 即时变更通知（让粉丝改期当下即获知新开售时间）；
     * 4. 立即触发到期重排（提前入窗立投 / 提前至已开售置 4 SKIPPED / 推迟超 72h 推迟入窗时间）。
     */
    public ShowRescheduleResultVO rescheduleForShowVersion(long showId) {
        ShowBasicDTO show = showReadFacade.getShowBasic(showId);
        if (show == null) {
            throw new BizException(ErrorCode.SHOW_NOT_FOUND);
        }
        int newVersion = show.effectiveSaleConfigVersion();
        int resetCount = reservationMapper.resetReservationsForScheduleVersion(showId, newVersion, LocalDateTime.now());
        int batchSize = Math.max(1, properties.getRemindRecoveryBatchSize());
        int maxBatches = Math.max(1, properties.getRemindRecoveryMaxBatchesPerRun());

        int notifiedCount = emitScheduleChangedNotifications(show, batchSize, maxBatches);
        int progressed = recoverDueReminders(batchSize, maxBatches);
        log.info("Rescheduled reminders for showId={}, version={}, resetCount={}, notified={}, progressed={}",
                showId, newVersion, resetCount, notifiedCount, progressed);
        return ShowRescheduleResultVO.builder()
                .showId(showId)
                .saleConfigVersion(newVersion)
                .saleStartTime(show.getSaleStartTime())
                .resetReservationCount(resetCount)
                .progressedCount(progressed)
                .scheduleNotifiedCount(notifiedCount)
                .build();
    }

    private int emitScheduleChangedNotifications(ShowBasicDTO show, int batchSize, int maxBatches) {
        if (userNotificationService == null) {
            return 0;
        }
        long afterId = 0L;
        int newlyNotified = 0;
        for (int batch = 0; batch < maxBatches; batch++) {
            List<ReservationDO> rows = reservationMapper.selectByShowIdCursor(show.getShowId(), afterId, batchSize);
            if (rows == null || rows.isEmpty()) {
                break;
            }
            for (ReservationDO row : rows) {
                afterId = Math.max(afterId, row.getId());
                if (userNotificationService.recordScheduleChangedNotification(row.getId(), row.getUserId(), show)) {
                    newlyNotified++;
                }
            }
            if (rows.size() < batchSize) {
                break;
            }
        }
        return newlyNotified;
    }

    /**
     * 游标推进式到期提醒补发主循环（支持跨场次、连续多批处理，避免超窗或单场次故障阻塞后续提醒）
     */
    public int recoverDueReminders(int batchSize, int maxBatches) {
        int safeBatchSize = Math.max(1, batchSize);
        int safeMaxBatches = Math.max(1, maxBatches);
        LocalDateTime now = LocalDateTime.now();
        long afterId = 0L;
        int progressed = 0;

        // 本轮内按 showId 缓存查询结果，避免同场次百条预约重复触发远程 RPC
        Map<Long, Object> showCache = new HashMap<>();

        for (int batch = 0; batch < safeMaxBatches; batch++) {
            List<ReservationDO> candidates = reservationMapper.selectDueRecoverableReminders(now, afterId, safeBatchSize);
            if (candidates == null || candidates.isEmpty()) {
                break;
            }
            for (ReservationDO reservation : candidates) {
                afterId = Math.max(afterId, reservation.getId());
                Object showResult = resolveShowWithIsolation(reservation.getShowId(), showCache);

                if (showResult == SHOW_LOOKUP_FAILED) {
                    // 单场次元数据查询临时异常：仅推迟本记录下次重试时间，绝不影响其他场次补发（ISSUE-009）
                    long backoffSec = Math.max(1L, properties.getRemindRetryBackoffSeconds());
                    reservationMapper.casDeferNextRetryTimeForVersion(
                            reservation.getId(),
                            reservation.effectiveRemindConfigVersion(),
                            LocalDateTime.now().plusSeconds(backoffSec)
                    );
                    continue;
                }
                if (showResult == SHOW_NOT_FOUND) {
                    if (reservationMapper.casMarkSkipped(reservation.getId()) == 1) {
                        progressed++;
                    }
                    continue;
                }

                ShowBasicDTO show = (ShowBasicDTO) showResult;
                if (SHOW_STATUS_ENDED.equalsIgnoreCase(show.getSaleStatus())) {
                    if (reservationMapper.casMarkSkipped(reservation.getId()) == 1) {
                        progressed++;
                    }
                    continue;
                }

                // 若后台改期已推进 show.saleConfigVersion，顺便对齐该条预约的版本号再排期
                int latestVersion = show.effectiveSaleConfigVersion();
                if (reservation.effectiveRemindConfigVersion() < latestVersion) {
                    reservationMapper.resetReservationsForScheduleVersion(
                            reservation.getShowId(), latestVersion, LocalDateTime.now()
                    );
                }

                ShowRemindProducer.ScheduleOutcome outcome = showRemindProducer.scheduleRemindWithOutcome(
                        reservation.getId(), reservation.getUserId(), show
                );
                if (outcome == ShowRemindProducer.ScheduleOutcome.SCHEDULED
                        || outcome == ShowRemindProducer.ScheduleOutcome.SKIPPED_ALREADY_ON_SALE) {
                    progressed++;
                }
            }
            if (candidates.size() < safeBatchSize) {
                break;
            }
        }
        return progressed;
    }

    private Object resolveShowWithIsolation(long showId, Map<Long, Object> showCache) {
        if (showCache.containsKey(showId)) {
            return showCache.get(showId);
        }
        try {
            ShowBasicDTO show = showReadFacade.getShowBasic(showId);
            Object val = (show == null) ? SHOW_NOT_FOUND : show;
            showCache.put(showId, val);
            return val;
        } catch (Exception ex) {
            log.warn("ShowReadFacade.getShowBasic failed temporarily for showId={}, isolating failure from other shows",
                    showId, ex);
            showCache.put(showId, SHOW_LOOKUP_FAILED);
            return SHOW_LOOKUP_FAILED;
        }
    }
}
