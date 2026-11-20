package com.starshow.order.service;

import com.starshow.order.vo.SeckillOrderVO;

/**
 * 抢票演进策略抽象接口（V1 乐观锁 / V2 场次锁 / V3 票档锁 / V4 Lua + 事务消息）
 */
public interface SeckillStrategy {

    /**
     * 策略标识：V1 / V2 / V3 / V4
     */
    String name();

    /**
     * 执行本策略下单核心逻辑：
     * - V1~V3：同步落库返回 WAIT_PAY（HTTP 200）；
     * - V4：发送事务 Half 消息 + Lua 原子预扣确认后返回 PENDING_CONFIRM（HTTP 202）。
     */
    SeckillOrderVO execute(SeckillExecutionContext ctx);
}
