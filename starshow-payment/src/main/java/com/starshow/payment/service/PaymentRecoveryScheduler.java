package com.starshow.payment.service;

import com.starshow.api.constant.MqTopics;
import com.starshow.payment.repository.MockChannelRepository;
import com.starshow.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "starshow.payment.enabled", havingValue = "true")
public class PaymentRecoveryScheduler {
    private final PaymentRepository payments;
    private final MockChannelRepository ledger;
    private final PaymentApplicationService service;
    private final RefundSagaService refunds;
    private final ObjectProvider<RocketMQTemplate> mq;

    @Value("${starshow.payment.mq-enabled:true}")
    private boolean mqEnabled;

    @Scheduled(fixedDelayString = "${starshow.payment.action-interval-ms:1000}")
    public void recover() {
        LocalDateTime now = LocalDateTime.now();
        for (var delivery : ledger.due(now, 100)) {
            try {
                service.deliver(delivery);
            } catch (RuntimeException ex) {
                log.warn("Mock delivery queued for replay, payNo={}", delivery.merchantBizNo());
                ledger.nextDelivery(delivery.merchantBizNo(), now.plusSeconds(15));
            }
        }
        for (var payment : payments.dueSync(now, 100)) service.syncIfPossible(payment);
        var producer = mqEnabled ? mq.getIfAvailable() : null;
        for (var refund : payments.dueRefunds(now, 100)) {
            if (producer != null && "INIT".equals(refund.sagaStep())) {
                try {
                    String key = "refund:" + refund.refundNo() + ":" + refund.sagaStep();
                    producer.syncSendOrderly(MqTopics.REFUND_EVENT, MessageBuilder.withPayload(Long.toString(refund.refundNo()))
                            .setHeader(RocketMQHeaders.KEYS, key).build(), Long.toString(refund.orderNo()), 1000);
                } catch (RuntimeException ex) {
                    log.warn("Refund dispatch unavailable, advancing from durable row, refundNo={}", refund.refundNo());
                }
            }
            refunds.advance(refund.refundNo());
        }
    }
}
