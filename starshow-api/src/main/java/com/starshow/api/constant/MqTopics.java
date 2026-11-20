package com.starshow.api.constant;

/**
 * RocketMQ 5.x Topic 与 Consumer Group 契约常量
 */
public final class MqTopics {

    private MqTopics() {
    }

    /** 链路一：开票前 10 分钟任意时间延迟提醒 */
    public static final String SHOW_REMIND = "TOPIC_SHOW_REMIND";
    public static final String CG_REMIND_NOTIFY = "cg-remind-notify";

    /** 链路三：抢票事务消息（Lua 预扣 -> MySQL 三层幂等落单） */
    public static final String ORDER_CREATE = "TOPIC_ORDER_CREATE";
    public static final String CG_ORDER_CREATE = "cg-order-create";

    /** 链路四：订单 15 分钟延迟超时关单 */
    public static final String ORDER_TIMEOUT = "TOPIC_ORDER_TIMEOUT";
    public static final String CG_ORDER_TIMEOUT = "cg-order-timeout";

    /** 链路四：支付成功触发电子票履约出票 */
    public static final String TICKET_ISSUE = "TOPIC_TICKET_ISSUE";
    public static final String CG_TICKET_ISSUE = "cg-ticket-issue";

    /** 链路四：退款 Saga 逆向回滚与库存回补 */
    public static final String REFUND_EVENT = "TOPIC_REFUND_EVENT";
    public static final String CG_REFUND_SAGA = "cg-refund-saga";
}
