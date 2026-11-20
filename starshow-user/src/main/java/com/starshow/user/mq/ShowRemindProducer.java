package com.starshow.user.mq;

import com.starshow.api.constant.MqTopics;
import com.starshow.api.dto.ShowBasicDTO;
import com.starshow.api.event.ShowRemindEvent;
import com.starshow.common.trace.TraceIdContext;
import com.starshow.user.config.ReservationProperties;
import com.starshow.user.mapper.ReservationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 链路一（节点 4）：RocketMQ 5.x 任意时间延迟开票提醒生产者（ISSUE-004 / ISSUE-009 / Phase 1.1 优化版）
 * - 严格在预约落库事务之外执行，异常降级不回滚主流程；
 * - 所有状态变更全部走数据库级版本守卫单向 CAS（0/2 -> 3、0/2 -> 2+退避、已开售 -> 4 无需提醒），绝不覆盖终态或新排期版本；
 * - 超出 broker.conf timerMaxDelaySec=259200 (72h) 时将 next_retry_time 精确推迟到入窗时刻，立即让出补偿队列防止队头饥饿；
 * - 支持可选的开票前分批抖动投递窗口（remindDispatchJitterSeconds），削平开票前瞬时详情洪峰。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShowRemindProducer {

    private static final long IMMINENT_DELAY_MS = 2000L;
    private static final long MIN_DEFER_SECONDS = 5L;

    public enum ScheduleOutcome {
        SCHEDULED,
        DEFERRED_OUT_OF_WINDOW,
        SKIPPED_ALREADY_ON_SALE,
        FAILED_DEGRADED
    }

    private final ObjectProvider<RocketMQTemplate> rocketMQTemplateProvider;
    private final ReservationMapper reservationMapper;
    private final ReservationProperties properties;

    /**
     * 安排开票前延迟提醒（支持首次预约投递、定时补偿重投及主板方改期后新版本重排）
     */
    public ScheduleOutcome scheduleRemindWithOutcome(long reservationId, long userId, ShowBasicDTO show) {
        long nowMs = System.currentTimeMillis();
        int configVersion = (show == null) ? 1 : show.effectiveSaleConfigVersion();
        long deliverTimeMs;

        if (properties.getTestOverrideDelayMs() > 0) {
            deliverTimeMs = nowMs + properties.getTestOverrideDelayMs();
        } else {
            if (show == null || show.getSaleStartTime() == null) {
                safeCasMarkFailed(reservationId, configVersion);
                return ScheduleOutcome.FAILED_DEGRADED;
            }
            long saleStartMs = show.getSaleStartTime()
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
            if (nowMs >= saleStartMs) {
                // 明确区分 4(无需发送) 与 1(真经 MQ 消费送达)
                safeCasMarkSkipped(reservationId);
                log.info("Show already on sale, marking remind_status=4 (SKIPPED): reservationId={}, showId={}, version={}",
                        reservationId, show.getShowId(), configVersion);
                return ScheduleOutcome.SKIPPED_ALREADY_ON_SALE;
            }
            long jitterMs = resolveDispatchJitterMs(reservationId);
            long targetMs = saleStartMs - properties.getRemindLeadSeconds() * 1000L - jitterMs;
            deliverTimeMs = (targetMs <= nowMs) ? (nowMs + IMMINENT_DELAY_MS) : targetMs;
        }

        long delaySec = (deliverTimeMs - nowMs) / 1000L;
        if (delaySec > properties.getRemindMaxDelaySeconds()) {
            // 超出 Broker 时间轮上限（默认 72h）：精确计算入窗时刻并推迟 next_retry_time，让出本轮补偿队列（ISSUE-009）
            long windowEnterMs = deliverTimeMs - properties.getRemindMaxDelaySeconds() * 1000L;
            long safeEnterMs = Math.max(nowMs + MIN_DEFER_SECONDS * 1000L, windowEnterMs);
            LocalDateTime nextRetryTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(safeEnterMs), ZoneId.systemDefault());
            safeCasDeferNextRetryTime(reservationId, configVersion, nextRetryTime);
            log.info("Remind time exceeds broker max delay window ({}s > {}s), deferred next_retry_time={} for reservationId={}, showId={}, version={}",
                    delaySec, properties.getRemindMaxDelaySeconds(), nextRetryTime, reservationId, show.getShowId(), configVersion);
            return ScheduleOutcome.DEFERRED_OUT_OF_WINDOW;
        }

        if (!properties.isMqEnabled()) {
            log.debug("Reservation MQ disabled by config, keeping status=0: reservationId={}", reservationId);
            return ScheduleOutcome.DEFERRED_OUT_OF_WINDOW;
        }

        RocketMQTemplate rocketMQTemplate = rocketMQTemplateProvider.getIfAvailable();
        if (rocketMQTemplate == null) {
            log.warn("RocketMQTemplate bean unavailable, CAS marking remind_status=2 with backoff: reservationId={}", reservationId);
            safeCasMarkFailed(reservationId, configVersion);
            return ScheduleOutcome.FAILED_DEGRADED;
        }

        ShowRemindEvent event = ShowRemindEvent.builder()
                .reservationId(reservationId)
                .userId(userId)
                .showId(show.getShowId())
                .showTitle(show.getTitle())
                .saleStartTime(show.getSaleStartTime())
                .saleConfigVersion(configVersion)
                .traceId(TraceIdContext.getOrCreate())
                .build();

        Message<ShowRemindEvent> message = MessageBuilder.withPayload(event)
                .setHeader(RocketMQHeaders.KEYS, "rsv:" + reservationId + ":v" + configVersion)
                .build();

        try {
            SendResult result = rocketMQTemplate.syncSendDeliverTimeMills(MqTopics.SHOW_REMIND, message, deliverTimeMs);
            if (result != null && result.getSendStatus() == SendStatus.SEND_OK) {
                safeCasMarkScheduled(reservationId, configVersion);
                log.info("Scheduled show remind delay message: reservationId={}, showId={}, version={}, deliverTimeMs={}, msgId={}",
                        reservationId, show.getShowId(), configVersion, deliverTimeMs, result.getMsgId());
                return ScheduleOutcome.SCHEDULED;
            }
            log.warn("RocketMQ returned non-OK status={}, CAS marking remind_status=2 with backoff: reservationId={}",
                    result == null ? "null" : result.getSendStatus(), reservationId);
            safeCasMarkFailed(reservationId, configVersion);
            return ScheduleOutcome.FAILED_DEGRADED;
        } catch (Exception ex) {
            // 发送超时或 Broker 不可用：版本守卫 CAS 置 2 并按退避推迟 next_retry_time（绝不回退已送达终态 1）
            log.error("Failed or timed out sending show remind message, CAS degrading to remind_status=2: reservationId={}, showId={}, version={}",
                    reservationId, show.getShowId(), configVersion, ex);
            safeCasMarkFailed(reservationId, configVersion);
            return ScheduleOutcome.FAILED_DEGRADED;
        }
    }

    public boolean scheduleRemind(long reservationId, long userId, ShowBasicDTO show) {
        return scheduleRemindWithOutcome(reservationId, userId, show) == ScheduleOutcome.SCHEDULED;
    }

    long resolveDispatchJitterMs(long reservationId) {
        long jitterSeconds = properties.getRemindDispatchJitterSeconds();
        if (jitterSeconds <= 0L) {
            return 0L;
        }
        long offsetSeconds = Math.floorMod(reservationId, jitterSeconds + 1L);
        return offsetSeconds * 1000L;
    }

    private void safeCasMarkScheduled(long reservationId, int configVersion) {
        try {
            reservationMapper.casMarkScheduledForVersion(reservationId, configVersion);
        } catch (Exception ex) {
            log.error("Failed to CAS mark remind_status=3 for reservationId={}, version={}", reservationId, configVersion, ex);
        }
    }

    private void safeCasMarkFailed(long reservationId, int configVersion) {
        try {
            long backoffSec = Math.max(1L, properties.getRemindRetryBackoffSeconds());
            reservationMapper.casMarkFailedWithRetryForVersion(
                    reservationId, configVersion, LocalDateTime.now().plusSeconds(backoffSec)
            );
        } catch (Exception ex) {
            log.error("Failed to CAS mark remind_status=2 for reservationId={}, version={}", reservationId, configVersion, ex);
        }
    }

    private void safeCasDeferNextRetryTime(long reservationId, int configVersion, LocalDateTime nextRetryTime) {
        try {
            reservationMapper.casDeferNextRetryTimeForVersion(reservationId, configVersion, nextRetryTime);
        } catch (Exception ex) {
            log.error("Failed to defer next_retry_time={} for reservationId={}, version={}",
                    nextRetryTime, reservationId, configVersion, ex);
        }
    }

    private void safeCasMarkSkipped(long reservationId) {
        try {
            reservationMapper.casMarkSkipped(reservationId);
        } catch (Exception ex) {
            log.error("Failed to CAS mark remind_status=4 (SKIPPED) for reservationId={}", reservationId, ex);
        }
    }
}
