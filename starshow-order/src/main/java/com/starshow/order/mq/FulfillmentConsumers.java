package com.starshow.order.mq;

import com.starshow.api.constant.MqTopics;
import com.starshow.api.event.OrderTimeoutEvent;
import com.starshow.order.service.OrderClosingService;
import com.starshow.order.service.OrderFulfillmentService;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

public final class FulfillmentConsumers {
    private FulfillmentConsumers() {}

    @Component
    @RequiredArgsConstructor
    @ConditionalOnExpression("${starshow.payment.enabled:false} && ${starshow.payment.mq-enabled:true}")
    @RocketMQMessageListener(topic = MqTopics.ORDER_TIMEOUT, consumerGroup = MqTopics.CG_ORDER_TIMEOUT)
    public static class Timeout implements RocketMQListener<OrderTimeoutEvent> {
        private final OrderClosingService closing;

        @Override
        public void onMessage(OrderTimeoutEvent message) {
            closing.timeout(message.getOrderNo());
        }
    }

    @Component
    @RequiredArgsConstructor
    @ConditionalOnExpression("${starshow.payment.enabled:false} && ${starshow.payment.mq-enabled:true}")
    @RocketMQMessageListener(topic = MqTopics.TICKET_ISSUE, consumerGroup = MqTopics.CG_TICKET_ISSUE)
    public static class Issue implements RocketMQListener<String> {
        private final OrderFulfillmentService fulfillment;

        @Override
        public void onMessage(String orderNo) {
            fulfillment.recoverIssue(Long.parseLong(orderNo));
        }
    }
}
