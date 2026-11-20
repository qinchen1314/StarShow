package com.starshow.common.statemachine;

import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 订单状态机流转矩阵
 * 核心安全底线：
 * - CLOSED 为绝对终态，严禁复活回 PAID（关单后滞后支付必须走异常退款）；
 * - 任何数据库更新仍需配合 SQL WHERE status = expected 做 CAS 双重保障。
 */
public final class OrderStateMachine {

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED_TRANSITIONS = new EnumMap<>(OrderStatus.class);

    static {
        ALLOWED_TRANSITIONS.put(OrderStatus.WAIT_PAY, EnumSet.of(OrderStatus.PAID, OrderStatus.CLOSED));
        ALLOWED_TRANSITIONS.put(OrderStatus.PAID, EnumSet.of(OrderStatus.TICKETED, OrderStatus.REFUNDING));
        ALLOWED_TRANSITIONS.put(OrderStatus.TICKETED, EnumSet.of(OrderStatus.REFUNDING));
        ALLOWED_TRANSITIONS.put(OrderStatus.REFUNDING, EnumSet.of(OrderStatus.REFUNDED));
        ALLOWED_TRANSITIONS.put(OrderStatus.CLOSED, EnumSet.noneOf(OrderStatus.class));
        ALLOWED_TRANSITIONS.put(OrderStatus.REFUNDED, EnumSet.noneOf(OrderStatus.class));
    }

    private OrderStateMachine() {
    }

    public static boolean canTransit(OrderStatus from, OrderStatus to) {
        if (from == null || to == null) {
            return false;
        }
        Set<OrderStatus> targets = ALLOWED_TRANSITIONS.get(from);
        return targets != null && targets.contains(to);
    }

    public static void assertCanTransit(OrderStatus from, OrderStatus to) {
        if (!canTransit(from, to)) {
            throw new BizException(
                    ErrorCode.ORDER_STATE_ILLEGAL,
                    "非法订单状态跃迁: " + from + " -> " + to
            );
        }
    }
}
