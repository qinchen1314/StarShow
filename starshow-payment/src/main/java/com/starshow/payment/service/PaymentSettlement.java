package com.starshow.payment.service;

import com.starshow.api.facade.OrderPaymentFacade;
import com.starshow.api.facade.OrderPaymentFacade.OrderSnapshot;
import com.starshow.api.facade.OrderPaymentFacade.PaidOrder;
import com.starshow.api.facade.PaymentReadFacade;
import com.starshow.common.exception.BizException;
import com.starshow.common.id.GeneSnowflakeIdGenerator;
import com.starshow.common.result.ErrorCode;
import com.starshow.payment.config.PaymentProperties;
import com.starshow.payment.repository.MockChannelRepository;
import com.starshow.payment.repository.MockChannelRepository.ChannelTxn;
import com.starshow.payment.repository.PaymentRepository;
import com.starshow.payment.repository.PaymentRepository.Payment;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "starshow.payment.enabled", havingValue = "true")
public class PaymentSettlement implements PaymentReadFacade {
    private final PaymentRepository payments;
    private final MockChannelRepository ledger;
    private final GeneSnowflakeIdGenerator ids;
    private final PaymentProperties settings;

    @Transactional
    public Payment register(OrderSnapshot order, LocalDateTime now) {
        LocalDateTime deadline = deadline(now, order.expireTime(), settings);
        if (now.isBefore(deadline)) {
            payments.register(ids.nextId(), order.orderNo(), order.userId(), order.amountCent(), "INIT",
                    deadline, order.expireTime());
        }
        Payment payment = payments.byOrder(order.orderNo(), true);
        if (payment == null || "CLOSED".equals(payment.status())) throw new BizException(ErrorCode.PAYMENT_WINDOW_CLOSED);
        if ("INIT".equals(payment.status())) {
            ledger.register("PAY", payment.payNo(), payment.orderNo(), payment.amountCent(), "INIT",
                    payment.expireTime(), null, null);
        }
        return payment;
    }

    public static LocalDateTime deadline(LocalDateTime now, LocalDateTime orderExpires, PaymentProperties settings) {
        LocalDateTime providerLimit = now.plusSeconds(settings.getChannelExpireSeconds());
        LocalDateTime closeLimit = orderExpires.minusSeconds(settings.getCloseGuardSeconds());
        return providerLimit.isBefore(closeLimit) ? providerLimit : closeLimit;
    }

    @Override
    @Transactional
    public CloseResult closeOrFindPaid(CloseRequest request) {
        long orderNo = request.orderNo();
        payments.register(ids.nextId(), orderNo, request.userId(), request.amountCent(), "CLOSED",
                request.orderExpireTime().minusSeconds(settings.getCloseGuardSeconds()), request.orderExpireTime());
        Payment payment = payments.byOrder(orderNo, true);
        if (payment == null || payment.userId() != request.userId() || payment.amountCent() != request.amountCent()) {
            throw BizException.serviceUnavailable(ErrorCode.CHANNEL_UNCERTAIN, 1500, "渠道订单资料待确认");
        }
        ledger.register("PAY", payment.payNo(), orderNo, payment.amountCent(), "CLOSED", payment.expireTime(), null, null);
        ChannelTxn txn = ledger.get("PAY", payment.payNo(), true);
        if ("SUCCESS".equals(txn.state())) {
            return new CloseResult(false, persistCapture(payment, txn));
        }
        if (!"INIT".equals(txn.state()) && !"CLOSED".equals(txn.state()) || payment.channelTradeNo() != null) {
            throw BizException.serviceUnavailable(ErrorCode.CHANNEL_UNCERTAIN, 1500, "渠道扣款状态待确认");
        }
        ledger.close(payment.payNo());
        payments.close(orderNo);
        return new CloseResult(true, null);
    }

    @Transactional
    public void acceptCallback(PaymentCallback callback) {
        Payment payment = payments.byNumber(callback.payNo(), true);
        if (payment == null || payment.orderNo() != callback.orderNo()) throw new BizException(ErrorCode.PAYMENT_NOT_FOUND);
        ChannelTxn txn = ledger.get("PAY", payment.payNo(), true);
        if (payment.amountCent() != callback.amountCent() || txn == null || txn.amountCent() != callback.amountCent()) {
            throw new BizException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
        if (!"SUCCESS".equals(callback.channelStatus()) || !Objects.equals(txn.channelTradeNo(), callback.channelTradeNo())
                || !Objects.equals(txn.confirmedAt(), callback.paidTime())) {
            throw new BizException(ErrorCode.PAYMENT_SIGN_INVALID, "支付回执与渠道账本不一致");
        }
        persistCapture(payment, txn);
    }

    @Transactional
    public void discover(long payNo) {
        Payment payment = payments.byNumber(payNo, true);
        if (payment == null || payment.channelTradeNo() != null) return;
        ChannelTxn txn = ledger.get("PAY", payNo, true);
        if (txn != null && "SUCCESS".equals(txn.state())) persistCapture(payment, txn);
    }

    @Transactional
    public void finish(Payment pending, OrderPaymentFacade.Settlement outcome) {
        Payment payment = payments.byNumber(pending.payNo(), true);
        if (payment == null || !payment.syncPending()) return;
        if (outcome == OrderPaymentFacade.Settlement.CLOSED) {
            payments.registerRefund(ids.nextId(), payment, "订单关闭后到账，自动原路退回", "ABNORMAL_PAID", LocalDateTime.now());
            payments.finishSync(payment.orderNo(), "ABNORMAL_PAID");
        } else if (outcome == OrderPaymentFacade.Settlement.APPLIED) {
            payments.finishSync(payment.orderNo(), "SUCCESS");
        } else {
            throw new IllegalStateException("Order rejected the captured payment");
        }
    }

    private PaidOrder persistCapture(Payment payment, ChannelTxn txn) {
        if (!"SUCCESS".equals(txn.state()) || txn.orderNo() != payment.orderNo() || txn.confirmedAt() == null
                || txn.channelTradeNo() == null || txn.amountCent() != payment.amountCent()
                || (payment.channelTradeNo() != null && !payment.channelTradeNo().equals(txn.channelTradeNo()))) {
            throw new BizException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
        if (payment.channelTradeNo() == null) payments.recordPaid(payment, txn.channelTradeNo(), txn.confirmedAt());
        return new PaidOrder(payment.orderNo(), payment.userId(), payment.amountCent(), txn.channelTradeNo(), txn.confirmedAt());
    }

    public record PaymentCallback(long payNo, long orderNo, String channelTradeNo, long amountCent,
                                  String channelStatus, LocalDateTime paidTime) {}
}
