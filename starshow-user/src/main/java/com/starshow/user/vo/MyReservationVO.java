package com.starshow.user.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 本人场次预约状态与下一步引导视图（解决请求超时 / 换设备后的状态确认与最新开售时间联动）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MyReservationVO {

    private boolean reserved;
    private boolean realNameVerified;
    private Long reservationId;
    private Long showId;
    private String showTitle;
    private LocalDateTime saleStartTime;
    private LocalDateTime saleEndTime;
    private LocalDateTime serverTime;
    private String saleStatus;
    private Integer saleConfigVersion;
    private Integer remindStatus;
    private String remindStatusLabel;
    private boolean entryOpen;
    /**
     * 下一步业务引导：VERIFY_IDENTITY / RESERVE / WAIT_FOR_SALE / ENTER_SECKILL / SALE_ENDED
     */
    private String nextAction;
}
