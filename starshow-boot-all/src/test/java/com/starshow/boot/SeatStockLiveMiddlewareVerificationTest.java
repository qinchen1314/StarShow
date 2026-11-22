package com.starshow.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshow.api.event.OrderCreateEvent;
import com.starshow.common.auth.AuthHeaders;
import com.starshow.common.config.StarShowSecurityProperties;
import com.starshow.common.constant.RedisKeys;
import com.starshow.common.id.GeneSnowflakeIdGenerator;
import com.starshow.order.lua.OrderRedisScripts;
import com.starshow.order.service.OrderPersistenceService;
import com.starshow.order.service.OrderStockReleaseService;
import com.starshow.show.service.ShowPreheatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfSystemProperty(named = "starshow.live.enabled", matches = "true")
@SpringBootTest(properties = {
        "starshow.payment.enabled=true", "starshow.payment.mq-enabled=false",
        "starshow.payment.action-interval-ms=3600000", "starshow.payment.close-sweep-interval-ms=3600000",
        "starshow.show.task-recovery-enabled=false", "starshow.show.lifecycle-sync-enabled=false",
        "starshow.show.bloom-enabled=false", "starshow.reservation.recovery-enabled=false",
        "starshow.reservation.mq-enabled=false", "starshow.seckill.mq-enabled=false",
        "starshow.seckill.timeout-dispatch-enabled=false", "starshow.seckill.pending-reconcile-enabled=false",
        "knife4j.enable=false"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("monolith")
class SeatStockLiveMiddlewareVerificationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private GeneSnowflakeIdGenerator ids;
    @Autowired private OrderRedisScripts orderScripts;
    @Autowired private OrderPersistenceService orderPersistence;
    @Autowired private OrderStockReleaseService releases;
    @Autowired private ShowPreheatService preheat;
    @Autowired private StarShowSecurityProperties auth;
    @Autowired private MockMvc http;
    @Autowired private ObjectMapper json;
    @Value("${spring.data.redis.database:0}") private int redisDatabase;

    @BeforeEach
    void requireIsolation() {
        String schema = jdbc.queryForObject("SELECT DATABASE()", String.class);
        assertNotNull(schema);
        assertTrue(schema.matches("starshow_seatstock_[a-z0-9]+"), "Seat stock tests require a disposable MySQL schema");
        assertTrue(redisDatabase > 0, "Seat stock tests require a dedicated Redis database");
    }

    @Test
    void twoClientsSeeExactlyTheReleasedSeatAndNoExtraStockAfterReplay() throws Exception {
        Fixture show = fixture(false, false);
        Purchase first = buy(show, 0);
        Purchase second = buy(show, 1);
        Snapshot soldOut = snapshot(show);
        assertEquals("SOLD_OUT", soldOut.stock().path("entryState").asText());
        assertTier(soldOut, "SOLD_OUT", 0);
        assertEquals(0, selectableSeats(soldOut));
        assertEquals(0, dbStock(show));

        cancel(first);
        try (var clients = Executors.newFixedThreadPool(2)) {
            var releaseA = clients.submit(() -> releases.release(first.orderNo()));
            var releaseB = clients.submit(() -> releases.release(first.orderNo()));
            releaseA.get(10, TimeUnit.SECONDS);
            releaseB.get(10, TimeUnit.SECONDS);
            var buyerA = clients.submit(() -> snapshot(show));
            var buyerB = clients.submit(() -> snapshot(show));
            Snapshot afterA = buyerA.get(10, TimeUnit.SECONDS);
            Snapshot afterB = buyerB.get(10, TimeUnit.SECONDS);
            for (Snapshot after : List.of(afterA, afterB)) {
                assertTier(after, "OPEN", 1);
                assertEquals("OPEN", after.stock().path("entryState").asText());
                assertEquals(1, selectableSeats(after));
                assertTrue(after.map().path("seats").get(0).path("selectable").asBoolean());
                assertFalse(after.map().path("seats").get(1).path("selectable").asBoolean());
                assertTrue(asOf(after.map()).isAfter(asOf(soldOut.map())));
                assertTrue(asOf(after.stock()).isAfter(asOf(soldOut.stock())));
                assertEquals(soldOut.map().path("layoutVersion"), after.map().path("layoutVersion"));
            }
            save("release", Map.of("soldOut", soldOut, "clientA", afterA, "clientB", afterB));
        }
        cancel(first);
        releases.release(first.orderNo());
        assertEquals(1, dbStock(show));
        assertEquals("1", redis.opsForValue().get(RedisKeys.stock(show.showId(), show.tierId())));
        assertEquals(1, selectableSeats(snapshot(show)));
        assertTrue(orderScripts.hasStockReleaseReceipt(show.showId(), first.orderNo(), 1));
        assertFalse(orderScripts.hasStockReleaseReceipt(show.showId(), second.orderNo(), 1));
        System.out.println("ISSUE_014_RELEASE before=0 clientA=1 clientB=1 selectableIndex=0 replayStock=1");
    }

    @Test
    void afterTheSaleWindowReleaseRestoresAccountingButSeatsStayUnavailable() throws Exception {
        Fixture show = fixture(false, false);
        Purchase first = buy(show, 0);
        buy(show, 1);
        long endedAt = System.currentTimeMillis() - 1000;
        redis.opsForHash().put(RedisKeys.showReady(show.showId()), "saleEndEpochMs", String.valueOf(endedAt));
        Snapshot before = snapshot(show);
        cancel(first);
        releases.release(first.orderNo());
        Snapshot after = snapshot(show);
        for (Snapshot snapshot : List.of(before, after)) {
            assertEquals("ENDED", snapshot.stock().path("entryState").asText());
            assertEquals("ENDED", snapshot.map().path("stockState").asText());
            assertEquals(0, selectableSeats(snapshot));
        }
        assertEquals(1, dbStock(show));
        assertEquals("1", redis.opsForValue().get(RedisKeys.stock(show.showId(), show.tierId())));
        assertFalse(redis.opsForValue().getBit(RedisKeys.seatBitmap(show.showId(), "A"), 0));
        save("ended", Map.of("beforeRelease", before, "afterRelease", after));
        System.out.println("ISSUE_014_ENDED replayStock=1 selectable=0");
    }

    @Test
    void httpContractSeparatesPreparingZeroMissingKeysAndBadBitmap() throws Exception {
        Fixture preparing = fixture(true, false);
        Snapshot beforeOpening = snapshot(preparing);
        assertEquals("PREPARING", beforeOpening.stock().path("entryState").asText());
        assertEquals("PREPARING", beforeOpening.map().path("stockState").asText());
        assertEquals(0, selectableSeats(beforeOpening));

        Fixture show = fixture(false, true);
        buy(show, 0);
        buy(show, 1);
        redis.delete(RedisKeys.stock(show.showId(), show.otherTierId()));
        Snapshot partial = snapshot(show);
        assertEquals("STOCK_UNAVAILABLE", partial.stock().path("entryState").asText());
        assertTier(partial, "SOLD_OUT", 0);
        JsonNode missingTier = partial.stock().path("tiers").get(1);
        assertEquals("STOCK_UNAVAILABLE", missingTier.path("stockState").asText());
        assertTrue(missingTier.path("available").isNull());
        assertEquals("0", partial.map().path("seats").get(0).path("seatIndex").asText());
        assertEquals("L", partial.map().path("seats").get(0).path("segmentId").asText());
        http.perform(get("/api/v1/shows/{id}/zones/B/seats", show.showId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("A0203"));

        redis.delete(RedisKeys.seatBitmap(show.showId(), "A"));
        var bitmap = expectUnavailable("/api/v1/shows/" + show.showId() + "/zones/A/seats");
        redis.delete(RedisKeys.stock(show.showId(), show.tierId()));
        var stock = expectUnavailable("/api/v1/shows/" + show.showId() + "/stock");
        save("availability", Map.of("preparing", beforeOpening, "partial", partial,
                "missingStock", stock, "missingBitmap", bitmap));
    }

    private Fixture fixture(boolean future, boolean secondTier) {
        long showId = ids.nextId();
        long tierId = ids.nextId();
        long other = ids.nextId();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO t_show (id,title,city,venue,show_time,sale_start_time,sale_end_time,sale_status,seat_mode)
                VALUES (?, 'Seat stock fixture', 'Shanghai', 'Hall', ?, ?, ?, 'INIT', 'MANUAL_SEAT')
                """, showId, now.plusDays(1), now.plusMinutes(future ? 20 : -5), now.plusHours(1));
        jdbc.update("""
                INSERT INTO t_ticket_tier (id,show_id,tier_name,zone_id,price_cent,total_stock,available_stock)
                VALUES (?, ?, 'A', 'A', 6800, 2, 2)
                """, tierId, showId);
        for (int index : List.of(0, 1)) {
            jdbc.update("""
                    INSERT INTO t_seat (show_id,zone_id,tier_id,seat_index,row_no,col_no,segment_id,status)
                    VALUES (?, 'A', ?, ?, 1, ?, 'L', 0)
                    """, showId, tierId, index, index + 1);
        }
        if (secondTier) {
            jdbc.update("""
                    INSERT INTO t_ticket_tier (id,show_id,tier_name,zone_id,price_cent,total_stock,available_stock)
                    VALUES (?, ?, 'B', 'B', 8800, 1, 1)
                    """, other, showId);
        }
        preheat.preheat(showId, 1);
        return new Fixture(showId, tierId, other);
    }

    private Purchase buy(Fixture show, int index) {
        long user = ids.nextId();
        long orderNo = ids.nextOrderId(user);
        Instant now = Instant.now();
        // Seed through the production V4 Lua before entering the order persistence and cancellation path.
        var deduct = RedisScript.of(new ClassPathResource("lua/seckill_seat_deduct.lua"), List.class);
        var result = redis.execute(deduct, List.of(RedisKeys.stock(show.showId(), show.tierId()),
                        RedisKeys.boughtUsers(show.showId()), RedisKeys.deductLog(show.showId(), orderNo),
                        RedisKeys.deductPendingZset(show.showId()), RedisKeys.rollbackDone(show.showId(), orderNo),
                        RedisKeys.showReady(show.showId()), RedisKeys.boughtOwner(show.showId()),
                        RedisKeys.seatBitmap(show.showId(), "A")),
                String.valueOf(user), String.valueOf(orderNo), String.valueOf(show.tierId()), "A",
                String.valueOf(now.getEpochSecond()), "86400", "1", String.valueOf(now.toEpochMilli()), String.valueOf(index));
        assertNotNull(result);
        assertEquals(1L, ((Number) result.getFirst()).longValue());
        var outcome = orderPersistence.persistV4OrderFromEvent(OrderCreateEvent.builder()
                .showId(show.showId()).tierId(show.tierId()).orderNo(orderNo).userId(user).zoneId("A")
                .seatIndices(List.of(index)).qty(1).amountCent(6800L).deductEpochSec(now.getEpochSecond())
                .clientRequestId("seat-stock-" + index).requestFingerprint("seat-stock-fixture")
                .saleConfigVersion(1).layoutVersion("1").strategy("V4").build());
        assertEquals(OrderPersistenceService.V4ConsumeStatus.APPLIED, outcome.status());
        orderScripts.removeDeductPending(show.showId(), orderNo);
        return new Purchase(orderNo, user);
    }

    private void cancel(Purchase purchase) throws Exception {
        http.perform(post("/api/v1/orders/{orderNo}/cancel", purchase.orderNo())
                        .header(AuthHeaders.X_INTERNAL_GATEWAY_SECRET, auth.getInternalGatewaySecret())
                        .header(AuthHeaders.X_USER_ID, purchase.userId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.orderStatus").value("CLOSED"));
    }

    private Snapshot snapshot(Fixture show) throws Exception {
        var stock = http.perform(get("/api/v1/shows/{id}/stock", show.showId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.showId").value(Long.toString(show.showId())))
                .andExpect(jsonPath("$.data.saleConfigVersion").value(1)).andReturn().getResponse();
        var map = http.perform(get("/api/v1/shows/{id}/zones/A/seats", show.showId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.tierId").value(Long.toString(show.tierId())))
                .andExpect(jsonPath("$.data.layoutVersion").value(1)).andReturn().getResponse();
        return new Snapshot(json.readTree(stock.getContentAsByteArray()).path("data"),
                json.readTree(map.getContentAsByteArray()).path("data"));
    }

    private Map<String, Object> expectUnavailable(String path) throws Exception {
        var response = http.perform(get(path)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("B0201")).andExpect(header().exists("Retry-After"))
                .andReturn().getResponse();
        return Map.of("status", response.getStatus(), "retryAfter", response.getHeader("Retry-After"),
                "body", json.readTree(response.getContentAsByteArray()));
    }

    private static void assertTier(Snapshot snapshot, String state, int count) {
        JsonNode tier = snapshot.stock().path("tiers").get(0);
        assertEquals(state, tier.path("stockState").asText());
        assertEquals(count, tier.path("available").asInt(-1));
    }

    private static OffsetDateTime asOf(JsonNode snapshot) {
        return OffsetDateTime.parse(snapshot.path("asOf").asText());
    }

    private static long selectableSeats(Snapshot snapshot) {
        return StreamSupport.stream(snapshot.map().path("seats").spliterator(), false)
                .filter(seat -> seat.path("selectable").asBoolean()).count();
    }

    private int dbStock(Fixture show) {
        return jdbc.queryForObject("SELECT available_stock FROM t_ticket_tier WHERE id = ?", Integer.class, show.tierId());
    }

    private void save(String name, Map<String, ?> evidence) throws Exception {
        Path folder = Files.createDirectories(Path.of("target", "issue-014-evidence"));
        json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve(name + ".json").toFile(), evidence);
    }

    private record Fixture(long showId, long tierId, long otherTierId) {}
    private record Purchase(long orderNo, long userId) {}
    private record Snapshot(JsonNode stock, JsonNode map) {}
}
