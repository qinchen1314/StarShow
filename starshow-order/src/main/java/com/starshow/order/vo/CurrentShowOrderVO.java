package com.starshow.order.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 换设备/杀 App 跨设备恢复响应体（GET /api/v1/orders/current?showId=...）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CurrentShowOrderVO {

    private boolean hasOrder;
    private Long showId;
    private Long orderNo;
    private String status;
    private Long amountCent;
    private OffsetDateTime expireTime;
    private Long pollIntervalMs;
    private boolean retryable;
    private String failCode;
}
