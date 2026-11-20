package com.starshow.user.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户站内通知实体（对应表 t_user_notification，与开票提醒消费终态同事务落账）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_user_notification")
public class UserNotificationDO {

    public static final String TYPE_REMIND_BEFORE_SALE = "REMIND_BEFORE_SALE";
    public static final String TYPE_SCHEDULE_CHANGED = "SCHEDULE_CHANGED";

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long userId;
    private Long showId;
    private Long reservationId;
    private Integer saleConfigVersion;
    private String notificationType;
    private String bizKey;
    private String title;
    private String content;
    private String actionUrl;
    private Integer readFlag;
    private LocalDateTime readTime;

    @TableLogic
    private Integer deleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
