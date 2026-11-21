package com.starshow.order.mapper;

import com.starshow.order.domain.entity.OrderDO;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(name = "starshow.payment.enabled", havingValue = "true")
public class OrderFulfillmentRepository {
    private final JdbcTemplate jdbc;

    public OrderDO lock(long orderNo) {
        return jdbc.query("SELECT * FROM " + orderTable(orderNo) + " WHERE order_no = ? AND deleted = 0 FOR UPDATE",
                new BeanPropertyRowMapper<>(OrderDO.class), orderNo).stream().findFirst().orElse(null);
    }

    public int transition(long orderNo, String from, String to, String reason, String tradeNo, LocalDateTime paidAt) {
        return jdbc.update("UPDATE " + orderTable(orderNo) + """
                 SET status = ?, close_reason = COALESCE(?, close_reason),
                     pay_trade_no = COALESCE(?, pay_trade_no), paid_time = COALESCE(?, paid_time)
                 WHERE order_no = ? AND status = ? AND deleted = 0
                """, to, reason, tradeNo, paidAt, orderNo, from);
    }

    public int deactivate(OrderDO order) {
        return jdbc.update("UPDATE " + orderTable(order.getOrderNo()) + """
                 SET active_flag = order_no WHERE order_no = ? AND status = ? AND active_flag = 1 AND deleted = 0
                """, order.getOrderNo(), order.getStatus());
    }

    public void restoreStock(OrderDO order, List<Integer> seats) {
        if (jdbc.update("""
                UPDATE t_ticket_tier SET available_stock = available_stock + ?, version = version + 1
                WHERE id = ? AND show_id = ? AND deleted = 0 AND available_stock <= total_stock - ?
                """, order.getQty(), order.getTierId(), order.getShowId(), order.getQty()) != 1) {
            throw new IllegalStateException("Physical stock release requires verification");
        }
        if (!"V4".equals(order.getSeckillStrategy())) {
            for (int seat : seats) {
                if (jdbc.update("""
                        UPDATE t_seat SET status = 0 WHERE show_id = ? AND tier_id = ? AND zone_id = ?
                        AND seat_index = ? AND status = 1 AND deleted = 0
                        """, order.getShowId(), order.getTierId(), order.getZoneId(), seat) != 1) {
                    throw new IllegalStateException("Physical seat release requires verification");
                }
            }
        }
    }

    public String seatLabel(OrderDO order, int seat) {
        return jdbc.queryForObject("""
                SELECT CONCAT(zone_id, ' ', row_no, '排', col_no, '座') FROM t_seat
                WHERE show_id = ? AND tier_id = ? AND zone_id = ? AND seat_index = ? AND deleted = 0
                """, String.class, order.getShowId(), order.getTierId(), order.getZoneId(), seat);
    }

    public LocalDateTime showTime(long showId) {
        return jdbc.query("SELECT show_time FROM t_show WHERE id = ? AND deleted = 0",
                (rs, row) -> rs.getObject(1, LocalDateTime.class), showId).stream().findFirst().orElse(null);
    }

    public List<TicketRow> tickets(long orderNo) {
        return jdbc.query("SELECT ticket_no, seat_info, qr_code_token, status FROM " + ticketTable(orderNo) +
                " WHERE order_no = ? AND deleted = 0 ORDER BY ticket_item_no, ticket_no",
                new DataClassRowMapper<>(TicketRow.class), orderNo);
    }

    public void insertTicket(OrderDO order, long ticketNo, int item, String seat, String token) {
        jdbc.update("INSERT INTO " + ticketTable(order.getOrderNo()) + """
                 (ticket_no, ticket_item_no, order_no, user_id, show_id, tier_id, seat_info, qr_code_token)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, ticketNo, item, order.getOrderNo(), order.getUserId(), order.getShowId(), order.getTierId(), seat, token);
    }

    public int transitionTickets(long orderNo, String from, String to) {
        return jdbc.update("UPDATE " + ticketTable(orderNo) + """
                 SET status = ? WHERE order_no = ? AND status = ? AND deleted = 0
                """, to, orderNo, from);
    }

    public boolean redeem(long orderNo, long ticketNo) {
        return jdbc.update("UPDATE " + ticketTable(orderNo) + """
                 SET status = 'USED' WHERE order_no = ? AND ticket_no = ? AND status = 'ISSUED' AND deleted = 0
                """, orderNo, ticketNo) == 1;
    }

    public ReleaseTimes releaseTimes(long orderNo) {
        return jdbc.query("""
                SELECT db_released_at, attempted_at FROM t_order_action
                WHERE order_no = ? AND action_type = 'RELEASE_STOCK' AND deleted = 0
                """, new DataClassRowMapper<>(ReleaseTimes.class), orderNo).stream().findFirst().orElseThrow();
    }

    public void markDbReleased(long orderNo, LocalDateTime now) {
        jdbc.update("""
                UPDATE t_order_action SET db_released_at = ?
                WHERE order_no = ? AND action_type = 'RELEASE_STOCK' AND status = 'PENDING' AND db_released_at IS NULL
                """, now, orderNo);
    }

    public void markReleaseAttempt(long orderNo, LocalDateTime now) {
        jdbc.update("""
                UPDATE t_order_action SET attempted_at = COALESCE(attempted_at, ?)
                WHERE order_no = ? AND action_type = 'RELEASE_STOCK' AND status = 'PENDING'
                """, now, orderNo);
    }

    public List<Long> dueOrders(String shard, LocalDateTime now, long after, int limit) {
        return jdbc.queryForList("SELECT order_no FROM " + shard + """
                 WHERE status = 'WAIT_PAY' AND expire_time <= ? AND order_no > ? AND deleted = 0
                 ORDER BY order_no LIMIT ?
                """, Long.class, now, after, limit);
    }

    public List<Long> changedOrders(String shard, LocalDateTime from, LocalDateTime until, long after, int limit) {
        return jdbc.queryForList("SELECT order_no FROM " + shard + """
                 WHERE update_time >= ? AND update_time < ? AND order_no > ? AND deleted = 0
                 ORDER BY order_no LIMIT ?
                """, Long.class, from, until, after, limit);
    }

    private String orderTable(long orderNo) {
        return OrderShardTableResolver.tableForOrderNo(orderNo);
    }

    private String ticketTable(long orderNo) {
        return "t_ticket_" + com.starshow.common.id.GeneSnowflakeIdGenerator.resolveShardByOrderNo(
                orderNo, OrderShardTableResolver.SHARD_COUNT);
    }

    public record TicketRow(long ticketNo, String seatInfo, String qrCodeToken, String status) {}
    public record ReleaseTimes(LocalDateTime dbReleasedAt, LocalDateTime attemptedAt) {}
}
