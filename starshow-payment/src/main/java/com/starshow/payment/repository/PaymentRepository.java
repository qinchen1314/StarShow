package com.starshow.payment.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(name = "starshow.payment.enabled", havingValue = "true")
public class PaymentRepository {
    private final JdbcTemplate jdbc;

    public void register(long payNo, long orderNo, long userId, long amount, String status,
                         LocalDateTime expires, LocalDateTime orderExpires) {
        jdbc.update("""
                INSERT INTO t_payment_order (pay_no, order_no, user_id, amount_cent, status, expire_time, order_expire_time)
                VALUES (?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE order_no = order_no
                """, payNo, orderNo, userId, amount, status, expires, orderExpires);
    }

    public Payment byOrder(long orderNo, boolean lock) {
        return jdbc.query("SELECT * FROM t_payment_order WHERE order_no = ? AND deleted = 0" +
                (lock ? " FOR UPDATE" : ""), new DataClassRowMapper<>(Payment.class), orderNo)
                .stream().findFirst().orElse(null);
    }

    public Payment byNumber(long payNo, boolean lock) {
        return jdbc.query("SELECT * FROM t_payment_order WHERE pay_no = ? AND deleted = 0" +
                (lock ? " FOR UPDATE" : ""), new DataClassRowMapper<>(Payment.class), payNo)
                .stream().findFirst().orElse(null);
    }

    public void close(long orderNo) {
        jdbc.update("UPDATE t_payment_order SET status = 'CLOSED' WHERE order_no = ? AND status = 'INIT' AND deleted = 0", orderNo);
    }

    public void recordPaid(Payment payment, String tradeNo, LocalDateTime paidTime) {
        jdbc.update("""
                UPDATE t_payment_order SET status = 'SUCCESS', channel_trade_no = ?, paid_time = ?,
                    sync_pending = 1, next_retry_at = ?
                WHERE order_no = ? AND status = ? AND deleted = 0
                """, tradeNo, paidTime, LocalDateTime.now(), payment.orderNo(), payment.status());
    }

    public void finishSync(long orderNo, String status) {
        jdbc.update("""
                UPDATE t_payment_order SET status = ?, sync_pending = 0
                WHERE order_no = ? AND status = 'SUCCESS' AND sync_pending = 1 AND deleted = 0
                """, status, orderNo);
    }

    public void postponeSync(long payNo, LocalDateTime until) {
        jdbc.update("UPDATE t_payment_order SET next_retry_at = ? WHERE pay_no = ? AND sync_pending = 1",
                until, payNo);
    }

    public List<Payment> dueSync(LocalDateTime now, int limit) {
        return jdbc.query("""
                SELECT * FROM t_payment_order WHERE sync_pending = 1 AND next_retry_at <= ? AND deleted = 0
                ORDER BY next_retry_at, pay_no LIMIT ?
                """, new DataClassRowMapper<>(Payment.class), now, limit);
    }

    public void registerRefund(long refundNo, Payment payment, String reason, String type, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO t_refund_order
                (refund_no, order_no, user_id, amount_cent, reason, refund_type, next_retry_at)
                VALUES (?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE order_no = order_no
                """, refundNo, payment.orderNo(), payment.userId(), payment.amountCent(), reason, type, now);
    }

    public Refund refundByOrder(long orderNo) {
        return jdbc.query("SELECT * FROM t_refund_order WHERE order_no = ? AND deleted = 0",
                new DataClassRowMapper<>(Refund.class), orderNo).stream().findFirst().orElse(null);
    }

    public Refund refundByNumber(long refundNo) {
        return jdbc.query("SELECT * FROM t_refund_order WHERE refund_no = ? AND deleted = 0",
                new DataClassRowMapper<>(Refund.class), refundNo).stream().findFirst().orElse(null);
    }

    public List<Refund> dueRefunds(LocalDateTime now, int limit) {
        return jdbc.query("""
                SELECT * FROM t_refund_order WHERE status = 'PROCESSING' AND next_retry_at <= ? AND deleted = 0
                ORDER BY next_retry_at, refund_no LIMIT ?
                """, new DataClassRowMapper<>(Refund.class), now, limit);
    }

    public void advance(Refund refund, String step, String status, String trade, String error) {
        jdbc.update("""
                UPDATE t_refund_order SET saga_step = ?, status = ?, channel_refund_no = COALESCE(?, channel_refund_no),
                    pay_refund_status = CASE WHEN ? IS NULL THEN pay_refund_status ELSE 'SUCCESS' END,
                    last_error_code = ?, retry_count = 0, next_retry_at = ?
                WHERE refund_no = ? AND status = 'PROCESSING' AND saga_step = ? AND deleted = 0
                """, step, status, trade, trade, error, LocalDateTime.now(), refund.refundNo(), refund.sagaStep());
    }

    public void postponeRefund(Refund refund, boolean mayHaveMovedMoney) {
        LocalDateTime retryAt = LocalDateTime.now().plusSeconds(Math.min(300, 5L << Math.min(refund.retryCount(), 6)));
        jdbc.update("""
                UPDATE t_refund_order SET
                    status = CASE WHEN retry_count >= 19 THEN 'FAILED' ELSE status END,
                    pay_refund_status = CASE WHEN retry_count >= 19 AND pay_refund_status = 'PENDING' AND ?
                        THEN 'MANUAL_CHECK' ELSE pay_refund_status END,
                    last_error_code = CASE WHEN retry_count >= 19 THEN 'MANUAL_CHECK' ELSE last_error_code END,
                    retry_count = retry_count + 1, next_retry_at = ?
                WHERE refund_no = ? AND status = 'PROCESSING' AND saga_step = ? AND retry_count = ? AND deleted = 0
                """, mayHaveMovedMoney, retryAt, refund.refundNo(), refund.sagaStep(), refund.retryCount());
    }

    public record Payment(long payNo, long orderNo, long userId, long amountCent, String status,
                          String channelTradeNo, LocalDateTime expireTime, LocalDateTime orderExpireTime,
                          LocalDateTime paidTime, boolean syncPending) {}

    public record Refund(long refundNo, long orderNo, long userId, long amountCent, String sagaStep, String status,
                         String refundType, String payRefundStatus, String channelRefundNo, int retryCount,
                         LocalDateTime updateTime, String lastErrorCode) {}
}
