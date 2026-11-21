package com.starshow.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshow.common.auth.JwtTokenProvider;
import com.starshow.common.constant.RedisKeys;
import com.starshow.order.config.SeckillProperties;
import com.starshow.order.mapper.OrderShardTableResolver;
import com.starshow.show.service.ShowPreheatService;
import com.starshow.show.util.ShowTimeUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "starshow.live.enabled", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "server.tomcat.threads.max=72", "server.tomcat.threads.min-spare=4",
        "spring.datasource.hikari.maximum-pool-size=20", "spring.datasource.hikari.minimum-idle=4",
        "starshow.seckill.token-ttl-seconds=600", "starshow.payment.enabled=false",
        "starshow.seckill.mq-enabled=true", "starshow.seckill.timeout-dispatch-enabled=false",
        "starshow.seckill.pending-reconcile-enabled=false", "starshow.show.task-recovery-enabled=false",
        "starshow.show.lifecycle-sync-enabled=false", "starshow.show.bloom-enabled=false",
        "starshow.reservation.mq-enabled=false", "starshow.reservation.recovery-enabled=false",
        "logging.level.root=WARN", "knife4j.enable=false"
})
@ActiveProfiles("monolith")
class Chain5LiveMiddlewareVerificationTest {
    private static final int USERS = 5001;
    private static final int CONCURRENCY = 64;
    private static final int TIER_COUNT = 5;
    private static final int REPLAYS = 25;

    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired ShowPreheatService preheat;
    @Autowired SeckillProperties config;
    @Autowired JwtTokenProvider jwt;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    @Value("${spring.data.redis.database:0}") int redisDb;

