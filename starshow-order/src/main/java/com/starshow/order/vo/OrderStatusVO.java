package com.starshow.order.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 本人订单办理状态查询响应体（GET /api/v1/orders/{orderNo}/status）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderStatusVO {

    private Long orderNo;
    private Long showId;
    private String status;
    private String failCode;
    private boolean retryable;
    private Long pollIntervalMs;
    private OffsetDateTime expireTime;
    private OffsetDateTime serverTime;
    private OffsetDateTime paymentCreateDeadline;
    private String nextAction;
    private Long retryAfterMs;
    private boolean canStartNew;
}
