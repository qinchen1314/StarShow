package com.starshow.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshow.api.facade.OrderPaymentFacade;
import com.starshow.api.facade.OrderPaymentFacade.PaidOrder;
import com.starshow.common.crypto.HmacSigner;
import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.payment.config.PaymentProperties;
import com.starshow.payment.repository.MockChannelRepository;
import com.starshow.payment.repository.MockChannelRepository.ChannelTxn;
import com.starshow.payment.repository.PaymentRepository;
import com.starshow.payment.repository.PaymentRepository.Payment;
import com.starshow.payment.service.MockPayChannel.Fault;
import com.starshow.payment.service.PaymentSettlement.PaymentCallback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "starshow.payment.enabled", havingValue = "true")
public class PaymentApplicationService {
    private final OrderPaymentFacade orders;
    private final PaymentSettlement settlement;
    private final MockPayChannel mock;
    private final PaymentRepository payments;
    private final MockChannelRepository ledger;
    private final PaymentProperties settings;
    private final ObjectMapper json;

    public PaymentView create(long orderNo, long userId) {
        var order = orders.get(orderNo, userId);
        if (order == null) throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        Payment payment = payments.byOrder(orderNo, false);
        if (payment != null && !"CLOSED".equals(payment.status()) && !"WAIT_PAY".equals(order.status())) {
            return view(payment);
        }
        if (!"WAIT_PAY".equals(order.status())) throw new BizException(ErrorCode.ORDER_STATE_ILLEGAL);
        return view(settlement.register(order, LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)));
    }

    public PaymentView get(long payNo, long userId) {
        Payment payment = ownedPayment(payNo, userId);
        settlement.discover(payNo);
        syncIfPossible(payments.byNumber(payNo, false));
        return view(payments.byNumber(payment.payNo(), false));
    }

    public PaymentView getForOrder(long orderNo, long userId) {
        Payment payment = payments.byOrder(orderNo, false);
        if (payment == null || payment.userId() != userId) throw new BizException(ErrorCode.PAYMENT_NOT_FOUND);
        return get(payment.payNo(), userId);
    }

    public MockResult mockPay(long payNo, long userId, Fault fault) {
        if (!settings.isMockEnabled() || (fault != Fault.NONE && !settings.isFaultEnabled())) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        ChannelTxn txn = mock.requestPayment(ownedPayment(payNo, userId), fault);
        if (Fault.NONE.name().equals(txn.deliveryFault()) || Fault.DUPLICATE_3X.name().equals(txn.deliveryFault())) {
            deliver(txn);
        }
        return new MockResult(payNo, "SUCCESS".equals(txn.state()) ? "ACCEPTED" : "PENDING");
    }

    public void callback(String timestamp, String signature, byte[] body) {
        long seconds;
        try {
            seconds = Long.parseLong(timestamp);
        } catch (NumberFormatException ex) {
            throw new BizException(ErrorCode.PAYMENT_SIGN_INVALID);
        }
        long now = Instant.now().getEpochSecond();
        long skew = settings.getSignatureSkewSeconds();
        if (seconds < now - skew || seconds > now + skew || body.length > 4096
                || !HmacSigner.matches(sign(timestamp, body), signature)) {
            throw new BizException(ErrorCode.PAYMENT_SIGN_INVALID);
        }
        PaymentCallback event;
        try {
            event = json.readValue(body, PaymentCallback.class);
        } catch (IOException ex) {
            throw new BizException(ErrorCode.PAYMENT_SIGN_INVALID);
        }
        if (event == null || event.payNo() <= 0 || event.orderNo() <= 0 || event.channelTradeNo() == null
                || event.paidTime() == null) throw new BizException(ErrorCode.PAYMENT_SIGN_INVALID);
        settlement.acceptCallback(event);
        syncIfPossible(payments.byNumber(event.payNo(), false));
    }

    public void deliver(ChannelTxn initial) {
        ChannelTxn txn = initial;
        if (Fault.RACE_WITH_CLOSE.name().equals(txn.deliveryFault()) && !"SUCCESS".equals(txn.state())) {
            var order = orders.get(txn.orderNo(), 0);
            if (order == null || !"CLOSED".equals(order.status())) {
                ledger.nextDelivery(txn.merchantBizNo(), LocalDateTime.now().plusSeconds(5));
                return;
            }
            txn = mock.captureAfterClose(txn.merchantBizNo());
        }
        if (!"SUCCESS".equals(txn.state())) return;
        try {
            byte[] body = json.writeValueAsBytes(new PaymentCallback(txn.merchantBizNo(), txn.orderNo(),
                    txn.channelTradeNo(), txn.amountCent(), txn.state(), txn.confirmedAt()));
            String timestamp = Long.toString(Instant.now().getEpochSecond());
            String signature = sign(timestamp, body);
            int deliveries = Fault.DUPLICATE_3X.name().equals(txn.deliveryFault()) ? 3 : 1;
            for (int i = 0; i < deliveries; i++) callback(timestamp, signature, body);
            ledger.nextDelivery(txn.merchantBizNo(), null);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot encode mock callback", ex);
        }
    }

    public void syncIfPossible(Payment payment) {
        if (payment == null || !payment.syncPending()) return;
        try {
            var result = orders.applyPaid(new PaidOrder(payment.orderNo(), payment.userId(), payment.amountCent(),
                    payment.channelTradeNo(), payment.paidTime()));
            settlement.finish(payment, result);
        } catch (RuntimeException ex) {
            payments.postponeSync(payment.payNo(), LocalDateTime.now().plusSeconds(15));
            log.warn("Payment awaiting order confirmation, payNo={}", payment.payNo());
        }
    }

    private Payment ownedPayment(long payNo, long userId) {
        Payment payment = payments.byNumber(payNo, false);
        if (payment == null || payment.userId() != userId) throw new BizException(ErrorCode.PAYMENT_NOT_FOUND);
        return payment;
    }

    private PaymentView view(Payment payment) {
        LocalDateTime now = LocalDateTime.now();
        var abnormal = "ABNORMAL_PAID".equals(payment.status()) ? payments.refundByOrder(payment.orderNo()) : null;
        String action = switch (payment.status()) {
            case "INIT" -> now.isBefore(payment.expireTime()) ? "PAY" : "CHECK_PAYMENT";
            case "SUCCESS" -> payment.syncPending() ? "CHECK_PAYMENT" : "VIEW_ORDER";
            case "ABNORMAL_PAID" -> "VIEW_REFUND";
            default -> "VIEW_ORDER";
        };
        return new PaymentView(payment.payNo(), payment.orderNo(), payment.amountCent(), payment.status(),
                offset(payment.expireTime()), offset(payment.orderExpireTime()), offset(now), action,
                abnormal == null ? null : abnormal.refundNo(), abnormal == null ? null : abnormal.status());
    }

    private String sign(String timestamp, byte[] body) {
        return HmacSigner.sign(settings.getCallbackSecret(), timestamp + "\n", body);
    }

    static OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    public record PaymentView(long payNo, long orderNo, long amountCent, String paymentStatus,
                              OffsetDateTime paymentExpireTime, OffsetDateTime orderExpireTime, OffsetDateTime serverTime,
                              String nextAction, Long abnormalRefundNo, String abnormalRefundStatus) {}
    public record MockResult(long payNo, String channelStatus) {}
}
