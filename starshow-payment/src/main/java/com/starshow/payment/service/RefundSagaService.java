package com.starshow.payment.service;

import com.starshow.api.facade.OrderPaymentFacade;
import com.starshow.api.facade.OrderPaymentFacade.RefundLock;
import com.starshow.common.exception.BizException;
import com.starshow.common.id.GeneSnowflakeIdGenerator;
import com.starshow.common.result.ErrorCode;
import com.starshow.payment.repository.PaymentRepository;
import com.starshow.payment.repository.PaymentRepository.Refund;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "starshow.payment.enabled", havingValue = "true")
public class RefundSagaService {
    private final PaymentRepository payments;
    private final OrderPaymentFacade orders;
    private final GeneSnowflakeIdGenerator ids;
    private final MockPayChannel channel;

    public RefundView request(long orderNo, long userId, String reason) {
        var payment = payments.byOrder(orderNo, false);
        if (payment == null || payment.userId() != userId) throw new BizException(ErrorCode.REFUND_NOT_ALLOWED);
        Refund existing = payments.refundByOrder(orderNo);
        if (existing != null) return view(existing);
        if (!"SUCCESS".equals(payment.status()) || payment.syncPending()
                || orders.lockRefund(orderNo, userId, true) != RefundLock.ACCEPTED) {
            throw new BizException(ErrorCode.REFUND_NOT_ALLOWED, "仅开演前未核销的已支付订单可申请整单退款");
        }
        if (reason != null && reason.length() > 128) throw new BizException(ErrorCode.INVALID_PARAM, "退款原因最多 128 个字符");
        payments.registerRefund(ids.nextId(), payment, reason == null ? "" : reason.trim(), "NORMAL", LocalDateTime.now());
        return view(payments.refundByOrder(orderNo));
    }

    public RefundView get(long refundNo, long userId) {
        Refund refund = payments.refundByNumber(refundNo);
        if (refund == null || refund.userId() != userId) throw new BizException(ErrorCode.REFUND_NOT_FOUND);
        return view(refund);
    }

    public RefundView getForOrder(long orderNo, long userId) {
        Refund refund = payments.refundByOrder(orderNo);
        if (refund == null || refund.userId() != userId) throw new BizException(ErrorCode.REFUND_NOT_FOUND);
        return view(refund);
    }

    public void advance(long refundNo) {
        Refund refund = payments.refundByNumber(refundNo);
        if (refund == null || !"PROCESSING".equals(refund.status())) return;
        boolean abnormal = "ABNORMAL_PAID".equals(refund.refundType());
        try {
            switch (refund.sagaStep()) {
                case "INIT" -> payments.advance(refund, "LOCK_TICKET", "PROCESSING", null, null);
                case "LOCK_TICKET" -> {
                    boolean allowed = abnormal || orders.lockRefund(refund.orderNo(), refund.userId(), false) == RefundLock.ACCEPTED;
                    payments.advance(refund, "REFUND_PAY", allowed ? "PROCESSING" : "FAILED", null,
                            allowed ? null : "REFUND_NOT_ALLOWED");
                }
                case "REFUND_PAY" -> {
                    var payment = payments.byOrder(refund.orderNo(), false);
                    if (payment == null || !(abnormal ? "ABNORMAL_PAID" : "SUCCESS").equals(payment.status())) {
                        throw new IllegalStateException("Missing confirmed source payment");
                    }
                    var captured = channel.refund(payment, refund);
                    payments.advance(refund, abnormal ? "DONE" : "ROLLBACK_STOCK", abnormal ? "SUCCESS" : "PROCESSING",
                            captured.channelTradeNo(), null);
                }
                case "ROLLBACK_STOCK" -> {
                    if (orders.completeRefund(refund.orderNo(), refund.userId())) {
                        payments.advance(refund, "DONE", "SUCCESS", null, null);
                    } else {
                        payments.postponeRefund(refund, true);
                    }
                }
                default -> throw new IllegalStateException("Unexpected refund step");
            }
        } catch (RuntimeException ex) {
            log.warn("Refund action awaiting retry, refundNo={}, step={}", refundNo, refund.sagaStep());
            payments.postponeRefund(refund, "REFUND_PAY".equals(refund.sagaStep()) || "ROLLBACK_STOCK".equals(refund.sagaStep()));
        }
    }

    private RefundView view(Refund refund) {
        String action = switch (refund.status()) {
            case "SUCCESS" -> "VIEW_ORDER";
            case "FAILED" -> "CONTACT_SUPPORT";
            default -> "CHECK_REFUND";
        };
        return new RefundView(refund.refundNo(), refund.orderNo(), refund.amountCent(), refund.status(),
                refund.payRefundStatus(), PaymentApplicationService.offset(refund.updateTime()), action, refund.lastErrorCode());
    }

    public record RefundView(long refundNo, long orderNo, long amountCent, String refundStatus, String payRefundStatus,
                             OffsetDateTime updatedAt, String nextAction, String reasonCode) {}
}
