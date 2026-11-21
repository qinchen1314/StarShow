package com.starshow.boot;

import com.starshow.boot.RefundCrashWorker.Cut;
import com.starshow.boot.RefundCrashWorker.Fixture;
import com.starshow.common.constant.RedisKeys;
import com.starshow.common.id.GeneSnowflakeIdGenerator;
import com.starshow.order.mapper.OrderShardTableResolver;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "starshow.live.enabled", matches = "true")
class RefundCrashRecoveryTest {
    private static final List<Cut> CUTS = List.of(Cut.REQUEST_RECORDED, Cut.TICKET_LOCKED, Cut.CHANNEL_REFUNDED,
            Cut.DB_STOCK_RELEASED, Cut.REDIS_STOCK_RELEASED, Cut.DONE_RECORDED, Cut.LATE_PAYMENT_CHANNEL_REFUNDED);

    @TempDir(cleanup = CleanupMode.ON_SUCCESS)
    Path workspace;

    @Test
    void sevenForcedProcessExitsLeaveProofForANewProcessToRecover() throws Exception {
        assertTrue(env("MYSQL_DATABASE").matches("starshow_c4_[0-9a-f]{12}"), "Dedicated Phase 4 schema required");
        assertTrue(Integer.parseInt(env("REDIS_DATABASE")) > 0, "Dedicated Redis DB required");
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:" + env("MYSQL_PORT") + "/" + env("MYSQL_DATABASE")
                        + "?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
                "root", env("MYSQL_PASSWORD")));
        assertEquals(env("MYSQL_DATABASE"), jdbc.queryForObject("SELECT DATABASE()", String.class));
        Config redisConfig = new Config().setThreads(2).setNettyThreads(2).setCodec(StringCodec.INSTANCE);
        redisConfig.useSingleServer().setAddress("redis://127.0.0.1:" + env("REDIS_PORT"))
                .setDatabase(Integer.parseInt(env("REDIS_DATABASE"))).setPassword(env("REDIS_PASSWORD"))
                .setConnectionMinimumIdleSize(1).setConnectionPoolSize(4)
                .setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(2);
        RedissonClient redis = Redisson.create(redisConfig);
        try {
            var records = new EnumMap<Cut, Snapshot>(Cut.class);
            for (Cut cut : CUTS) {
                Fixture f = Fixture.forCut(cut);
                var process = start(cut);
                try {
                    awaitBoundary(cut, process);
                    Snapshot before = read(jdbc, redis, f);
                    assertCut(cut, before);
                    process.destroyForcibly();
                    assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Forced stop timed out");
                    assertFalse(process.isAlive());
                    assertEquals(before, read(jdbc, redis, f), "Boundary evidence must survive process termination");
                    records.put(cut, before);
                    System.out.printf("REFUND_CRASH_CUT cut=%s pid=%d exit=%d step=%s money=%d db=%d redis=%d%n",
                            cut, process.pid(), process.exitValue(), before.step(), before.channelRefunds(),
                            before.dbStock(), before.redisStock());
                } finally {
                    stop(process);
                }
            }

            long restart = System.nanoTime();
            var recovery = start(Cut.RECOVERY);
            try {
                awaitBoundary(Cut.RECOVERY, recovery);
                await(Duration.ofSeconds(45), () -> CUTS.stream()
                        .allMatch(cut -> "SUCCESS".equals(read(jdbc, redis, Fixture.forCut(cut)).refundStatus())),
                        "Restarted scheduler did not complete every refund");
                for (Cut cut : CUTS) assertRecovered(cut, records.get(cut), read(jdbc, redis, Fixture.forCut(cut)));
                long recoveryMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - restart);
                Thread.sleep(1000);
                for (Cut cut : CUTS) assertRecovered(cut, records.get(cut), read(jdbc, redis, Fixture.forCut(cut)));
                System.out.printf("REFUND_CRASH_RESULT cuts=%d restarts=1 recoveryMs=%d singleRefundAndRelease=7%n", CUTS.size(), recoveryMs);
            } finally {
                stop(recovery);
            }
        } finally {
            redis.shutdown(0, 3, TimeUnit.SECONDS);
        }
    }

    private void assertCut(Cut cut, Snapshot actual) {
        assertTrue(actual.refundNo() > 0);
        switch (cut) {
            case REQUEST_RECORDED -> {
                assertPhase(actual, "INIT", "PENDING", "TICKETED", "ISSUED", 0, 0, 0);
                assertNull(actual.releaseStatus());
            }
            case TICKET_LOCKED -> {
                assertPhase(actual, "LOCK_TICKET", "PENDING", "REFUNDING", "LOCKED", 0, 0, 0);
                assertNull(actual.releaseStatus());
            }
            case CHANNEL_REFUNDED -> {
                assertPhase(actual, "REFUND_PAY", "PENDING", "REFUNDING", "LOCKED", 1, 0, 0);
                assertNull(actual.releaseStatus());
            }
            case DB_STOCK_RELEASED -> {
                assertPhase(actual, "ROLLBACK_STOCK", "SUCCESS", "REFUNDING", "REFUNDED", 1, 1, 0);
                assertEquals("PENDING", actual.releaseStatus());
                assertTrue(actual.dbReleased());
                assertNull(actual.receipt());
            }
            case REDIS_STOCK_RELEASED -> {
                assertPhase(actual, "ROLLBACK_STOCK", "SUCCESS", "REFUNDING", "REFUNDED", 1, 1, 1);
                assertEquals("PENDING", actual.releaseStatus());
                assertTrue(actual.dbReleased());
                assertNotNull(actual.receipt());
            }
            case DONE_RECORDED -> assertRecovered(cut, actual, actual);
            case LATE_PAYMENT_CHANNEL_REFUNDED -> {
                assertPhase(actual, "REFUND_PAY", "PENDING", "CLOSED", "NONE", 1, 1, 1);
                assertEquals("ABNORMAL_PAID", actual.paymentStatus());
                assertEquals("RELEASED", actual.releaseStatus());
            }
            default -> fail("Unexpected cut: " + cut);
        }
    }

    private void assertRecovered(Cut cut, Snapshot before, Snapshot after) {
        var f = Fixture.forCut(cut);
        boolean late = cut == Cut.LATE_PAYMENT_CHANNEL_REFUNDED;
        assertPhase(after, "DONE", "SUCCESS", late ? "CLOSED" : "REFUNDED", late ? "NONE" : "REFUNDED", 1, 1, 1);
        assertEquals(before.refundNo(), after.refundNo());
        assertEquals("SUCCESS", after.refundStatus());
        assertEquals(late ? "ABNORMAL_PAID" : "SUCCESS", after.paymentStatus());
        assertEquals(late ? 0 : 1, after.ticketCount());
        assertEquals(68_000, after.channelRefundCent());
        assertEquals(f.orderNo(), after.activeFlag(), "Refund must free the original buyer to rebook");
        assertEquals("RELEASED", after.releaseStatus());
        assertTrue(after.dbReleased());
        assertEquals(f.orderNo() + ":1", after.receipt());
        assertFalse(after.seatOccupied(), "The original seat must return to sale");
        assertFalse(after.userBought(), "The original buyer must no longer own an active hold");
    }

    private void assertPhase(Snapshot s, String step, String funds, String order, String ticket,
                             int refunds, int dbStock, int redisStock) {
        assertEquals(step, s.step());
        assertEquals(funds, s.refundFunds());
        assertEquals(order, s.orderStatus());
        assertEquals(ticket, s.ticketStatus());
        assertEquals(refunds, s.channelRefunds());
        assertEquals(dbStock, s.dbStock());
        assertEquals(redisStock, s.redisStock());
        assertEquals(redisStock == 0, s.seatOccupied());
        assertEquals(redisStock == 0, s.userBought());
    }

    private Snapshot read(JdbcTemplate jdbc, RedissonClient redis, Fixture f) {
        var payment = jdbc.queryForMap("SELECT status FROM t_payment_order WHERE order_no=?", f.orderNo());
        var refund = jdbc.queryForMap("SELECT refund_no, status, saga_step, pay_refund_status FROM t_refund_order WHERE order_no=?", f.orderNo());
        var order = jdbc.queryForMap("SELECT status, active_flag FROM " + f.shard() + " WHERE order_no=?", f.orderNo());
        var release = jdbc.queryForList("""
                SELECT status, db_released_at FROM t_order_action WHERE order_no=? AND action_type='RELEASE_STOCK'
                """, f.orderNo()).stream().findFirst().orElse(null);
        var channel = jdbc.queryForMap("""
                SELECT COUNT(*) AS entries, COALESCE(SUM(amount_cent),0) AS amount FROM t_mock_channel_txn
                WHERE order_no=? AND kind='REFUND' AND state='SUCCESS'
                """, f.orderNo());
        int shard = GeneSnowflakeIdGenerator.resolveShardByOrderNo(f.orderNo(), OrderShardTableResolver.SHARD_COUNT);
        var tickets = jdbc.queryForList("SELECT status FROM t_ticket_" + shard + " WHERE order_no=?", String.class, f.orderNo());
        return new Snapshot(((Number) refund.get("refund_no")).longValue(), refund.get("status").toString(),
                refund.get("saga_step").toString(), refund.get("pay_refund_status").toString(), payment.get("status").toString(),
                order.get("status").toString(), ((Number) order.get("active_flag")).longValue(),
                tickets.isEmpty() ? "NONE" : tickets.getFirst(), tickets.size(),
                ((Number) channel.get("entries")).intValue(), ((Number) channel.get("amount")).longValue(),
                release == null ? null : release.get("status").toString(), release != null && release.get("db_released_at") != null,
                jdbc.queryForObject("SELECT available_stock FROM t_ticket_tier WHERE id=?", Integer.class, f.tierId()),
                Integer.parseInt(redis.<String>getBucket(RedisKeys.stock(f.showId(), f.tierId())).get()),
                redis.getBitSet(RedisKeys.seatBitmap(f.showId(), "FLOOR")).get(2),
                redis.getSet(RedisKeys.boughtUsers(f.showId())).contains(Long.toString(f.userId())),
                redis.<String>getBucket(RedisKeys.stockReleaseReceipt(f.showId(), f.orderNo())).get());
    }

    private Process start(Cut cut) throws Exception {
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        String java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path options = workspace.resolve(cut + ".args");
        Files.writeString(options, "-Xmx192m\n-cp\n\"" + classpath.replace("\\", "\\\\") + "\"\n");
        return new ProcessBuilder(java, "@" + options, RefundCrashWorker.class.getName(),
                cut.name(), marker(cut).toString())
                .redirectErrorStream(true).redirectOutput(workspace.resolve(cut + ".log").toFile()).start();
    }

    private void awaitBoundary(Cut cut, Process process) throws Exception {
        await(Duration.ofSeconds(35), () -> {
            assertTrue(process.isAlive(), () -> cut + " worker exited early; see " + workspace.resolve(cut + ".log"));
            return Files.exists(marker(cut));
        }, cut + " did not reach its expected boundary; see " + workspace.resolve(cut + ".log"));
    }

    private Path marker(Cut cut) { return workspace.resolve(cut + ".ready"); }

    private void await(Duration timeout, BooleanSupplier probe, String message) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (probe.getAsBoolean()) return;
            Thread.sleep(200);
        }
        fail(message);
    }

    private void stop(Process process) throws InterruptedException {
        if (process.isAlive()) {
            process.destroyForcibly();
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Test process still running");
        }
    }

    private static String env(String key) {
        String value = System.getenv(key);
        assertTrue(value != null && !value.isBlank(), "Missing test setting " + key);
        return value;
    }

    private record Snapshot(long refundNo, String refundStatus, String step, String refundFunds, String paymentStatus,
                            String orderStatus, long activeFlag, String ticketStatus, int ticketCount,
                            int channelRefunds, long channelRefundCent, String releaseStatus, boolean dbReleased,
                            int dbStock, int redisStock, boolean seatOccupied, boolean userBought, String receipt) {}
}
