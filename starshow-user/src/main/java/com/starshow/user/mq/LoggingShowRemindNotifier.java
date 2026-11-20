package com.starshow.user.mq;

import com.starshow.api.event.ShowRemindEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 默认模拟站内信/Push 通知实现（严禁打印手机号、身份证等明文隐私）
 */
@Slf4j
@Component
public class LoggingShowRemindNotifier implements ShowRemindNotifier {

    @Override
    public void notifyRemind(ShowRemindEvent event) {
        log.info("[SHOW_REMIND_NOTIFY] 开票提醒已送达: reservationId={}, userId={}, showId={}, title={}, saleStartTime={}",
                event.getReservationId(), event.getUserId(), event.getShowId(),
                event.getShowTitle(), event.getSaleStartTime());
    }
}