    @Test
    void sameRealJwtPoolAndInventoryUnderAllFourStrategies() throws Exception {
        String schema = jdbc.queryForObject("SELECT DATABASE()", String.class);
        assertNotNull(schema);
        assertTrue(schema.matches("starshow_c5_[0-9a-f]{12}") && redisDb > 0);
        assertEquals("Asia/Shanghai", java.time.ZoneId.systemDefault().getId());
        var identities = new ArrayList<Object[]>(USERS);
        var tokens = new String[USERS];
        for (int i = 0; i < USERS; i++) {
            long userId = 8_000_000L + i;
            identities.add(new Object[]{userId, "fixture", "fixture", "fixture",
                    String.format(Locale.ROOT, "%064x", userId)});
            tokens[i] = jwt.generateToken(userId).token();
            assertEquals(userId, jwt.parseToken(tokens[i]).userId());
        }
        assertEquals(USERS, new HashSet<>(Arrays.asList(tokens)).size());
        jdbc.batchUpdate("""
                INSERT INTO t_user_identity (user_id,real_name_cipher,id_card_cipher,id_card_masked,id_card_hmac)
                VALUES (?, ?, ?, ?, ?)
                """, identities);

        System.out.printf("BENCH_ENV java=%s os=%s activeCpus=%d maxHeapMiB=%d users=%d concurrency=%d%n",
                System.getProperty("java.version"), System.getProperty("os.name"),
                Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory() / 1048576, USERS, CONCURRENCY);
        try (var http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(4)).build()) {
            int round = 0;
            for (int perTier : new int[]{400, 2000}) {
                for (String strategy : List.of("V1", "V2", "V3", "V4")) {
                    benchmark(http, tokens, strategy, 950_000L + 10L * ++round, perTier);
                }
            }
        }
    }

    private void benchmark(HttpClient http, String[] jwtTokens, String strategy, long showId, int perTier) throws Exception {
        config.setStrategy(strategy);
        prepareShow(showId, perTier);
        String[] bodies = new String[USERS];
        runWorkers(i -> {
            Reply token = post(http, "token", jwtTokens[i], """
                    {"showId":"%d","saleConfigVersion":1}
                    """.formatted(showId));
            assertEquals("00000", token.code());
            String issued = token.data().path("seckillToken").asText();
            assertFalse(issued.isBlank());
            bodies[i] = """
                    {"showId":"%d","tierId":"%d","qty":1,"saleConfigVersion":1,"expectedUnitPriceCent":"68000",
                     "clientRequestId":"bench-order-%05d","seckillToken":"%s"}
                    """.formatted(showId, tier(showId, i % TIER_COUNT), i, issued);
        });
        long lockCount = innodb("Innodb_row_lock_waits");
        long lockMs = innodb("Innodb_row_lock_time");
        Reply[] replies = new Reply[USERS];
        long[] latency = new long[USERS];
        long oldestPendingMs;
        long apiNs;
        long totalNs;
        int accepted;
        var start = System.nanoTime();
        AtomicLong oldest = new AtomicLong();
        try (var monitor = Executors.newSingleThreadScheduledExecutor()) {
            var ageSample = monitor.scheduleWithFixedDelay(() -> sampleOldest(showId, oldest), 0, 100, TimeUnit.MILLISECONDS);
            runWorkers(i -> {
                long begin = System.nanoTime();
                replies[i] = post(http, "orders", jwtTokens[i], bodies[i]);
                latency[i] = System.nanoTime() - begin;
            });
            apiNs = System.nanoTime() - start;
            accepted = (int) Arrays.stream(replies).filter(Reply::accepted).count();
            assertTrue(accepted > 0);
            long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
            while (dbTotals(showId).tickets() < accepted || pending(showId) > 0) {
                assertTrue(System.nanoTime() < deadline, "V4 drain timed out for " + showId);
                if (ageSample.isDone()) ageSample.get();
                Thread.sleep(50);
            }
            totalNs = System.nanoTime() - start;
            sampleOldest(showId, oldest);
            oldestPendingMs = oldest.get();
            if (ageSample.isDone()) ageSample.get();
            ageSample.cancel(false);
        }
        long dbWaits = innodb("Innodb_row_lock_waits") - lockCount;
        long dbWaitMs = innodb("Innodb_row_lock_time") - lockMs;
        var codes = new java.util.TreeMap<String, Integer>();
        var acceptedOrderNos = new HashSet<String>();
        var winnerIndices = new ArrayList<Integer>();
        for (int i = 0; i < replies.length; i++) {
            Reply reply = replies[i];
            codes.merge(reply.code(), 1, Integer::sum);
            if (reply.accepted()) {
                assertEquals("V4".equals(strategy) ? 202 : 200, reply.httpStatus());
                assertEquals(strategy, reply.data().path("strategy").asText());
                String orderNo = reply.data().path("orderNo").asText();
                assertTrue(Long.parseLong(orderNo) > 0);
                assertTrue(acceptedOrderNos.add(orderNo), "Duplicate orderNo in the admitted set");
                winnerIndices.add(i);
            } else {
                assertTrue(List.of("A0301", "B0102").contains(reply.code()),
                        () -> "Unexpected result for " + strategy + ": HTTP " + reply.httpStatus() + " " + reply.code());
            }
        }

        assertInventory(showId, strategy, perTier, accepted);
        if (perTier == 400) assertEquals(perTier * TIER_COUNT, accepted, "All tiers must sell through");
        for (int i : winnerIndices.subList(0, REPLAYS)) {
            Reply replay = post(http, "orders", jwtTokens[i], bodies[i]);
            assertTrue(replay.accepted(), "Replay must return the original admission");
            assertEquals(replies[i].data().path("orderNo").asText(), replay.data().path("orderNo").asText());
        }
        assertInventory(showId, strategy, perTier, accepted);
        Arrays.sort(latency);
        System.out.printf(Locale.ROOT,
                "BENCH_RESULT pool=%d scenario=%s strategy=%s stock=%d requests=%d accepted=%d codes=%s "
                        + "apiMs=%.1f requestTPS=%.1f acceptedTPS=%.1f dbTPS=%.1f p95Ms=%.1f p99Ms=%.1f "
                        + "oldestPendingMs=%d drainMs=%.1f postApiDrainMs=%.1f dbLockWaits=%d dbLockWaitMs=%d "
                        + "oversell=0 duplicates=0 replay=%d pending=%d%n",
                USERS, perTier == 400 ? "sellout" : "throughput", strategy, perTier * TIER_COUNT,
                USERS, accepted, codes, ms(apiNs), rate(USERS, apiNs), rate(accepted, apiNs),
                rate(accepted, totalNs), ms(latency[(int) Math.ceil(USERS * .95) - 1]),
                ms(latency[(int) Math.ceil(USERS * .99) - 1]), oldestPendingMs, ms(totalNs),
                ms(totalNs - apiNs), dbWaits, dbWaitMs, REPLAYS, pending(showId));
    }

    private void assertInventory(long showId, String strategy, int perTier, int accepted) {
        Totals totals = dbTotals(showId);
        assertEquals(accepted, totals.tickets(), "Admission must equal durable quantity");
        assertEquals(accepted, totals.orders());
        assertEquals(0, totals.duplicates(), "At most one active order per identity and session");
        for (int idx = 0; idx < TIER_COUNT; idx++) {
            long tierId = tier(showId, idx);
            int remain = jdbc.queryForObject("SELECT available_stock FROM t_ticket_tier WHERE id = ?", Integer.class, tierId);
            int sold = OrderShardTableResolver.allShardTables().stream().mapToInt(shard -> jdbc.queryForObject(
                    "SELECT COALESCE(SUM(qty),0) FROM " + shard + " WHERE tier_id=? AND active_flag=1", Integer.class, tierId)).sum();
            assertEquals(perTier - sold, remain);
            assertTrue(remain >= 0);
            assertEquals(Integer.toString("V4".equals(strategy) ? remain : perTier),
                    redis.opsForValue().get(RedisKeys.stock(showId, tierId)), "Redis/DB mismatch or baseline rewrote live stock");
        }
        assertEquals(0, pending(showId));
    }

    private Totals dbTotals(long showId) {
        String groups = String.join(" UNION ALL ", OrderShardTableResolver.allShardTables().stream()
                .map(shard -> "SELECT user_id, qty FROM " + shard + " WHERE active_flag=1 AND deleted=0 AND show_id=?").toList());
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(qty),0), COALESCE(SUM(orders),0), COALESCE(SUM(orders > 1),0)
                FROM (SELECT user_id, SUM(qty) qty, COUNT(*) orders FROM (%s) active GROUP BY user_id) grouped
                """.formatted(groups), (rs, row) -> new Totals(rs.getInt(1), rs.getInt(2), rs.getInt(3)),
                showId, showId, showId, showId);
    }

    private void prepareShow(long showId, int perTier) {
        LocalDateTime now = ShowTimeUtil.nowLocalDateTime();
        jdbc.update("""
                INSERT INTO t_show (id,title,city,venue,show_time,sale_start_time,sale_end_time,sale_status)
                VALUES (?, 'Benchmark concert', 'Shanghai', 'Demo arena', ?, ?, ?, 'INIT')
                """, showId, now.plusWeeks(3), now.minusMinutes(5), now.plusHours(6));
        for (int idx = 0; idx < TIER_COUNT; idx++) {
            jdbc.update("""
                    INSERT INTO t_ticket_tier (id,show_id,tier_name,zone_id,price_cent,total_stock,available_stock)
                    VALUES (?,?,?,?,68000,?,?)
                    """, tier(showId, idx), showId, "Tier " + idx, "ZONE_" + idx, perTier, perTier);
        }
        preheat.preheat(showId, 1);
    }

    private void sampleOldest(long showId, AtomicLong oldest) {
        var first = redis.opsForZSet().rangeWithScores(RedisKeys.deductPendingZset(showId), 0, 0);
        if (first != null && !first.isEmpty()) {
            double score = first.iterator().next().getScore();
            oldest.accumulateAndGet(System.currentTimeMillis() - (long) (score * 1000), Math::max);
        }
    }

    private long pending(long showId) {
        Long size = redis.opsForZSet().zCard(RedisKeys.deductPendingZset(showId));
        return size == null ? 0 : size;
    }

    private long innodb(String variable) {
        return jdbc.queryForObject("SHOW GLOBAL STATUS LIKE '" + variable + "'", (rs, row) -> Long.parseLong(rs.getString(2)));
    }

    private Reply post(HttpClient http, String action, String token, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/seckill/" + action))
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(20)).POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            var data = json.readTree(response.body());
            return new Reply(response.statusCode(), data.path("code").asText(), data.path("data"));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Benchmark request interrupted", ex);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Benchmark HTTP request failed", ex);
        }
    }

    private void runWorkers(IntConsumer submit) throws Exception {
        AtomicInteger next = new AtomicInteger();
        var start = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Void>> results = new ArrayList<>(CONCURRENCY);
            for (int i = 0; i < CONCURRENCY; i++) {
                results.add(workers.submit(() -> {
                    start.await();
                    for (int index = next.getAndIncrement(); index < USERS; index = next.getAndIncrement()) submit.accept(index);
                    return null;
                }));
            }
            start.countDown();
            for (var result : results) result.get(5, TimeUnit.MINUTES);
        }
    }

    private long tier(long showId, int number) { return showId + number + 1; }
    private double ms(long ns) { return ns / 1_000_000.0; }
    private double rate(int n, long ns) { return n * 1_000_000_000.0 / ns; }

    private record Reply(int httpStatus, String code, JsonNode data) {
        boolean accepted() { return "00000".equals(code) || "00202".equals(code); }
    }

    private record Totals(int tickets, int orders, int duplicates) {}
}
