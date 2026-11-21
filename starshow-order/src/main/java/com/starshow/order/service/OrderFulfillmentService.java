package com.starshow.order.service;

import com.starshow.api.constant.MqTopics;
import com.starshow.api.facade.OrderPaymentFacade;
import com.starshow.common.crypto.HmacSigner;
import com.starshow.common.exception.BizException;
import com.starshow.common.id.GeneSnowflakeIdGenerator;
import com.starshow.common.result.ErrorCode;
import com.starshow.common.security.SecuritySecretValidator;
import com.starshow.common.statemachine.OrderStateMachine;
import com.starshow.common.statemachine.OrderStatus;
import com.starshow.order.domain.entity.MqConsumeLogDO;
import com.starshow.order.domain.entity.OrderActionDO;
import com.starshow.order.domain.entity.OrderDO;
import com.starshow.order.mapper.MqConsumeLogMapper;
import com.starshow.order.mapper.OrderActionMapper;
import com.starshow.order.mapper.OrderFulfillmentRepository;
import com.starshow.order.mapper.OrderFulfillmentRepository.TicketRow;
import com.starshow.order.mapper.OrderShardMapper;
import com.starshow.order.mapper.OrderShardTableResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@Service
@ConditionalOnProperty(name = "starshow.payment.enabled", havingValue = "true")
public class OrderFulfillmentService implements OrderPaymentFacade {
    public static final String ISSUE_TICKET = "ISSUE_TICKET";
    private static final String RELEASE_STOCK = OrderActionDO.TYPE_RELEASE_STOCK;

    private final OrderShardMapper orders;
    private final OrderFulfillmentRepository fulfillment;
    private final OrderActionMapper actions;
    private final MqConsumeLogMapper consumeLog;
    private final GeneSnowflakeIdGenerator ids;
    private final String ticketSecret;

    public OrderFulfillmentService(OrderShardMapper orders, OrderFulfillmentRepository fulfillment,
                                   OrderActionMapper actions, MqConsumeLogMapper consumeLog,
                                   GeneSnowflakeIdGenerator ids,
                                   @Value("${starshow.payment.ticket-secret:}") String ticketSecret) {
        SecuritySecretValidator.validateSigningSecret(ticketSecret, "starshow.payment.ticket-secret");
        this.orders = orders;
        this.fulfillment = fulfillment;
        this.actions = actions;
        this.consumeLog = consumeLog;
        this.ids = ids;
        this.ticketSecret = ticketSecret;
    }

    @Override
    @Transactional(readOnly = true)
    public OrderSnapshot get(long orderNo, long userId) {
        OrderDO order = find(orderNo);
        if (order == null || (userId != 0 && order.getUserId() != userId)) return null;
        List<TicketRow> tickets = fulfillment.tickets(orderNo);
        OrderActionDO release = actions.selectByOrderAndType(orderNo, RELEASE_STOCK);
        int validTickets = (int) tickets.stream().filter(ticket ->
                "ISSUED".equals(ticket.status()) || "USED".equals(ticket.status())).count();
        return new OrderSnapshot(orderNo, order.getUserId(), order.getAmountCent(), order.getStatus(),
                order.getExpireTime(), order.getPayTradeNo(), order.getQty(), tickets.size(),
                validTickets, release == null ? null : release.getStatus());
    }

    @Override
    @Transactional
    public Settlement applyPaid(PaidOrder paid) {
        OrderDO order = fulfillment.lock(paid.orderNo());
        if (order == null || order.getUserId() != paid.userId() || order.getAmountCent() != paid.amountCent()
                || paid.channelTradeNo() == null || paid.paidTime() == null) return Settlement.MISMATCH;
        if ("CLOSED".equals(order.getStatus())) return Settlement.CLOSED;
        if (!"WAIT_PAY".equals(order.getStatus())) {
            return Objects.equals(order.getPayTradeNo(), paid.channelTradeNo()) ? Settlement.APPLIED : Settlement.MISMATCH;
        }
        transition(order, OrderStatus.PAID, null, paid.channelTradeNo(), paid.paidTime());
        enqueue(order, ISSUE_TICKET);
        return Settlement.APPLIED;
    }

