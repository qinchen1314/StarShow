package com.starshow.common.statemachine;

import com.starshow.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrderStateMachineTest {

    @Test
    void shouldAllowValidTransitions() {
        assertTrue(OrderStateMachine.canTransit(OrderStatus.WAIT_PAY, OrderStatus.PAID));
        assertTrue(OrderStateMachine.canTransit(OrderStatus.WAIT_PAY, OrderStatus.CLOSED));
        assertTrue(OrderStateMachine.canTransit(OrderStatus.PAID, OrderStatus.TICKETED));
        assertTrue(OrderStateMachine.canTransit(OrderStatus.PAID, OrderStatus.REFUNDING));
        assertTrue(OrderStateMachine.canTransit(OrderStatus.TICKETED, OrderStatus.REFUNDING));
        assertTrue(OrderStateMachine.canTransit(OrderStatus.REFUNDING, OrderStatus.REFUNDED));
    }

    @Test
    void shouldForbidResurrectingClosedOrder() {
        assertFalse(OrderStateMachine.canTransit(OrderStatus.CLOSED, OrderStatus.PAID));
        assertFalse(OrderStateMachine.canTransit(OrderStatus.CLOSED, OrderStatus.TICKETED));
        assertFalse(OrderStateMachine.canTransit(OrderStatus.REFUNDED, OrderStatus.PAID));
        assertThrows(BizException.class, () ->
                OrderStateMachine.assertCanTransit(OrderStatus.CLOSED, OrderStatus.PAID));
    }
}
