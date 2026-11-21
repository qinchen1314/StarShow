package com.starshow.api.facade;

import java.time.LocalDateTime;

public interface PaymentReadFacade {
    CloseResult closeOrFindPaid(CloseRequest request);

    record CloseRequest(long orderNo, long userId, long amountCent, LocalDateTime orderExpireTime) {}
    record CloseResult(boolean closed, OrderPaymentFacade.PaidOrder payment) {}
}
