package com.starshow.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshow.common.auth.AuthHeaders;
import com.starshow.common.config.StarShowSecurityProperties;
import com.starshow.common.constant.RedisKeys;
import com.starshow.common.crypto.HmacSigner;
import com.starshow.common.exception.BizException;
import com.starshow.common.id.GeneSnowflakeIdGenerator;
import com.starshow.order.lua.OrderRedisScripts;
import com.starshow.order.mapper.OrderShardTableResolver;
import com.starshow.order.mq.OrderFulfillmentScheduler;
import com.starshow.order.service.OrderClosingService;
import com.starshow.order.service.OrderFulfillmentService;
import com.starshow.order.service.OrderStockReleaseService;
import com.starshow.payment.config.PaymentProperties;
import com.starshow.payment.repository.MockChannelRepository;
import com.starshow.payment.repository.PaymentRepository;
import com.starshow.payment.service.MockPayChannel.Fault;
import com.starshow.payment.service.PaymentApplicationService;
import com.starshow.payment.service.PaymentSettlement.PaymentCallback;
import com.starshow.payment.service.ReconciliationService;
import com.starshow.payment.service.RefundSagaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfSystemProperty(named = "starshow.live.enabled", matches = "true")
@SpringBootTest(properties = {
        "starshow.payment.enabled=true", "starshow.payment.mock-enabled=true", "starshow.payment.fault-enabled=true",
        "starshow.payment.action-interval-ms=3600000", "starshow.payment.close-sweep-interval-ms=3600000",
        "starshow.show.task-recovery-enabled=false", "starshow.show.lifecycle-sync-enabled=false",
        "starshow.show.bloom-enabled=false", "starshow.reservation.recovery-enabled=false",
        "starshow.reservation.mq-enabled=false", "starshow.seckill.mq-enabled=false",
        "starshow.seckill.timeout-dispatch-enabled=false", "starshow.seckill.pending-reconcile-enabled=false",
        "knife4j.enable=false"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("monolith")
class Chain4LiveMiddlewareVerificationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired GeneSnowflakeIdGenerator ids;
    @Autowired OrderFulfillmentService orders;
    @Autowired OrderClosingService closing;
    @Autowired OrderStockReleaseService releases;
    @Autowired OrderFulfillmentScheduler orderJobs;
    @Autowired OrderRedisScripts scripts;
    @Autowired PaymentApplicationService payments;
    @Autowired RefundSagaService refunds;
    @Autowired MockChannelRepository ledger;
    @Autowired PaymentRepository paymentRows;
    @Autowired ReconciliationService reconciliation;
    @Autowired PaymentProperties properties;
    @Autowired StarShowSecurityProperties auth;
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Value("${spring.data.redis.database:0}") int redisDatabase;

    @BeforeEach
    void requireIsolation() {
        String schema = jdbc.queryForObject("SELECT DATABASE()", String.class);
        assertNotNull(schema);
        assertTrue(schema.startsWith("starshow_c4_"), "Chain 4 tests require a dedicated database");
        assertTrue(redisDatabase > 0, "Live tests require a dedicated Redis database");
    }

    @Test
    void duplicateCallbacksIssueExactlyOneTicketAndConcealItFromOtherUsers() throws Exception {
        Purchase order = prepare();
        String response = http.perform(post("/api/v1/payments")
                        .header(AuthHeaders.X_INTERNAL_GATEWAY_SECRET, auth.getInternalGatewaySecret())
                        .header(AuthHeaders.X_USER_ID, order.userId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"" + order.orderNo() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.amountCent").value("6800"))
                .andReturn().getResponse().getContentAsString();
        long payNo = json.readTree(response).path("data").path("payNo").asLong();
        assertEquals(payNo, payments.create(order.orderNo(), order.userId()).payNo());
        payments.mockPay(payNo, order.userId(), Fault.DUPLICATE_3X);
        callback(payNo, 2);
        // The suite turns off periodic recovery. Drive the same scheduler so its durable
        // retry runs if the broker has not assigned the ticket queue to this consumer yet.
        await(() -> {
            if ("TICKETED".equals(orders.find(order.orderNo()).getStatus())) return true;
            orderJobs.dueActions();
            return false;
        });
        var view = orders.ticketView(order.orderNo(), order.userId());
        assertEquals(1, view.tickets().size());
        assertNotNull(view.tickets().getFirst().qrCodeToken());
        assertThrows(BizException.class, () -> orders.ticketView(order.orderNo(), order.userId() + 1));
        assertThrows(BizException.class, () -> payments.get(payNo, order.userId() + 1));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_mq_consume_log WHERE msg_key = ?",
                Integer.class, "ticket-issue:" + order.orderNo()));
    }

    @Test
    void droppedCallbackIsRecoveredByActualTimeoutCheck() {
        Purchase order = prepare();
        long payNo = payments.create(order.orderNo(), order.userId()).payNo();
        payments.mockPay(payNo, order.userId(), Fault.DROP_CALLBACK);
        assertEquals("INIT", paymentRows.byNumber(payNo, false).status());
        assertEquals("WAIT_PAY", orders.find(order.orderNo()).getStatus());
        jdbc.update("UPDATE " + order.table() + " SET expire_time = ? WHERE order_no = ?",
                LocalDateTime.now().minusSeconds(1), order.orderNo());
        closing.timeout(order.orderNo());
        assertEquals("PAID", orders.find(order.orderNo()).getStatus());
        assertEquals("ISSUING", orders.ticketView(order.orderNo(), order.userId()).issueStatus());
        orders.recoverIssue(order.orderNo());
        assertEquals("TICKETED", orders.find(order.orderNo()).getStatus());
        assertEquals("0", redis.opsForValue().get(RedisKeys.stock(order.showId(), order.tierId())));
    }

    @Test
    void lateCaptureRefundsExactlyOnceWithoutReopeningOrderOrRestoringTwice() throws Exception {
        Purchase order = prepare();
        long payNo = payments.create(order.orderNo(), order.userId()).payNo();
        payments.mockPay(payNo, order.userId(), Fault.RACE_WITH_CLOSE);
        assertEquals("CLOSED", closing.cancel(order.orderNo(), order.userId()).orderStatus());
        releases.release(order.orderNo());
        payments.deliver(ledger.get("PAY", payNo, false));
        callback(payNo, 2);
        var payment = payments.get(payNo, order.userId());
        assertEquals("ABNORMAL_PAID", payment.paymentStatus());
        assertEquals("VIEW_REFUND", payment.nextAction());
        for (int i = 0; i < 5; i++) refunds.advance(payment.abnormalRefundNo());
        var refund = refunds.get(payment.abnormalRefundNo(), order.userId());
        assertEquals("SUCCESS", refund.payRefundStatus());
        assertEquals("SUCCESS", refund.refundStatus());
        assertEquals("CLOSED", orders.find(order.orderNo()).getStatus());
        assertEquals(0, orders.get(order.orderNo(), order.userId()).ticketCount());
        assertEquals(1, ledger.forOrder(order.orderNo()).stream().filter(txn -> "REFUND".equals(txn.kind())).count());
        releases.release(order.orderNo());
        assertStockReleased(order);
    }

    @Test
    void normalRefundSurvivesRepeatedStepsAndAReleasedButUnrecordedRedisResult() {
        Purchase order = prepare();
        long payNo = payments.create(order.orderNo(), order.userId()).payNo();
        payments.mockPay(payNo, order.userId(), Fault.NONE);
        orders.recoverIssue(order.orderNo());
        long refundNo = refunds.request(order.orderNo(), order.userId(), "演练").refundNo();
        assertEquals(refundNo, refunds.request(order.orderNo(), order.userId(), "重复请求").refundNo());
        refunds.advance(refundNo);
        refunds.advance(refundNo);
        assertNull(orders.ticketView(order.orderNo(), order.userId()).tickets().getFirst().qrCodeToken());
        refunds.advance(refundNo);
        assertEquals("SUCCESS", refunds.get(refundNo, order.userId()).payRefundStatus());
        refunds.advance(refundNo);
        assertEquals(order.orderNo(), orders.find(order.orderNo()).getActiveFlag());
        assertEquals(1, scripts.executeRollback(order.showId(), order.tierId(), null, order.userId(), 1, order.orderNo(), 86400, null));
        releases.release(order.orderNo());
        for (int i = 0; i < 2; i++) refunds.advance(refundNo);
        assertEquals("REFUNDED", orders.find(order.orderNo()).getStatus());
        assertEquals("SUCCESS", refunds.get(refundNo, order.userId()).refundStatus());
        assertStockReleased(order);
        assertNull(scripts.readBoughtOwnerOrderNo(order.showId(), order.userId()));
    }

    @Test
    void usedTicketCannotBeRefundedAndCloseMarkerPreventsAFirstLatePayment() {
        Purchase order = prepare();
        long payNo = payments.create(order.orderNo(), order.userId()).payNo();
        payments.mockPay(payNo, order.userId(), Fault.NONE);
        orders.recoverIssue(order.orderNo());
        var ticket = orders.ticketView(order.orderNo(), order.userId()).tickets().getFirst();
        assertTrue(orders.redeem(order.orderNo(), ticket.ticketNo(), ticket.qrCodeToken()));
        assertFalse(orders.redeem(order.orderNo(), ticket.ticketNo(), ticket.qrCodeToken()));
        assertThrows(BizException.class, () -> refunds.request(order.orderNo(), order.userId(), ""));
        assertNull(paymentRows.refundByOrder(order.orderNo()));

        Purchase unpaid = prepare();
        assertEquals("CLOSED", closing.cancel(unpaid.orderNo(), unpaid.userId()).orderStatus());
        assertThrows(BizException.class, () -> payments.create(unpaid.orderNo(), unpaid.userId()));
        var marker = paymentRows.byOrder(unpaid.orderNo(), false);
        assertEquals("CLOSED", ledger.get("PAY", marker.payNo(), false).state());
        assertThrows(BizException.class, () -> payments.mockPay(marker.payNo(), unpaid.userId(), Fault.NONE));
    }

    @Test
    void reconciliationFindsInjectedCentMismatchAndDoesNotDuplicateTheDiff() {
        Purchase order = prepare();
        long payNo = payments.create(order.orderNo(), order.userId()).payNo();
        payments.mockPay(payNo, order.userId(), Fault.NONE);
        orders.recoverIssue(order.orderNo());
        LocalDate bill = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1);
        LocalDateTime bookingTime = bill.atTime(12, 0).atZone(ZoneId.of("Asia/Shanghai"))
                .withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        jdbc.update("UPDATE t_mock_channel_txn SET amount_cent = amount_cent + 1, confirmed_at = ? WHERE kind='PAY' AND merchant_biz_no = ?",
                bookingTime, payNo);
        assertTrue(reconciliation.run(bill, 0, 2).differences() >= 1);
        reconciliation.run(bill, 0, 2);
        var diffs = reconciliation.list(bill, "OPEN", 0, 100).stream().filter(diff -> diff.orderNo() == order.orderNo()).toList();
        assertEquals(1, diffs.size());
        assertEquals("LONG", diffs.getFirst().diffType());
        assertEquals("starshow-payment", diffs.getFirst().ownerModule());
    }

    @Test
    void tombstoneAndWrongOwnerNeverMutateAnyStock() {
        Purchase order = prepare();
        redis.opsForHash().put(RedisKeys.boughtOwner(order.showId()), Long.toString(order.userId()), "1234");
        assertEquals(-1, scripts.executeRollback(order.showId(), order.tierId(), null, order.userId(), 1, order.orderNo(), 86400, null));
        assertEquals("0", redis.opsForValue().get(RedisKeys.stock(order.showId(), order.tierId())));
        redis.opsForHash().put(RedisKeys.boughtOwner(order.showId()), Long.toString(order.userId()), Long.toString(order.orderNo()));
        redis.opsForValue().set(RedisKeys.rollbackDone(order.showId(), order.orderNo()), "1");
        assertEquals(-1, scripts.executeRollback(order.showId(), order.tierId(), null, order.userId(), 1, order.orderNo(), 86400, null));
        assertFalse(scripts.hasStockReleaseReceipt(order.showId(), order.orderNo()));
        assertEquals("0", redis.opsForValue().get(RedisKeys.stock(order.showId(), order.tierId())));
    }

    private void callback(long payNo, int times) throws Exception {
        var entry = ledger.get("PAY", payNo, false);
        byte[] body = json.writeValueAsBytes(new PaymentCallback(payNo, entry.orderNo(), entry.channelTradeNo(),
                entry.amountCent(), entry.state(), entry.confirmedAt()));
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String signature = HmacSigner.sign(properties.getCallbackSecret(), timestamp + "\n", body);
        for (int i = 0; i < times; i++) {
            http.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON).content(body)
                            .header("X-Mock-Timestamp", timestamp).header("X-Mock-Signature", signature))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("00000"));
        }
    }

    private void assertStockReleased(Purchase order) {
        assertEquals("1", redis.opsForValue().get(RedisKeys.stock(order.showId(), order.tierId())));
        assertEquals(1, jdbc.queryForObject("SELECT available_stock FROM t_ticket_tier WHERE id = ?", Integer.class, order.tierId()));
        assertEquals("RELEASED", orders.get(order.orderNo(), order.userId()).releaseStatus());
    }

    private Purchase prepare() {
        long showId = ids.nextId();
        long tierId = ids.nextId();
        long userId = ids.nextId() + 7;
        long orderNo = ids.nextOrderId(userId);
        String table = OrderShardTableResolver.tableForOrderNo(orderNo);
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO t_show (id,title,city,venue,show_time,sale_start_time,sale_end_time,sale_status)
                VALUES (?, 'Phase 4', '上海', '演练', ?, ?, ?, 'ON_SALE')
                """, showId, now.plusDays(1), now.minusMinutes(5), now.plusHours(1));
        jdbc.update("""
                INSERT INTO t_ticket_tier (id,show_id,tier_name,price_cent,total_stock,available_stock)
                VALUES (?, ?, '普通票', 6800, 1, 0)
                """, tierId, showId);
        jdbc.update("INSERT INTO " + table + """
                 (order_no,user_id,show_id,tier_id,amount_cent,status,expire_time)
                 VALUES (?, ?, ?, ?, 6800, 'WAIT_PAY', ?)
                """, orderNo, userId, showId, tierId, now.plusMinutes(15));
        redis.opsForValue().set(RedisKeys.stock(showId, tierId), "0");
        redis.opsForSet().add(RedisKeys.boughtUsers(showId), Long.toString(userId));
        redis.opsForHash().put(RedisKeys.boughtOwner(showId), Long.toString(userId), Long.toString(orderNo));
        return new Purchase(orderNo, userId, showId, tierId, table);
    }

    private void await(BooleanSupplier condition) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < until) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(250);
        }
        fail("Ticket still unavailable after message delivery and scheduled recovery");
    }

    private record Purchase(long orderNo, long userId, long showId, long tierId, String table) {}
}
