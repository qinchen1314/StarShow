package com.starshow.order.controller;

import com.starshow.api.facade.OrderPaymentFacade.*;
import com.starshow.common.auth.AuthHeaders;
import com.starshow.common.auth.InternalAccess;
import com.starshow.common.config.StarShowSecurityProperties;
import com.starshow.common.result.Result;
import com.starshow.order.service.OrderFulfillmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/internal/order-payment")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "starshow.payment.enabled", havingValue = "true")
public class InternalOrderPaymentController {
    private final OrderFulfillmentService orders;
    private final StarShowSecurityProperties security;

    @GetMapping("/{orderNo}")
    public Result<OrderSnapshot> get(@RequestHeader(AuthHeaders.X_INTERNAL_GATEWAY_SECRET) String secret,
            @PathVariable long orderNo, @RequestParam long userId) {
        InternalAccess.verify(security, secret);
        return Result.ok(orders.get(orderNo, userId));
    }

    @PostMapping("/paid")
    public Result<Settlement> paid(@RequestHeader(AuthHeaders.X_INTERNAL_GATEWAY_SECRET) String secret,
            @RequestBody PaidOrder payment) {
        InternalAccess.verify(security, secret);
        return Result.ok(orders.applyPaid(payment));
    }

    @PostMapping("/{orderNo}/refund-lock")
    public Result<RefundLock> lock(@RequestHeader(AuthHeaders.X_INTERNAL_GATEWAY_SECRET) String secret,
            @PathVariable long orderNo, @RequestParam long userId, @RequestParam boolean preview) {
        InternalAccess.verify(security, secret);
        return Result.ok(orders.lockRefund(orderNo, userId, preview));
    }

    @PostMapping("/{orderNo}/refund-complete")
    public Result<Boolean> refunded(@RequestHeader(AuthHeaders.X_INTERNAL_GATEWAY_SECRET) String secret,
            @PathVariable long orderNo, @RequestParam long userId) {
        InternalAccess.verify(security, secret);
        return Result.ok(orders.completeRefund(orderNo, userId));
    }

    @PostMapping("/{orderNo}/issue")
    public Result<Void> issue(@RequestHeader(AuthHeaders.X_INTERNAL_GATEWAY_SECRET) String secret,
            @PathVariable long orderNo) {
        InternalAccess.verify(security, secret);
        orders.recoverIssue(orderNo);
        return Result.ok();
    }

    @GetMapping("/changed")
    public Result<List<Long>> changed(@RequestHeader(AuthHeaders.X_INTERNAL_GATEWAY_SECRET) String secret,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime until, @RequestParam long after,
            @RequestParam int limit) {
        InternalAccess.verify(security, secret);
        return Result.ok(orders.changedOrders(from, until, after, Math.clamp(limit, 1, 100)));
    }

    @PostMapping("/{orderNo}/tickets/{ticketNo}/redeem")
    public Result<Boolean> redeem(@RequestHeader(AuthHeaders.X_INTERNAL_GATEWAY_SECRET) String secret,
            @PathVariable long orderNo, @PathVariable long ticketNo, @RequestBody TicketCode code) {
        InternalAccess.verify(security, secret);
        return Result.ok(orders.redeem(orderNo, ticketNo, code.qrCodeToken()));
    }

    public record TicketCode(String qrCodeToken) {}
}
