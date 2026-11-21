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
public class MockChannelRepository {
    private final JdbcTemplate jdbc;

    public void register(String kind, long merchantNo, long orderNo, long amount, String state,
                         LocalDateTime expiry, String tradeNo, LocalDateTime confirmedAt) {
        jdbc.update("""
                INSERT INTO t_mock_channel_txn
                    (kind, merchant_biz_no, order_no, amount_cent, state, expire_time, channel_trade_no, confirmed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE merchant_biz_no = merchant_biz_no
                """, kind, merchantNo, orderNo, amount, state, expiry, tradeNo, confirmedAt);
    }

    public ChannelTxn get(String kind, long merchantNo, boolean lock) {
        return jdbc.query("SELECT * FROM t_mock_channel_txn WHERE kind = ? AND merchant_biz_no = ?" +
                (lock ? " FOR UPDATE" : ""), new DataClassRowMapper<>(ChannelTxn.class), kind, merchantNo)
                .stream().findFirst().orElse(null);
    }

    public void close(long merchantNo) {
        jdbc.update("""
                UPDATE t_mock_channel_txn SET state = 'CLOSED'
                WHERE kind = 'PAY' AND merchant_biz_no = ? AND state = 'INIT'
                """, merchantNo);
    }

    public void setDelivery(long merchantNo, String state, LocalDateTime when, String fault) {
        jdbc.update("""
                UPDATE t_mock_channel_txn SET delivery_at = ?, delivery_fault = ?
                WHERE kind = 'PAY' AND merchant_biz_no = ? AND state = ? AND delivery_fault IS NULL
                """, when, fault, merchantNo, state);
    }

    public void capture(long merchantNo, String expectedState, String tradeNo, LocalDateTime now) {
        jdbc.update("""
                UPDATE t_mock_channel_txn SET state = 'SUCCESS', channel_trade_no = ?, confirmed_at = ?
                WHERE kind = 'PAY' AND merchant_biz_no = ? AND state = ?
                """, tradeNo, now, merchantNo, expectedState);
    }

    public void nextDelivery(long merchantNo, LocalDateTime retryAt) {
        jdbc.update("""
                UPDATE t_mock_channel_txn SET delivery_at = ? WHERE kind = 'PAY' AND merchant_biz_no = ?
                AND delivery_at IS NOT NULL
                """, retryAt, merchantNo);
    }

    public List<ChannelTxn> due(LocalDateTime now, int limit) {
        return jdbc.query("""
                SELECT * FROM t_mock_channel_txn WHERE delivery_at <= ?
                ORDER BY delivery_at, merchant_biz_no LIMIT ?
                """, new DataClassRowMapper<>(ChannelTxn.class), now, limit);
    }

    public List<ChannelTxn> forOrder(long orderNo) {
        return jdbc.query("""
                SELECT * FROM t_mock_channel_txn
                WHERE order_no = ?
                """, new DataClassRowMapper<>(ChannelTxn.class), orderNo);
    }

    public record ChannelTxn(String kind, long merchantBizNo, long orderNo, long amountCent, String state,
                             String channelTradeNo, LocalDateTime expireTime, LocalDateTime confirmedAt,
                             String deliveryFault, LocalDateTime deliveryAt) {}
}
