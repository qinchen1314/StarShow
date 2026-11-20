package com.starshow.order.domain.entity;

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
 * 订单异步动作与创单裁决恢复表（t_order_action）：
 * - CREATE_DECISION：消费端与悬挂巡检共享的唯一行锁裁决（APPLIED vs REJECTED）
 * - CLOSE_CHECK：创单落库同事务记录的 15 分钟延迟关单检查任务（PENDING -> SENT）
 * - RELEASE_STOCK：明确拒绝/终止后的 V4 预扣幂等回补任务（PENDING -> DONE）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_order_action")
public class OrderActionDO {

    public static final String TYPE_CREATE_DECISION = "CREATE_DECISION";
    public static final String TYPE_CLOSE_CHECK = "CLOSE_CHECK";
    public static final String TYPE_RELEASE_STOCK = "RELEASE_STOCK";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPLIED = "APPLIED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.INPUT)
    private Long actionId;
    private Long orderNo;
    private Long userId;
    private Long showId;
    private String actionType;
    private String status;
    private String payloadJson;
    private LocalDateTime nextRetryAt;
    private LocalDateTime leaseUntil;
    private Integer attempt;
    private String lastErrorCode;
    @TableLogic
    private Integer deleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
