package com.starshow.order.mq;

import com.starshow.api.event.OrderCreateEvent;
import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * V4 事务消息执行上下文（在 sendMessageInTransaction 与 executeLocalTransaction 之间传递结果细节）
 */
@Data
@Builder
public class OrderCreateTxContext {

    private OrderCreateEvent event;
    private Long leaseVersion;
    private boolean manualSeat;
    private int expectedSaleConfigVersion;
    private String layoutVersion;

    /** 可选测试故障注入开关：在 Lua 成功预扣后强制返回 UNKNOWN，用于真实触发 RocketMQ Broker 回查补单剧本 */
    private boolean simulateUnknownAfterDeduct;

    /** executeLocalTransaction 写回的 Lua 返回码与冲突偏移 */
    private volatile Long luaCode;
    private volatile List<Integer> conflictSeatIndices;
    private volatile Throwable localException;

    public List<Integer> safeConflictSeats() {
        return conflictSeatIndices == null ? Collections.emptyList() : conflictSeatIndices;
    }
}
