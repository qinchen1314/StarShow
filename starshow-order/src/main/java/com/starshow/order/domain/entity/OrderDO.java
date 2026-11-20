package com.starshow.order.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 订单分表实体（对应 t_order_0..3，按 user_id % 4 基因分片）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderDO {

    private Long orderNo;
    private Long userId;
    private Long showId;
    private Long tierId;
    private String zoneId;
    private String seatIndices;
    private Integer qty;
    private Long amountCent;
    private String status;
    private Long activeFlag;
    private String requestId;
    private String requestFingerprint;
    private Integer saleConfigVersion;
    private String layoutVersion;
    private String seckillStrategy;
    private String payTradeNo;
    private String closeReason;
    private LocalDateTime expireTime;
    private LocalDateTime paidTime;
    private Integer deleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
