package com.starshow.payment.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(name = "starshow.payment.enabled", havingValue = "true")
public class ReconciliationRepository {
    private final JdbcTemplate jdbc;

    public List<Long> changed(LocalDateTime from, LocalDateTime until, long cursor, int limit) {
        return jdbc.queryForList("""
                SELECT DISTINCT order_no FROM (
                  SELECT order_no FROM t_mock_channel_txn WHERE confirmed_at >= ? AND confirmed_at < ? AND order_no > ?
                  UNION ALL SELECT order_no FROM t_payment_order WHERE paid_time >= ? AND paid_time < ?
                    AND order_no > ? AND deleted = 0
                  UNION ALL SELECT order_no FROM t_refund_order WHERE update_time >= ? AND update_time < ?
                    AND order_no > ? AND deleted = 0
                ) sources ORDER BY order_no LIMIT ?
                """, Long.class, from, until, cursor, from, until, cursor, from, until, cursor, limit);
    }

    public void record(LocalDate billDate, long orderNo, String type, String owner, String action, String detail) {
        jdbc.update("""
                INSERT INTO t_reconciliation_diff
                    (bill_date, order_no, diff_type, handle_status, owner_module, suggested_action, detail_json)
                VALUES (?, ?, ?, 'OPEN', ?, ?, ?)
                ON DUPLICATE KEY UPDATE detail_json = ?, owner_module = ?, suggested_action = ?
                """, billDate, orderNo, type, owner, action, detail, detail, owner, action);
    }

    public void fixed(LocalDate billDate, long orderNo, String type) {
        jdbc.update("""
                UPDATE t_reconciliation_diff SET handle_status = 'AUTO_FIXED'
                WHERE bill_date = ? AND order_no = ? AND diff_type = ? AND handle_status = 'OPEN' AND deleted = 0
                """, billDate, orderNo, type);
    }

    public List<Diff> page(LocalDate date, String status, long after, int limit) {
        return jdbc.query("""
                SELECT id, bill_date, order_no, diff_type, handle_status, owner_module, suggested_action, detail_json
                FROM t_reconciliation_diff WHERE bill_date = ? AND id > ? AND deleted = 0
                AND (? IS NULL OR handle_status = ?) ORDER BY id LIMIT ?
                """, new DataClassRowMapper<>(Diff.class), date, after, status, status, limit);
    }

    public record Diff(long id, LocalDate billDate, long orderNo, String diffType, String handleStatus,
                       String ownerModule, String suggestedAction, String detailJson) {}
}
