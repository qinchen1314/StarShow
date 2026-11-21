package com.starshow.order.client;

import com.starshow.api.facade.PaymentReadFacade;
import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("distributed")
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "starshow.payment.enabled", havingValue = "true")
public class FeignPaymentReadFacade implements PaymentReadFacade {
    private final OrderPaymentFeignClient feign;

    @Override
    public CloseResult closeOrFindPaid(CloseRequest request) {
        var answer = feign.close(request);
        if (answer == null || !answer.isSuccess() || answer.getData() == null) {
            throw BizException.serviceUnavailable(ErrorCode.CHANNEL_UNCERTAIN, 1500, "支付服务暂无法确认关单");
        }
        return answer.getData();
    }
}