    @Transactional
    public void close(long orderNo, String reason) {
        OrderDO order = fulfillment.lock(orderNo);
        if (order == null || !"WAIT_PAY".equals(order.getStatus())) return;
        transition(order, OrderStatus.CLOSED, reason, null, null);
        order.setStatus("CLOSED");
        requireOne(fulfillment.deactivate(order));
        enqueue(order, RELEASE_STOCK);
    }

    @Transactional
    @Override
    public void recoverIssue(long orderNo) {
        OrderDO order = fulfillment.lock(orderNo);
        if (order == null) return;
        if ("PAID".equals(order.getStatus())) {
            List<Integer> seats = seats(order);
            for (int item = 0; item < order.getQty(); item++) {
                long ticketNo = ids.nextId();
                String seat = seats.isEmpty() ? "自动配座" : fulfillment.seatLabel(order, seats.get(item));
                fulfillment.insertTicket(order, ticketNo, item + 1, seat, ticketCode(orderNo, ticketNo));
            }
            transition(order, OrderStatus.TICKETED, null, null, null);
            MqConsumeLogDO receipt = new MqConsumeLogDO();
            receipt.setMsgKey("ticket-issue:" + orderNo);
            receipt.setTopic(MqTopics.TICKET_ISSUE);
            receipt.setUserId(order.getUserId());
            receipt.setBizNo(Long.toString(orderNo));
            receipt.setConsumeStatus(1);
            receipt.setCreateTime(LocalDateTime.now());
            consumeLog.insert(receipt);
        }
        finishIssue(orderNo);
    }

    @Override
    @Transactional
    public RefundLock lockRefund(long orderNo, long userId, boolean preview) {
        OrderDO order = fulfillment.lock(orderNo);
        if (order == null || order.getUserId() != userId) return RefundLock.INELIGIBLE;
        if ("REFUNDING".equals(order.getStatus())) return RefundLock.ACCEPTED;
        if (!List.of("PAID", "TICKETED").contains(order.getStatus())) return RefundLock.INELIGIBLE;
        LocalDateTime showTime = fulfillment.showTime(order.getShowId());
        if (showTime == null || !LocalDateTime.now().isBefore(showTime)) return RefundLock.INELIGIBLE;
        List<TicketRow> tickets = fulfillment.tickets(orderNo);
        if (tickets.stream().anyMatch(ticket -> !"ISSUED".equals(ticket.status()))) return RefundLock.INELIGIBLE;
        if (!preview) {
            transition(order, OrderStatus.REFUNDING, null, null, null);
            if (fulfillment.transitionTickets(orderNo, "ISSUED", "LOCKED") != tickets.size()) {
                throw new IllegalStateException("Ticket state changed during refund");
            }
            finishIssue(orderNo);
        }
        return RefundLock.ACCEPTED;
    }

    @Override
    @Transactional
    public boolean completeRefund(long orderNo, long userId) {
        OrderDO order = fulfillment.lock(orderNo);
        if (order == null || order.getUserId() != userId) return false;
        if ("REFUNDED".equals(order.getStatus())) return true;
        if (!"REFUNDING".equals(order.getStatus())) throw new BizException(ErrorCode.ORDER_STATE_ILLEGAL);
        if (order.getActiveFlag() == 1L) {
            fulfillment.transitionTickets(orderNo, "LOCKED", "REFUNDED");
            requireOne(fulfillment.deactivate(order));
            enqueue(order, RELEASE_STOCK);
            fulfillment.restoreStock(order, seats(order));
            fulfillment.markDbReleased(orderNo, LocalDateTime.now());
        }
        return false;
    }

    @Transactional
    public OrderDO prepareRelease(long orderNo) {
        OrderDO order = fulfillment.lock(orderNo);
        if (order == null || !List.of("CLOSED", "REFUNDING").contains(order.getStatus())) return null;
        OrderActionDO action = actions.selectByOrderAndTypeForUpdate(orderNo, RELEASE_STOCK);
        if (action == null || !"PENDING".equals(action.getStatus())) return null;
        if (fulfillment.releaseTimes(orderNo).dbReleasedAt() == null) {
            fulfillment.restoreStock(order, seats(order));
            fulfillment.markDbReleased(orderNo, LocalDateTime.now());
        }
        return order;
    }

