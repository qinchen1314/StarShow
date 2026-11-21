package com.starshow.order.mq;

import com.starshow.api.constant.MqTopics;
import com.starshow.order.domain.entity.OrderActionDO;
import com.starshow.order.mapper.OrderActionMapper;
import com.starshow.order.mapper.OrderFulfillmentRepository;
import com.starshow.order.mapper.OrderShardTableResolver;
import com.starshow.order.service.OrderClosingService;
import com.starshow.order.service.OrderFulfillmentService;
import com.starshow.order.service.OrderStockReleaseService;
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
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "starshow.payment.enabled", havingValue = "true")
public class OrderFulfillmentScheduler {
    private static final int BATCH = 100;
    private final OrderFulfillmentService orders;
    private final OrderClosingService closing;
    private final OrderStockReleaseService releases;
    private final OrderActionMapper actions;
    private final OrderFulfillmentRepository fulfillment;
    private final ObjectProvider<RocketMQTemplate> mq;
    private final AtomicLong closeCursor = new AtomicLong();

    @Value("${starshow.payment.mq-enabled:true}")
    private boolean mqEnabled;

    @Scheduled(fixedDelayString = "${starshow.payment.action-interval-ms:1000}")
    public void dueActions() {
        LocalDateTime now = LocalDateTime.now();
        for (OrderActionDO issue : actions.selectDuePendingActions(OrderFulfillmentService.ISSUE_TICKET, now, BATCH)) {
            try {
                RocketMQTemplate producer = mqEnabled ? mq.getIfAvailable() : null;
                if (producer == null || issue.getAttempt() > 0) {
                    orders.recoverIssue(issue.getOrderNo());
                } else {
                    producer.syncSend(MqTopics.TICKET_ISSUE, MessageBuilder.withPayload(issue.getOrderNo().toString())
                            .setHeader(RocketMQHeaders.KEYS, "ticket-issue:" + issue.getOrderNo()).build(), 1000);
                    retry(issue, now, null);
                }
            } catch (RuntimeException ex) {
                log.warn("Ticket issue queued for retry, orderNo={}", issue.getOrderNo());
                retry(issue, now, "ISSUE_RETRY");
            }
        }
        for (OrderActionDO release : actions.selectDueFulfillmentReleases(now, BATCH)) {
            try {
                releases.release(release.getOrderNo());
            } catch (RuntimeException ex) {
                log.warn("Inventory release queued for retry, orderNo={}", release.getOrderNo());
                retry(release, now, "RELEASE_RETRY");
            }
        }
    }

    @Scheduled(fixedDelayString = "${starshow.payment.close-sweep-interval-ms:300000}")
    public void dueClosures() {
        LocalDateTime now = LocalDateTime.now();
        var due = OrderShardTableResolver.allShardTables().stream()
                .flatMap(shard -> fulfillment.dueOrders(shard, now, closeCursor.get(), BATCH).stream())
                .sorted().limit(BATCH).toList();
        closeCursor.set(due.size() == BATCH ? due.getLast() : 0);
        for (long orderNo : due) {
            try {
                closing.timeout(orderNo);
            } catch (RuntimeException ex) {
                log.warn("Timeout close awaiting channel verification, orderNo={}", orderNo);
            }
        }
    }

    private void retry(OrderActionDO action, LocalDateTime now, String error) {
        actions.recordRetryFailure(action.getActionId(), now.plusSeconds(15), error, now);
    }
}
