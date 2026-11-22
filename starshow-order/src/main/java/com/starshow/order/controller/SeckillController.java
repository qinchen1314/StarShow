package com.starshow.order.controller;

import com.starshow.common.auth.UserContext;
import com.starshow.common.result.ErrorCode;
import com.starshow.common.result.Result;
import com.starshow.order.dto.SeckillOrderRequest;
import com.starshow.order.dto.SeckillTokenRequest;
import com.starshow.order.service.OrderQueryService;
import com.starshow.order.service.SeckillOrderService;
import com.starshow.order.service.SeckillTokenService;
import com.starshow.order.vo.SeckillOrderVO;
import com.starshow.order.vo.SeckillRequestStatusVO;
import com.starshow.order.vo.SeckillTokenVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 链路三抢票准入与下单控制器（只负责参数校验、认证上下文提取与响应包装）
 */
@RestController
@Validated
@RequestMapping("/api/v1/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private final SeckillTokenService seckillTokenService;
    private final SeckillOrderService seckillOrderService;
    private final OrderQueryService orderQueryService;

    @GetMapping("/requests/{clientRequestId}")
    public Result<SeckillRequestStatusVO> getRequestStatus(
            @PathVariable("clientRequestId") @NotBlank @Size(min = 8, max = 36) String clientRequestId,
            @RequestParam("showId") @Min(1) long showId) {
        return Result.ok(orderQueryService.getSeckillRequestStatus(UserContext.requireUserId(), showId, clientRequestId));
    }

    @PostMapping("/token")
    public Result<SeckillTokenVO> issueToken(@Valid @RequestBody SeckillTokenRequest request) {
        long userId = UserContext.requireUserId();
        return Result.ok(seckillTokenService.issueToken(userId, request));
    }

    @PostMapping("/orders")
    public ResponseEntity<Result<SeckillOrderVO>> placeOrder(@Valid @RequestBody SeckillOrderRequest request) {
        long userId = UserContext.requireUserId();
        SeckillOrderVO vo = seckillOrderService.placeOrder(userId, request);
        if ("PENDING_CONFIRM".equals(vo.getStatus())) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(Result.accepted(vo, ErrorCode.ORDER_QUEUING.getMessage()));
        }
        return ResponseEntity.ok(Result.ok(vo));
    }
}
