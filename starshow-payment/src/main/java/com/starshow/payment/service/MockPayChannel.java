package com.starshow.payment.service;

import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.payment.repository.MockChannelRepository;
import com.starshow.payment.repository.MockChannelRepository.ChannelTxn;
import com.starshow.payment.repository.PaymentRepository.Payment;
import com.starshow.payment.repository.PaymentRepository.Refund;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "starshow.payment.enabled", havingValue = "true")
public class MockPayChannel {
    private final MockChannelRepository ledger;

    // Capture commits independently of the application's callback processing.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ChannelTxn requestPayment(Payment payment, Fault fault) {
        ChannelTxn txn = ledger.get("PAY", payment.payNo(), true);
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        if (txn == null) throw new BizException(ErrorCode.PAYMENT_NOT_FOUND);
        if ("SUCCESS".equals(txn.state())) return txn;
        if (!"INIT".equals(txn.state()) || !now.isBefore(txn.expireTime())) {
            throw new BizException(ErrorCode.PAYMENT_WINDOW_CLOSED);
        }
        if (txn.deliveryFault() != null) return txn;
        if (fault == Fault.RACE_WITH_CLOSE) {
            ledger.setDelivery(payment.payNo(), "INIT", payment.orderExpireTime(), fault.name());
        } else {
            ledger.capture(payment.payNo(), "INIT", UUID.randomUUID().toString(), now);
            LocalDateTime delivery = switch (fault) {
                case DELAY_20S -> now.plusSeconds(20);
                case DROP_CALLBACK -> null;
                default -> now;
            };
            ledger.setDelivery(payment.payNo(), "SUCCESS", delivery, fault.name());
        }
        return ledger.get("PAY", payment.payNo(), false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ChannelTxn captureAfterClose(long payNo) {
        ChannelTxn txn = ledger.get("PAY", payNo, true);
        if ("CLOSED".equals(txn.state()) && Fault.RACE_WITH_CLOSE.name().equals(txn.deliveryFault())) {
            ledger.capture(payNo, "CLOSED", UUID.randomUUID().toString(), LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
        }
        return ledger.get("PAY", payNo, false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ChannelTxn refund(Payment payment, Refund refund) {
        ChannelTxn source = ledger.get("PAY", payment.payNo(), true);
        if (source == null || !"SUCCESS".equals(source.state()) || source.orderNo() != refund.orderNo()
                || source.amountCent() != refund.amountCent() || !source.channelTradeNo().equals(payment.channelTradeNo())) {
            throw new IllegalStateException("Refund lacks matching captured funds");
        }
        ledger.register("REFUND", refund.refundNo(), refund.orderNo(), refund.amountCent(), "SUCCESS", null,
                UUID.randomUUID().toString(), LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
        return ledger.get("REFUND", refund.refundNo(), false);
    }

    public enum Fault { NONE, DUPLICATE_3X, DROP_CALLBACK, DELAY_20S, RACE_WITH_CLOSE }
}
