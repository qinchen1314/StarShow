package com.starshow.payment.client;

import com.starshow.api.facade.OrderPaymentFacade;
import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Profile("distributed")
@Component
@EnableFeignClients(clients = PaymentOrderFeignClient.class)
@RequiredArgsConstructor
@ConditionalOnProperty(name = "starshow.payment.enabled", havingValue = "true")
public class FeignOrderPaymentFacade implements OrderPaymentFacade {
    private final PaymentOrderFeignClient client;

    @Override
    public OrderSnapshot get(long orderNo, long userId) {
        return data(client.get(orderNo, userId));
    }

    @Override
    public Settlement applyPaid(PaidOrder payment) {
        return data(client.paid(payment));
    }

    @Override
    public RefundLock lockRefund(long orderNo, long userId, boolean preview) {
        return data(client.lock(orderNo, userId, preview));
    }

    @Override
    public boolean completeRefund(long orderNo, long userId) {
        return Boolean.TRUE.equals(data(client.refunded(orderNo, userId)));
    }

    @Override
    public void recoverIssue(long orderNo) {
        data(client.issue(orderNo));
    }

    @Override
    public List<Long> changedOrders(LocalDateTime from, LocalDateTime until, long after, int limit) {
        return data(client.changed(from, until, after, limit));
    }

    private <T> T data(Result<T> response) {
        if (response == null || !response.isSuccess()) {
            throw BizException.serviceUnavailable(ErrorCode.CHANNEL_UNCERTAIN, 1500, "订单结果待确认");
        }
        return response.getData();
    }
}
