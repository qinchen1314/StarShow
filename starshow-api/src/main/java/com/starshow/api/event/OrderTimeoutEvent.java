package com.starshow.api.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 订单 15 分钟延迟超时关单检查事件契约（TOPIC_ORDER_TIMEOUT）
 * 生产与重投严格沿用同一绝对截止时间（expireTime / expireEpochMs）与稳定键 close-check:<orderNo>。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderTimeoutEvent implements Serializable {

    private String msgKey;
    private Long orderNo;
    private Long userId;
    private Long showId;
    private LocalDateTime expireTime;
    private Long expireEpochMs;
    private String traceId;
}
