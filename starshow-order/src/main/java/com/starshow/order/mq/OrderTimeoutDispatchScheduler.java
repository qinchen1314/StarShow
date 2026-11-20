package com.starshow.order.mq;

import com.starshow.order.config.SeckillProperties;
import com.starshow.order.domain.entity.OrderActionDO;
import com.starshow.order.mapper.OrderActionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 超时关单延迟消息可靠补发调度器：
 * 扫描 t_order_action(action_type='CLOSE_CHECK', status='PENDING', next_retry_at <= NOW)，
 * 即使落单提交后进程立即退出也能可靠找回并按同一绝对截止时间补发 TOPIC_ORDER_TIMEOUT。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutDispatchScheduler {

    private static final int BATCH_SIZE = 100;

    private final OrderActionMapper orderActionMapper;
    private final OrderTimeoutProducer orderTimeoutProducer;
    private final SeckillProperties properties;

    @Scheduled(fixedDelayString = "${starshow.seckill.timeout-dispatch-interval-ms:10000}")
    public void scheduledDispatch() {
        if (!properties.isTimeoutDispatchEnabled() || !properties.isMqEnabled()) {
            return;
        }
        dispatchDueCloseChecks(BATCH_SIZE);
    }

    public int dispatchDueCloseChecks(int limit) {
        List<OrderActionDO> actions = orderActionMapper.selectDuePendingActions(
                OrderActionDO.TYPE_CLOSE_CHECK,
                LocalDateTime.now(),
                Math.max(1, limit)
        );
        if (actions == null || actions.isEmpty()) {
            return 0;
        }
        int sent = 0;
        for (OrderActionDO action : actions) {
            if (orderTimeoutProducer.dispatchCloseCheckAction(action)) {
                sent++;
            }
        }
        return sent;
    }
}