    @Transactional
    public void finishRelease(long orderNo) {
        OrderDO order = fulfillment.lock(orderNo);
        if (order == null) return;
        if (actions.casUpdateStatus(orderNo, RELEASE_STOCK, "PENDING", "RELEASED", null, null, LocalDateTime.now()) == 1
                && "REFUNDING".equals(order.getStatus())) {
            transition(order, OrderStatus.REFUNDED, null, null, null);
        }
    }

    @Transactional
    public boolean redeem(long orderNo, long ticketNo, String code) {
        if (!HmacSigner.matches(ticketCode(orderNo, ticketNo), code)) return false;
        OrderDO order = fulfillment.lock(orderNo);
        return order != null && "TICKETED".equals(order.getStatus()) && fulfillment.redeem(orderNo, ticketNo);
    }

    @Transactional(readOnly = true)
    public TicketsView ticketView(long orderNo, long userId) {
        OrderDO order = find(orderNo);
        if (order == null || order.getUserId() != userId) throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        String issueStatus = switch (order.getStatus()) {
            case "PAID" -> "ISSUING";
            case "TICKETED" -> "ISSUED";
            default -> order.getStatus();
        };
        List<TicketView> ticketViews = fulfillment.tickets(orderNo).stream()
                .map(t -> new TicketView(t.ticketNo(), t.seatInfo(), t.status(),
                        "ISSUED".equals(t.status()) && "TICKETED".equals(order.getStatus()) ? t.qrCodeToken() : null))
                .toList();
        return new TicketsView(orderNo, issueStatus, order.getTierId(), order.getZoneId(), seats(order),
                "ISSUING".equals(issueStatus) ? 1500 : null, ticketViews);
    }

    @Override
    public List<Long> changedOrders(LocalDateTime from, LocalDateTime until, long after, int limit) {
        return OrderShardTableResolver.allShardTables().stream()
                .flatMap(shard -> fulfillment.changedOrders(shard, from, until, after, limit).stream())
                .sorted().limit(limit).toList();
    }

    public OrderDO find(long orderNo) {
        return orders.selectByOrderNo(OrderShardTableResolver.tableForOrderNo(orderNo), orderNo);
    }

    public static List<Integer> seats(OrderDO order) {
        String csv = order.getSeatIndices();
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).map(Integer::valueOf).toList();
    }

    private String ticketCode(long orderNo, long ticketNo) {
        return HmacSigner.sign(ticketSecret, "starshow-ticket\n", (orderNo + ":" + ticketNo).getBytes(StandardCharsets.UTF_8));
    }

    private void enqueue(OrderDO order, String action) {
        LocalDateTime now = LocalDateTime.now();
        requireOne(actions.insertIgnore(OrderActionDO.builder()
                .actionId(ids.nextId()).orderNo(order.getOrderNo()).showId(order.getShowId()).userId(order.getUserId())
                .actionType(action).status("PENDING").attempt(0).nextRetryAt(now).createTime(now).updateTime(now).build()));
    }

    private void finishIssue(long orderNo) {
        actions.casUpdateStatus(orderNo, ISSUE_TICKET, "PENDING", "DONE", null, null, LocalDateTime.now());
    }

    private void transition(OrderDO order, OrderStatus to, String reason, String tradeNo, LocalDateTime paidAt) {
        OrderStateMachine.assertCanTransit(OrderStatus.valueOf(order.getStatus()), to);
        requireOne(fulfillment.transition(order.getOrderNo(), order.getStatus(), to.name(), reason, tradeNo, paidAt));
    }

    private void requireOne(int affectedRows) {
        if (affectedRows != 1) throw new IllegalStateException("Unexpected order write count: " + affectedRows);
    }

    public record TicketView(long ticketNo, String seatInfo, String status, String qrCodeToken) {}
    public record TicketsView(long orderNo, String issueStatus, long tierId, String zoneId,
                              List<Integer> seatPreview, Integer retryAfterMs, List<TicketView> tickets) {}
}
