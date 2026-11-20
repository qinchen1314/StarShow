package com.starshow.user.mq;

import com.starshow.api.constant.MqTopics;
import com.starshow.api.dto.ShowBasicDTO;
import com.starshow.api.event.ShowRemindEvent;
import com.starshow.api.facade.ShowReadFacade;
import com.starshow.common.trace.TraceIdContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 链路一（节点 4）：RocketMQ 5.x 开票前延迟提醒消费者（Phase 1.1 版本守卫 + 站内通知同事务落账版）
 * 1. 先核对权威场次 saleConfigVersion：若主办方已改期推进版本，携带旧版本的延迟消息直接幂等跳过，绝不推送过期开售时间；
 * 2. 委托 ShowRemindDeliveryService 在同一本地事务内原子执行 CAS(remind_status -> 1 且 remind_config_version 匹配)
 *    与 t_user_notification 站内通知写入（uk_biz_key 防重）；
 * 3. 事务提交成功后触发模拟通知日志/外部推送钩子，重复投递严格仅生效一次。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "starshow.reservation", name = "mq-enabled", havingValue = "true", matchIfMissing = true)
@RocketMQMessageListener(topic = MqTopics.SHOW_REMIND, consumerGroup = MqTopics.CG_REMIND_NOTIFY)
public class ShowRemindConsumer implements RocketMQListener<ShowRemindEvent> {

    private final ShowRemindDeliveryService showRemindDeliveryService;
    private final ShowReadFacade showReadFacade;
    private final ShowRemindNotifier showRemindNotifier;

    @Override
    public void onMessage(ShowRemindEvent event) {
        if (event == null || event.getReservationId() == null) {
            log.warn("Received invalid ShowRemindEvent: {}", event);
            return;
        }
        TraceIdContext.set(event.getTraceId());
        try {
            int eventVersion = event.effectiveSaleConfigVersion();
            if (isStaleAgainstShowSchedule(event, eventVersion)) {
                log.info("[SHOW_REMIND_STALE_VERSION] 场次已改期，旧版本延迟消息直接跳过: reservationId={}, showId={}, eventVersion={}",
                        event.getReservationId(), event.getShowId(), eventVersion);
                return;
            }

            boolean delivered = showRemindDeliveryService.deliverIfVersionMatches(event, eventVersion);
            if (delivered) {
                showRemindNotifier.notifyRemind(event);
            } else {
                log.info("[SHOW_REMIND_IDEMPOTENT] 重复投递、版本已更新或已收敛终态，幂等跳过: reservationId={}, userId={}, showId={}, eventVersion={}",
                        event.getReservationId(), event.getUserId(), event.getShowId(), eventVersion);
            }
        } finally {
            TraceIdContext.clear();
        }
    }

    private boolean isStaleAgainstShowSchedule(ShowRemindEvent event, int eventVersion) {
        if (event.getShowId() == null || showReadFacade == null) {
            return false;
        }
        try {
            ShowBasicDTO latestShow = showReadFacade.getShowBasic(event.getShowId());
            return latestShow != null && latestShow.effectiveSaleConfigVersion() > eventVersion;
        } catch (Exception ex) {
            // 若场次服务短时不可达，放行至本地数据库 remind_config_version 版本守卫兜底，不误丢合法提醒
            log.warn("Failed to verify latest saleConfigVersion for showId={}, relying on DB version guard",
                    event.getShowId(), ex);
            return false;
        }
    }
}
