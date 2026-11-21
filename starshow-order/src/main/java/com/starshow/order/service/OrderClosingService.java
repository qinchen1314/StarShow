package com.starshow.order.service;

import com.starshow.api.facade.OrderPaymentFacade.Settlement;
import com.starshow.api.facade.PaymentReadFacade;
import com.starshow.api.facade.PaymentReadFacade.CloseRequest;
import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.order.domain.entity.OrderDO;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "starshow.payment.enabled", havingValue = "true")
public class OrderClosingService {
    private final OrderFulfillmentService orders;
    private final PaymentReadFacade payment;
    private final RedissonClient redisson;

    public CancelView cancel(long orderNo, long userId) {
        OrderDO order = orders.find(orderNo);
        if (order == null || order.getUserId() != userId) throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        try {
            check(order, true);
        } catch (RuntimeException ex) {
            // Channel and lock failures leave the order unchanged and the user can retry the same cancellation.
            return new CancelView(orderNo, order.getStatus(), "CHECK_ORDER");
        }
        OrderDO current = orders.find(orderNo);
        return new CancelView(orderNo, current.getStatus(), "VIEW_ORDER");
    }

    public void timeout(long orderNo) {
        OrderDO order = orders.find(orderNo);
        if (order != null) check(order, false);
    }

    private void check(OrderDO order, boolean userRequested) {
        if (!"WAIT_PAY".equals(order.getStatus()) || (!userRequested && LocalDateTime.now().isBefore(order.getExpireTime()))) {
            return;
        }
        RLock lock = redisson.getLock("starshow:lock:order:" + order.getOrderNo());
        boolean acquired;
        try {
            acquired = lock.tryLock(0, 10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw uncertain();
        }
        if (!acquired) throw uncertain();
        try {
            var result = payment.closeOrFindPaid(new CloseRequest(
                    order.getOrderNo(), order.getUserId(), order.getAmountCent(), order.getExpireTime()));
            if (result != null && result.payment() != null) {
                if (orders.applyPaid(result.payment()) == Settlement.MISMATCH) throw uncertain();
            } else if (result != null && result.closed()) {
                orders.close(order.getOrderNo(), userRequested ? "USER_CANCELLED" : "PAY_TIMEOUT");
            } else {
                throw uncertain();
            }
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    private BizException uncertain() {
        return BizException.serviceUnavailable(ErrorCode.CHANNEL_UNCERTAIN, 1500, "支付结果待确认，请稍后刷新订单");
    }

    public record CancelView(long orderNo, String orderStatus, String nextAction) {}
}
