package com.starshow.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 演出改期后联动重排预约提醒的内部响应契约
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShowRescheduleSyncDTO implements Serializable {

    private Long showId;
    private Integer saleConfigVersion;
    private LocalDateTime saleStartTime;
    private int resetReservationCount;
    private int progressedCount;
    private int scheduleNotifiedCount;
}
