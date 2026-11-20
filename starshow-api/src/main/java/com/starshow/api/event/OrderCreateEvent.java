package com.starshow.api.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 抢票事务消息事件契约（TOPIC_ORDER_CREATE）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreateEvent implements Serializable {

    private String msgKey;
    private Long orderNo;
    private Long userId;
    private Long showId;
    private Long tierId;
    private String zoneId;
    private List<Integer> seatIndices;
    private Integer qty;
    private Long amountCent;
    private Long deductEpochSec;
    private String clientRequestId;
    private String requestFingerprint;
    private Integer saleConfigVersion;
    private String layoutVersion;
    private String strategy;
    private String traceId;
}
