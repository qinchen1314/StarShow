package com.starshow.order.service;

import com.starshow.api.dto.TierSnapshotDTO;
import com.starshow.order.dto.SeckillOrderRequest;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * V1~V4 抢票策略统一执行上下文
 */
@Data
@Builder
public class SeckillExecutionContext {

    private long userId;
    private long orderNo;
    private SeckillOrderRequest request;
    private TierSnapshotDTO tier;
    private List<Integer> normalizedSeats;
    private long totalAmountCent;
    private String requestFingerprint;
    private int authoritativeConfigVersion;
    private String authoritativeLayoutVersion;
    private String strategyName;
    private boolean manualSeat;
    private boolean simulateUnknownAfterDeduct;
}
