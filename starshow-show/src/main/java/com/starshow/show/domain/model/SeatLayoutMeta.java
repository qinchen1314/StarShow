package com.starshow.show.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Redis 分区座位布局元数据（starshow:seat:layout:{showId}:<zoneId>）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeatLayoutMeta implements Serializable {

    private Long showId;
    private String zoneId;
    private Long tierId;
    private Integer version;
    private int maxSeatIndex;
    private int byteLength;
    private int totalSeatCount;
    private int initialSelectableCount;
}
