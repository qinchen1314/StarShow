package com.starshow.order.controller;

import com.starshow.common.auth.UserContext;
import com.starshow.common.result.Result;
import com.starshow.order.service.OrderClosingService;
import com.starshow.order.service.OrderFulfillmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "starshow.payment.enabled", havingValue = "true")
public class FulfillmentController {
    private final OrderFulfillmentService fulfillment;
    private final OrderClosingService closing;

    @PostMapping("/api/v1/orders/{orderNo}/cancel")
    public Result<OrderClosingService.CancelView> cancel(@PathVariable long orderNo) {
        return Result.ok(closing.cancel(orderNo, UserContext.requireUserId()));
    }

    @GetMapping("/api/v1/tickets/order/{orderNo}")
    public Result<OrderFulfillmentService.TicketsView> tickets(@PathVariable long orderNo) {
        return Result.ok(fulfillment.ticketView(orderNo, UserContext.requireUserId()));
    }
}
