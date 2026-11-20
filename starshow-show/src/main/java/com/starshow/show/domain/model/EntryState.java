package com.starshow.show.domain.model;

/**
 * 演出购票入口与票档库存统一权威状态枚举（严格四态不误报）：
 * - PREPARING：未到开售时间、或预热未完成/改期中；
 * - OPEN：在售窗口内、门禁就绪且有有效余票可尝试购买；
 * - SOLD_OUT：在售窗口内且有效余票为 0（支持未支付关单/退票回流）；
 * - STOCK_UNAVAILABLE：已开售但 Redis 键缺失或异常（严禁误报为 SOLD_OUT）；
 * - ENDED：已停售或超过 saleEndTime（即使有余量也停止受理）。
 */
public enum EntryState {
    PREPARING,
    OPEN,
    SOLD_OUT,
    STOCK_UNAVAILABLE,
    ENDED
}
