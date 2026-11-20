package com.starshow.order.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 抢票下单响应体（POST /api/v1/seckill/orders）：
 * - V1~V3 同步落单：HTTP 200/00000，status=WAIT_PAY，携带 expireTime；
 * - V4 异步受理：HTTP 202/00202，status=PENDING_CONFIRM，携带 pollIntervalMs。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeckillOrderVO {

    private Long orderNo;
    private Long showId;
    private Long tierId;
    private Integer qty;
    private Long amountCent;
    private String status;
    private String strategy;
    private OffsetDateTime expireTime;
    private Long pollIntervalMs;
}
