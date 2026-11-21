package com.starshow.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshow.api.facade.OrderPaymentFacade;
import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.payment.repository.MockChannelRepository;
import com.starshow.payment.repository.MockChannelRepository.ChannelTxn;
import com.starshow.payment.repository.PaymentRepository;
import com.starshow.payment.repository.ReconciliationRepository;
import com.starshow.payment.repository.ReconciliationRepository.Diff;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "starshow.payment.enabled", havingValue = "true")
public class ReconciliationService {
    private static final ZoneId BILL_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int PAGE = 100;
    private final ReconciliationRepository diffs;
    private final PaymentRepository payments;
    private final MockChannelRepository ledger;
    private final OrderPaymentFacade orders;
    private final ObjectMapper json;

    @Scheduled(cron = "${starshow.reconciliation.cron:0 15 2 * * *}", zone = "Asia/Shanghai")
    public void yesterday() {
        run(LocalDate.now(BILL_ZONE).minusDays(1), 0, 0);
    }

    public Run run(LocalDate billDate, long cursor, int maxPages) {
        validateDay(billDate);
        var from = billDate.atStartOfDay(BILL_ZONE).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        var until = billDate.plusDays(1).atStartOfDay(BILL_ZONE).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        int scanned = 0;
        int found = 0;
        int pages = 0;
        while (maxPages == 0 || pages < maxPages) {
            var candidates = Stream.concat(diffs.changed(from, until, cursor, PAGE).stream(),
                            orders.changedOrders(from, until, cursor, PAGE).stream())
                    .distinct().sorted().limit(PAGE).toList();
            for (long orderNo : candidates) {
                found += check(billDate, orderNo);
            }
            scanned += candidates.size();
            if (candidates.size() < PAGE) return result(billDate, scanned, found, null);
            cursor = candidates.getLast();
            pages++;
        }
        return result(billDate, scanned, found, cursor);
    }

    public List<Diff> list(LocalDate billDate, String status, long after, int limit) {
        validateDay(billDate);
        return diffs.page(billDate, status, Math.max(after, 0), Math.clamp(limit, 1, PAGE));
    }

    private int check(LocalDate date, long orderNo) {
        var order = orders.get(orderNo, 0);
        var payment = payments.byOrder(orderNo, false);
        var refund = payments.refundByOrder(orderNo);
        var channel = ledger.forOrder(orderNo).stream().filter(txn -> "SUCCESS".equals(txn.state())).toList();
        long channelPaid = sum(channel, "PAY");
        long channelRefunded = sum(channel, "REFUND");
        long localPaid = payment != null && payment.channelTradeNo() != null ? payment.amountCent() : 0;
        long localRefunded = refund != null && "SUCCESS".equals(refund.payRefundStatus()) ? refund.amountCent() : 0;
        String detail = payload(new Audit(order == null ? null : order.status(), order == null ? null : order.amountCent(),
                payment == null ? null : payment.status(), refund == null ? null : refund.payRefundStatus(),
                channelPaid, localPaid, channelRefunded, localRefunded, order == null ? 0 : order.ticketCount(),
                order == null ? 0 : order.validTicketCount(), order == null ? null : order.releaseStatus()));
        int issues = 0;
        if (channelPaid != localPaid) {
            String type = channelPaid > localPaid ? "LONG" : "SHORT";
            diffs.record(date, orderNo, type, "starshow-payment", "VERIFY_CHANNEL_PAYMENT", detail);
            issues++;
        }
        if (channelRefunded != localRefunded || (order != null && localPaid > 0 && localPaid != order.amountCent())) {
            diffs.record(date, orderNo, "AMOUNT_OR_FUND_MISMATCH", "starshow-payment", "VERIFY_CENT_AMOUNTS", detail);
            issues++;
        }
        if (order == null) {
            if (!channel.isEmpty() || localPaid > 0) {
                diffs.record(date, orderNo, "STATE_MISMATCH", "starshow-order", "VERIFY_MISSING_ORDER", detail);
                issues++;
            }
            return issues;
        }
        boolean paidOrder = List.of("PAID", "TICKETED", "REFUNDING", "REFUNDED").contains(order.status());
        boolean linkedPayment = payment != null && order.channelTradeNo() != null
                && order.channelTradeNo().equals(payment.channelTradeNo());
        boolean legitimateLateFunds = "CLOSED".equals(order.status()) && payment != null
                && "ABNORMAL_PAID".equals(payment.status()) && refund != null;
        if (paidOrder && !linkedPayment || !paidOrder && localPaid > 0 && !legitimateLateFunds
                || ("CLOSED".equals(order.status()) || "REFUNDED".equals(order.status())) && order.validTicketCount() > 0) {
            diffs.record(date, orderNo, "STATE_MISMATCH", "starshow-order", "VERIFY_ORDER_AND_TICKETS", detail);
            issues++;
        }
        if (("PAID".equals(order.status()) || "TICKETED".equals(order.status())) && order.validTicketCount() != order.qty()) {
            diffs.record(date, orderNo, "PAID_NO_TICKET", "starshow-order", "REPLAY_TICKET_ISSUE", detail);
            issues++;
            if ("PAID".equals(order.status()) && order.ticketCount() == 0 && linkedPayment && channelPaid == localPaid
                    && channelRefunded == 0) {
                orders.recoverIssue(orderNo);
                var confirmed = orders.get(orderNo, 0);
                if (confirmed != null && "TICKETED".equals(confirmed.status()) && confirmed.validTicketCount() == confirmed.qty()) {
                    diffs.fixed(date, orderNo, "PAID_NO_TICKET");
                }
            }
        }
        if (localRefunded > 0 && !"RELEASED".equals(order.releaseStatus()) || "MANUAL_CHECK".equals(order.releaseStatus())) {
            String type = localRefunded > 0 ? "REFUNDED_STOCK_NOT_RELEASED" : "STOCK_DRIFT";
            diffs.record(date, orderNo, type, "starshow-order", "VERIFY_STOCK_RELEASE_RECEIPT", detail);
            issues++;
        }
        return issues;
    }

    private long sum(List<ChannelTxn> channel, String kind) {
        return channel.stream().filter(entry -> kind.equals(entry.kind())).mapToLong(ChannelTxn::amountCent).sum();
    }

    private void validateDay(LocalDate billDate) {
        if (billDate == null || !billDate.isBefore(LocalDate.now(BILL_ZONE))) {
            throw new BizException(ErrorCode.INVALID_PARAM, "仅支持已结束的上海账日");
        }
    }

    private String payload(Audit audit) {
        try {
            return json.writeValueAsString(audit);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot encode reconciliation snapshot", ex);
        }
    }

    private Run result(LocalDate date, int scanned, int found, Long cursor) {
        return new Run(UUID.randomUUID().toString(), date, scanned, found, cursor);
    }

    public record Run(String runId, LocalDate billDate, int ordersChecked, int differences, Long nextCursor) {}
    private record Audit(String orderStatus, Long orderCent, String paymentStatus, String fundsRefundStatus,
                         long channelPaidCent, long localPaidCent, long channelRefundedCent, long localRefundedCent,
                         int tickets, int validTickets, String releaseStatus) {}
}
