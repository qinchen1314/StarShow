package com.starshow.user.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 主办方改期后预约提醒联动重排结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShowRescheduleResultVO {

    private Long showId;
    private Integer saleConfigVersion;
    private LocalDateTime saleStartTime;
    private int resetReservationCount;
    private int progressedCount;
    private int scheduleNotifiedCount;
}
