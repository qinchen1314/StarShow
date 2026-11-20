package com.starshow.order.controller;

import com.starshow.common.auth.UserContext;
import com.starshow.common.result.Result;
import com.starshow.order.service.OrderQueryService;
import com.starshow.order.vo.CurrentShowOrderVO;
import com.starshow.order.vo.OrderDetailVO;
import com.starshow.order.vo.OrderListVO;
import com.starshow.order.vo.OrderStatusVO;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 链路三本人订单状态、跨设备找回、详情与列表查询控制器
 */
@Validated
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderQueryService orderQueryService;

    @GetMapping("/current")
    public Result<CurrentShowOrderVO> getCurrentShowOrder(
            @RequestParam("showId") @Min(value = 1, message = "showId 必须为正整数") long showId) {
        long userId = UserContext.requireUserId();
        return Result.ok(orderQueryService.getCurrentShowOrder(userId, showId));
    }

    @GetMapping("/{orderNo}/status")
    public Result<OrderStatusVO> getOrderStatus(
            @PathVariable("orderNo") long orderNo,
            @RequestParam(value = "showId", required = false) Long showId) {
        long userId = UserContext.requireUserId();
        return Result.ok(orderQueryService.getOrderStatus(userId, orderNo, showId));
    }

    @GetMapping("/{orderNo}")
    public Result<OrderDetailVO> getOrderDetail(@PathVariable("orderNo") long orderNo) {
        long userId = UserContext.requireUserId();
        return Result.ok(orderQueryService.getOrderDetail(userId, orderNo));
    }

    @GetMapping
    public Result<OrderListVO> listMyOrders(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "size", required = false) Integer size) {
        long userId = UserContext.requireUserId();
        return Result.ok(orderQueryService.listMyOrders(userId, status, cursor, size));
    }
}
