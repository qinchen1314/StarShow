package com.starshow.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 票档与演出只读快照（用于下单金额计算、开票时间校验与区域定位）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TierSnapshotDTO implements Serializable {

    private Long showId;
    private Long tierId;
    private String tierName;
    private String zoneId;
    private Long priceCent;
    private String saleStatus;
    private String seatMode;
    private LocalDateTime saleStartTime;
    private LocalDateTime saleEndTime;
}
