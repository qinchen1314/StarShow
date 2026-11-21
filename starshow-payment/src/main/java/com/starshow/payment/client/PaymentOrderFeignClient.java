package com.starshow.payment.client;

import com.starshow.api.facade.OrderPaymentFacade.*;
import com.starshow.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@FeignClient(name = "payment-starshow-order", url = "${starshow.services.order-uri:http://127.0.0.1:8003}",
        path = "/internal/order-payment", configuration = PaymentFeignConfig.class)
public interface PaymentOrderFeignClient {
    @GetMapping("/{orderNo}")
    Result<OrderSnapshot> get(@PathVariable("orderNo") long orderNo, @RequestParam("userId") long userId);

    @PostMapping("/paid")
    Result<Settlement> paid(@RequestBody PaidOrder payment);

    @PostMapping("/{orderNo}/refund-lock")
    Result<RefundLock> lock(@PathVariable("orderNo") long orderNo, @RequestParam("userId") long userId,
                          @RequestParam("preview") boolean preview);

    @PostMapping("/{orderNo}/refund-complete")
    Result<Boolean> refunded(@PathVariable("orderNo") long orderNo, @RequestParam("userId") long userId);

    @PostMapping("/{orderNo}/issue")
    Result<Void> issue(@PathVariable("orderNo") long orderNo);

    @GetMapping("/changed")
    Result<List<Long>> changed(@RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                              @RequestParam("until") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime until,
                              @RequestParam("after") long after, @RequestParam("limit") int limit);
}
