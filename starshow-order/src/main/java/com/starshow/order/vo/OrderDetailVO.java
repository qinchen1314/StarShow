package com.starshow.order.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 持久订单详情响应体（GET /api/v1/orders/{orderNo}）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderDetailVO {

    private Long orderNo;
    private Long userId;
    private Long showId;
    private Long tierId;
    private String zoneId;
    private List<Integer> seatIndices;
    private Integer qty;
    private Long amountCent;
    private String status;
    private Integer saleConfigVersion;
    private String layoutVersion;
    private String seckillStrategy;
    private String payTradeNo;
    private String closeReason;
    private OffsetDateTime expireTime;
    private OffsetDateTime paidTime;
    private OffsetDateTime createTime;
    /** 链路四扩展槽位：关单后滞后异常到账对应的自动退款单号与状态 */
    private Long abnormalRefundNo;
    private String abnormalRefundStatus;
}
