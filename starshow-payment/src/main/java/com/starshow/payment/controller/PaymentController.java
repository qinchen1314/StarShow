package com.starshow.payment.controller;

import com.starshow.common.auth.UserContext;
import com.starshow.common.result.Result;
import com.starshow.payment.service.MockPayChannel.Fault;
import com.starshow.payment.service.PaymentApplicationService;
import com.starshow.payment.service.RefundSagaService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "starshow.payment.enabled", havingValue = "true")
public class PaymentController {
    private final PaymentApplicationService payments;
    private final RefundSagaService refunds;

    @PostMapping("/api/v1/payments")
    public Result<PaymentApplicationService.PaymentView> create(@RequestBody PaymentRequest request) {
        return Result.ok(payments.create(request.orderNo(), UserContext.requireUserId()));
    }

    @GetMapping("/api/v1/payments/{payNo}")
    public Result<PaymentApplicationService.PaymentView> get(@PathVariable long payNo) {
        return Result.ok(payments.get(payNo, UserContext.requireUserId()));
    }

    @GetMapping("/api/v1/payments/order/{orderNo}")
    public Result<PaymentApplicationService.PaymentView> paymentForOrder(@PathVariable long orderNo) {
        return Result.ok(payments.getForOrder(orderNo, UserContext.requireUserId()));
    }

    @PostMapping("/api/v1/payments/mock/pay/{payNo}")
    public Result<PaymentApplicationService.MockResult> mock(@PathVariable long payNo,
                                                            @RequestParam(defaultValue = "NONE") Fault fault) {
        return Result.ok(payments.mockPay(payNo, UserContext.requireUserId(), fault));
    }

    @PostMapping("/api/v1/payments/callback")
    public Result<Void> callback(@RequestHeader(value = "X-Mock-Timestamp", required = false) String timestamp,
                                 @RequestHeader(value = "X-Mock-Signature", required = false) String signature,
                                 @RequestBody byte[] body) {
        payments.callback(timestamp, signature, body);
        return Result.ok();
    }

    @PostMapping("/api/v1/refunds")
    public Result<RefundSagaService.RefundView> refund(@RequestBody RefundRequest request) {
        return Result.ok(refunds.request(request.orderNo(), UserContext.requireUserId(), request.reason()));
    }

    @GetMapping("/api/v1/refunds/{refundNo}")
    public Result<RefundSagaService.RefundView> refund(@PathVariable long refundNo) {
        return Result.ok(refunds.get(refundNo, UserContext.requireUserId()));
    }

    @GetMapping("/api/v1/refunds/order/{orderNo}")
    public Result<RefundSagaService.RefundView> refundForOrder(@PathVariable long orderNo) {
        return Result.ok(refunds.getForOrder(orderNo, UserContext.requireUserId()));
    }

    public record PaymentRequest(long orderNo) {}
    public record RefundRequest(long orderNo, String reason) {}
}
