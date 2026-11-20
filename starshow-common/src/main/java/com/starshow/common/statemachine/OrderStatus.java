package com.starshow.common.statemachine;

import lombok.Getter;

/**
 * 订单核心状态枚举（单向不可逆）
 */
@Getter
public enum OrderStatus {

    WAIT_PAY("待支付"),
    PAID("已支付待出票"),
    TICKETED("已出票履约"),
    CLOSED("已关闭"),
    REFUNDING("退款中"),
    REFUNDED("已退款");

    private final String desc;

    OrderStatus(String desc) {
        this.desc = desc;
    }
}
