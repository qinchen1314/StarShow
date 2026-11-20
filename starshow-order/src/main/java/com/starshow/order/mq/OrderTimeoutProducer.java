package com.starshow.order.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshow.api.constant.MqTopics;
import com.starshow.api.event.OrderTimeoutEvent;
import com.starshow.common.trace.TraceIdContext;
import com.starshow.order.config.SeckillProperties;
import com.starshow.order.domain.entity.OrderActionDO;
import com.starshow.order.mapper.OrderActionMapper;
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
import java.util.Map;

/**
 * 订单 15 分钟延迟关单消息生产者（TOPIC_ORDER_TIMEOUT）：
 * 严格沿用订单落库时写入 t_order_action(CLOSE_CHECK) 的绝对截止时间 expireEpochMs 与稳定键 close-check:<orderNo>。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutProducer {

    private final ObjectProvider<RocketMQTemplate> rocketMQTemplateProvider;
    private final OrderActionMapper orderActionMapper;
    private final ObjectMapper objectMapper;
    private final SeckillProperties properties;

    public boolean dispatchCloseCheckAction(OrderActionDO action) {
        if (action == null || !OrderActionDO.TYPE_CLOSE_CHECK.equals(action.getActionType())) {
            return false;
        }
        if (!OrderActionDO.STATUS_PENDING.equals(action.getStatus())) {
            return true;
        }
        if (!properties.isMqEnabled()) {
            return false;
        }
        RocketMQTemplate template = rocketMQTemplateProvider.getIfAvailable();
        if (template == null) {
            recordFailure(action.getActionId(), "MQ_TEMPLATE_UNAVAILABLE");
            return false;
        }

        long expireEpochMs = extractExpireEpochMs(action);
        long nowMs = System.currentTimeMillis();
        long deliverTimeMs = Math.max(nowMs + 1000L, expireEpochMs);
        LocalDateTime expireTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(expireEpochMs), ZoneId.systemDefault());

        String msgKey = "close-check:" + action.getOrderNo();
        OrderTimeoutEvent event = OrderTimeoutEvent.builder()
                .msgKey(msgKey)
                .orderNo(action.getOrderNo())
                .userId(action.getUserId())
                .showId(action.getShowId())
                .expireTime(expireTime)
                .expireEpochMs(expireEpochMs)
                .traceId(TraceIdContext.getOrCreate())
                .build();

        Message<OrderTimeoutEvent> message = MessageBuilder.withPayload(event)
                .setHeader(RocketMQHeaders.KEYS, msgKey)
                .build();

        try {
            SendResult result = template.syncSendDeliverTimeMills(MqTopics.ORDER_TIMEOUT, message, deliverTimeMs);
            if (result != null && result.getSendStatus() == SendStatus.SEND_OK) {
                orderActionMapper.casMarkSent(action.getActionId(), LocalDateTime.now());
                log.info("Dispatched TOPIC_ORDER_TIMEOUT delay message: orderNo={}, expireEpochMs={}, msgId={}",
                        action.getOrderNo(), expireEpochMs, result.getMsgId());
                return true;
            }
            recordFailure(action.getActionId(), result == null ? "SEND_NULL" : result.getSendStatus().name());
            return false;
        } catch (Exception ex) {
            log.warn("Failed to dispatch TOPIC_ORDER_TIMEOUT for orderNo={}: {}", action.getOrderNo(), ex.getMessage());
            recordFailure(action.getActionId(), "SEND_EXCEPTION");
            return false;
        }
    }

    private void recordFailure(long actionId, String errorCode) {
        try {
            LocalDateTime now = LocalDateTime.now();
            orderActionMapper.recordRetryFailure(actionId, now.plusSeconds(15), errorCode, now);
        } catch (Exception ex) {
            log.warn("Failed to record CLOSE_CHECK retry failure actionId={}: {}", actionId, ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private long extractExpireEpochMs(OrderActionDO action) {
        if (action.getPayloadJson() != null && !action.getPayloadJson().isBlank()) {
            try {
                Map<String, Object> map = objectMapper.readValue(action.getPayloadJson(), Map.class);
                Object raw = map.get("expireEpochMs");
                if (raw instanceof Number num) {
                    return num.longValue();
                }
                if (raw != null) {
                    return Long.parseLong(String.valueOf(raw));
                }
            } catch (Exception ignored) {
            }
        }
        LocalDateTime fallback = action.getCreateTime() == null
                ? LocalDateTime.now().plusMinutes(properties.getOrderExpireMinutes())
                : action.getCreateTime().plusMinutes(properties.getOrderExpireMinutes());
        return fallback.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
