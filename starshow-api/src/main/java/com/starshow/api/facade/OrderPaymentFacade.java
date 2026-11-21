package com.starshow.api.facade;

import java.time.LocalDateTime;
import java.util.List;

public interface OrderPaymentFacade {
    OrderSnapshot get(long orderNo, long userId);
    Settlement applyPaid(PaidOrder payment);
    RefundLock lockRefund(long orderNo, long userId, boolean preview);
    boolean completeRefund(long orderNo, long userId);
    void recoverIssue(long orderNo);
    List<Long> changedOrders(LocalDateTime from, LocalDateTime until, long after, int limit);

    enum Settlement { APPLIED, CLOSED, MISMATCH }
    enum RefundLock { ACCEPTED, INELIGIBLE }

    record PaidOrder(long orderNo, long userId, long amountCent, String channelTradeNo, LocalDateTime paidTime) {}

    record OrderSnapshot(long orderNo, long userId, long amountCent, String status, LocalDateTime expireTime,
                         String channelTradeNo, int qty, int ticketCount, int validTicketCount, String releaseStatus) {}
}
