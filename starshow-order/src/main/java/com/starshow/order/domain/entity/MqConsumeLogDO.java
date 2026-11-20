package com.starshow.order.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * MQ 消费幂等日志表（与订单分表同库同事务提交）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_mq_consume_log")
public class MqConsumeLogDO {

    @TableId(type = IdType.INPUT)
    private String msgKey;
    private String topic;
    private Long userId;
    private String bizNo;
    private Integer consumeStatus;
    private LocalDateTime createTime;
}
