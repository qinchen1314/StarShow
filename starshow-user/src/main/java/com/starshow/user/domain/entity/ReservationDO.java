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
 * 开票预约蓄水实体（对应表 t_reservation）
 * 提醒状态机（单向 CAS，1/4 为本版本终态，仅在主办方改期推进 remind_config_version 时重排）：
 * - 0 (REMIND_PENDING)：待投递 / 超 72h 时间轮窗口待入窗（由 next_retry_time 控制入窗调度时机）
 * - 3 (REMIND_SCHEDULED)：Broker 已确认受理排入时间轮，等待到点消费
 * - 2 (REMIND_FAILED)：投递失败或发送超时（结果未知），按 next_retry_time 退避重试
 * - 1 (REMIND_SENT / DELIVERED)：真经 MQ 消费送达并落站内通知
 * - 4 (REMIND_SKIPPED)：预约或改期时已开售 / 已结束 / 场次失效，无需发送提醒（与真送达 1 明确区分）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_reservation")
public class ReservationDO {

    public static final int REMIND_PENDING = 0;
    public static final int REMIND_SENT = 1;
    public static final int REMIND_FAILED = 2;
    public static final int REMIND_SCHEDULED = 3;
    public static final int REMIND_SKIPPED = 4;

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long userId;
    private Long showId;
    private Integer remindStatus;
    private Integer remindConfigVersion;
    private LocalDateTime nextRetryTime;

    @TableLogic
    private Integer deleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public int effectiveRemindConfigVersion() {
        return (remindConfigVersion == null || remindConfigVersion < 1) ? 1 : remindConfigVersion;
    }

    public static String remindStatusLabel(Integer status) {
        if (status == null) {
            return "NOT_RESERVED";
        }
        return switch (status) {
            case REMIND_PENDING -> "PENDING_SCHEDULE";
            case REMIND_SENT -> "DELIVERED";
            case REMIND_FAILED -> "FAILED_RETRYING";
            case REMIND_SCHEDULED -> "SCHEDULED";
            case REMIND_SKIPPED -> "SKIPPED_ALREADY_ON_SALE";
            default -> "UNKNOWN";
        };
    }
}
