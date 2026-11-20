package com.starshow.api.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 链路一：开票前延迟提醒事件（RocketMQ 5.x 任意时间延迟消息）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShowRemindEvent implements Serializable {

    private Long reservationId;
    private Long userId;
    private Long showId;
    private String showTitle;
    private LocalDateTime saleStartTime;
    private Integer saleConfigVersion;
    private String traceId;

    public int effectiveSaleConfigVersion() {
        return (saleConfigVersion == null || saleConfigVersion < 1) ? 1 : saleConfigVersion;
    }
}
