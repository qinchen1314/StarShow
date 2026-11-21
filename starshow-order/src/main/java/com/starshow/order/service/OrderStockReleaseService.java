package com.starshow.order.service;

import com.starshow.order.config.SeckillProperties;
import com.starshow.order.domain.entity.OrderActionDO;
import com.starshow.order.domain.entity.OrderDO;
import com.starshow.order.lua.OrderRedisScripts;
import com.starshow.order.mapper.OrderActionMapper;
import com.starshow.order.mapper.OrderFulfillmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "starshow.payment.enabled", havingValue = "true")
public class OrderStockReleaseService {
    private final OrderFulfillmentService orders;
    private final OrderFulfillmentRepository fulfillment;
    private final OrderActionMapper actions;
    private final OrderRedisScripts redis;
    private final SeckillProperties seckill;

    public void release(long orderNo) {
        OrderDO order = orders.prepareRelease(orderNo);
        if (order == null) return;
        if (!"V4".equals(order.getSeckillStrategy()) || redis.hasStockReleaseReceipt(order.getShowId(), orderNo, order.getQty())) {
            orders.finishRelease(orderNo);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        long ttl = Math.max(60L, seckill.getDeductProofTtlSeconds());
        var evidence = fulfillment.releaseTimes(orderNo);
        if (evidence.attemptedAt() != null && !now.isBefore(evidence.attemptedAt().plusSeconds(ttl - 5))) {
            manual(orderNo, "RELEASE_PROOF_EXPIRED", now);
            return;
        }
        fulfillment.markReleaseAttempt(orderNo, now);
        long result = redis.executeRollback(order.getShowId(), order.getTierId(), order.getZoneId(),
                order.getUserId(), order.getQty(), orderNo, ttl, OrderFulfillmentService.seats(order));
        if (result >= 0) {
            orders.finishRelease(orderNo);
        } else {
            // A concurrent worker may have completed between reads. The Lua replay path checks the exact receipt.
            manual(orderNo, "RELEASE_OWNERSHIP_UNKNOWN", now);
        }
    }

    private void manual(long orderNo, String reason, LocalDateTime now) {
        actions.casUpdateStatus(orderNo, OrderActionDO.TYPE_RELEASE_STOCK, "PENDING", "MANUAL_CHECK", null, reason, now);
    }
}
