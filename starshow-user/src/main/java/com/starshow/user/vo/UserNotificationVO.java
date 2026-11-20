package com.starshow.user.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 站内开票提醒视图（支持用户换设备查看提醒内容、直达购票入口并独立统计可见与打开指标）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserNotificationVO {

    private Long notificationId;
    private Long showId;
    private Long reservationId;
    private Integer saleConfigVersion;
    private String notificationType;
    private String bizKey;
    private String title;
    private String content;
    private String actionUrl;
    private boolean read;
    private LocalDateTime readTime;
    private LocalDateTime createTime;
}
