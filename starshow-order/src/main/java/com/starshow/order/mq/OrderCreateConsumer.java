package com.starshow.order.mq;

import com.starshow.api.constant.MqTopics;
import com.starshow.api.event.OrderCreateEvent;
import com.starshow.common.trace.TraceIdContext;
import com.starshow.order.config.SeckillProperties;
import com.starshow.order.lua.OrderRedisScripts;
import com.starshow.order.service.OrderPersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 链路三 V4 创单消费端（TOPIC_ORDER_CREATE）：
 * 1. 每票档并发限流保护 InnoDB 热点行锁，同事务落库 t_mq_consume_log + t_order_N + t_ticket_tier + t_order_action(CLOSE_CHECK)；
 * 2. 与悬挂巡检共用 CREATE_DECISION 唯一行锁裁决（消费 APPLIED vs 巡检 REJECTED）；
 * 3. 提交后清理 deduct_pending、更新 attempt/req 为 WAIT_PAY，并立即尝试投递 15 分钟延迟 TOPIC_ORDER_TIMEOUT；
 * 4. 若命中另一有效单或物理库存不足被 DB 拒绝，执行 seckill_rollback.lua 幂等释放 V4 预扣并标记终态 FAILED。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "starshow.seckill", name = "mq-enabled", havingValue = "true", matchIfMissing = true)
@RocketMQMessageListener(
        topic = MqTopics.ORDER_CREATE,
        consumerGroup = MqTopics.CG_ORDER_CREATE,
        consumeThreadNumber = 4
)
public class OrderCreateConsumer implements RocketMQListener<OrderCreateEvent> {

    private final OrderPersistenceService persistenceService;
    private final OrderRedisScripts redisScripts;
    private final OrderTimeoutProducer orderTimeoutProducer;
    private final SeckillProperties properties;

    @Override
    public void onMessage(OrderCreateEvent event) {
        if (event == null || event.getOrderNo() == null) {
            log.warn("Received invalid OrderCreateEvent: {}", event);
            return;
        }
        TraceIdContext.set(event.getTraceId());
        try {
            handleEvent(event);
        } finally {
            TraceIdContext.clear();
        }
    }

    /**
     * 抽取公开方法，供消费端与 L3 重复投递/竞速剧本实测复用
     */
    public OrderPersistenceService.V4ConsumeOutcome handleEvent(OrderCreateEvent event) {
        OrderPersistenceService.V4ConsumeOutcome outcome = persistenceService.persistV4OrderFromEvent(event);
        if (outcome == null) {
            return null;
        }
        long showId = event.getShowId();
        long orderNo = event.getOrderNo();
        long reqTtl = Math.max(60L, properties.getRequestTtlSeconds());

        switch (outcome.status()) {
            case APPLIED -> {
                redisScripts.removeDeductPending(showId, orderNo);
                redisScripts.updateAttemptAndReqStatus(showId, event.getUserId(), event.getClientRequestId(),
                        orderNo, "WAIT_PAY", "", false, reqTtl);
                if (outcome.closeAction() != null) {
                    orderTimeoutProducer.dispatchCloseCheckAction(outcome.closeAction());
                }
                log.info("V4 OrderCreateConsumer persisted order: orderNo={}, userId={}, showId={}, tierId={}, qty={}",
                        orderNo, event.getUserId(), showId, event.getTierId(), event.getQty());
            }
            case ALREADY_APPLIED -> {
                redisScripts.removeDeductPending(showId, orderNo);
                redisScripts.updateAttemptAndReqStatus(showId, event.getUserId(), event.getClientRequestId(),
                        orderNo, "WAIT_PAY", "", false, reqTtl);
                log.info("V4 OrderCreateConsumer idempotent skip (already persisted): orderNo={}", orderNo);
            }
            case ALREADY_REJECTED -> {
                // 巡检已先抢占 CREATE_DECISION=REJECTED，绝不落单，确保幂等回补并清理索引
                rollbackV4DeductIfNeeded(event, outcome.failCode());
                log.info("V4 OrderCreateConsumer skipped because CREATE_DECISION already REJECTED: orderNo={}", orderNo);
            }
            case REJECTED_NEED_ROLLBACK -> {
                // DB 物理库存或唯一约束拒绝：执行幂等释放 V4 预扣并标记终态 FAILED
                rollbackV4DeductIfNeeded(event, outcome.failCode());
                log.warn("V4 OrderCreateConsumer rejected by DB constraint ({}), executed idempotent rollback: orderNo={}",
                        outcome.failCode(), orderNo);
            }
            case INVALID -> log.error("Ignored invalid OrderCreateEvent ({}): orderNo={}", outcome.failCode(), orderNo);
        }
        return outcome;
    }

    private void rollbackV4DeductIfNeeded(OrderCreateEvent event, String failCode) {
        long ttlSec = Math.max(60L, properties.getDeductProofTtlSeconds());
        long released = redisScripts.executeRollback(
                event.getShowId(),
                event.getTierId(),
                event.getZoneId(),
                event.getUserId(),
                event.getQty(),
                event.getOrderNo(),
                ttlSec,
                event.getSeatIndices()
        );
        if (released < 0) {
            throw new IllegalStateException("Unproven inventory release for rejected order " + event.getOrderNo());
        }
        persistenceService.markReleaseStockDone(event.getOrderNo());
        redisScripts.updateAttemptAndReqStatus(
                event.getShowId(),
                event.getUserId(),
                event.getClientRequestId(),
                event.getOrderNo(),
                "FAILED",
                failCode == null ? "A0301" : failCode,
                true,
                Math.max(60L, properties.getRequestTtlSeconds())
        );
    }
}
