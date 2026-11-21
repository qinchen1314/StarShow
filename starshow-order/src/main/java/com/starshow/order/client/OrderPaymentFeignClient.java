package com.starshow.order.client;

import com.starshow.api.facade.PaymentReadFacade;
import com.starshow.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "order-starshow-payment", url = "${starshow.services.payment-uri:http://127.0.0.1:8004}",
        configuration = OrderInternalFeignClientConfig.class)
public interface OrderPaymentFeignClient {
    @PostMapping("/internal/payment/close")
    Result<PaymentReadFacade.CloseResult> close(@RequestBody PaymentReadFacade.CloseRequest request);
}
