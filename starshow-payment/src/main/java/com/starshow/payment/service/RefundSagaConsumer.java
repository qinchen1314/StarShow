package com.starshow.payment.service;

import com.starshow.api.constant.MqTopics;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnExpression("${starshow.payment.enabled:false} && ${starshow.payment.mq-enabled:true}")
@RocketMQMessageListener(topic = MqTopics.REFUND_EVENT, consumerGroup = MqTopics.CG_REFUND_SAGA,
        consumeMode = ConsumeMode.ORDERLY)
public class RefundSagaConsumer implements RocketMQListener<String> {
    private final RefundSagaService refunds;

    @Override
    public void onMessage(String refundNo) {
        refunds.advance(Long.parseLong(refundNo));
    }
}
