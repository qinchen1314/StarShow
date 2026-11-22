package com.starshow.show.queue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshow.api.facade.UserFacade;
import com.starshow.boot.StarShowBootAllApplication;
import com.starshow.common.auth.AuthHeaders;
import com.starshow.common.config.StarShowSecurityProperties;
import com.starshow.common.constant.RedisKeys;
import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.order.mapper.OrderShardMapper;
import com.starshow.show.mapper.ShowMapper;
import com.starshow.show.service.ShowScheduleService;
import com.starshow.user.mapper.UserIdentityMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.starshow.show.queue.WaitingRoomStore.Action.*;
import static com.starshow.show.queue.WaitingRoomStore.Status.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfSystemProperty(named = "starshow.waiting-room.live", matches = "true")
@SpringBootTest(classes = StarShowBootAllApplication.class, properties = {
        "starshow.waiting-room.enabled=true", "starshow.waiting-room.show-ids=15001,15002,15003",
        "starshow.waiting-room.maintenance-ms=3600000", "starshow.waiting-room.join-per-minute=6",
        "starshow.show.task-recovery-enabled=false", "starshow.show.lifecycle-sync-enabled=false",
        "starshow.show.bloom-enabled=false", "starshow.reservation.recovery-enabled=false",
        "starshow.reservation.mq-enabled=false", "starshow.seckill.mq-enabled=false",
        "starshow.seckill.timeout-dispatch-enabled=false", "starshow.seckill.pending-reconcile-enabled=false",
        "starshow.payment.enabled=false", "knife4j.enable=false"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("monolith")
class WaitingRoomLiveVerificationTest {
    private static final long HTTP_SHOW = 15001;
    private static final long ORDER_SHOW = 15002;
    private static final long OUTAGE_SHOW = 15003;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private WaitingRoomStore store;
    @Autowired private WaitingRoomProperties configuration;
    @Autowired private WaitingRoomScheduleProjection schedules;
    @Autowired private ShowScheduleService reschedules;
    @Autowired private StarShowSecurityProperties auth;
    @Autowired private ObjectMapper json;
    @Autowired private MockMvc http;
    @Value("${spring.data.redis.database:0}") private int redisDatabase;
    @SpyBean private UserFacade users;
    @SpyBean private UserIdentityMapper identityRows;
    @SpyBean private ShowMapper showRows;
    @SpyBean private OrderShardMapper orderRows;
    @SpyBean private StringRedisTemplate sharedRedis;

    @BeforeEach
    void requireDedicatedStorage() {
        String database = jdbc.queryForObject("SELECT DATABASE()", String.class);
        assertNotNull(database);
        assertTrue(database.matches("starshow_queue_[a-z0-9]+"));
        assertTrue(redisDatabase > 0);
        assertTrue(configuration.getRedisDatabase() > 0);
    }

    @Test
    void httpRecoversByJoinOrCredentialAndExpiresTheOldGeneration() throws Exception {
        LocalDateTime start = insertShow(HTTP_SHOW);
        long buyer = 9007199254740993L;
        verifyIdentityFixture(buyer);
        schedules.maintain();
        http.perform(post(path(HTTP_SHOW, ""))).andExpect(status().isUnauthorized());
        http.perform(post(path(HTTP_SHOW, "")).headers(headers(buyer + 1)))
                .andExpect(jsonPath("$.code").value("A0104"))
                .andExpect(jsonPath("$.data.nextAction").value("VERIFY_IDENTITY"));
        http.perform(post(path(HTTP_SHOW, "")).headers(headers(buyer)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"saleConfigVersion\":2}"))
                .andExpect(jsonPath("$.code").value("A0205"))
                .andExpect(jsonPath("$.data.saleConfigVersion").value(1));

        JsonNode joined = join(HTTP_SHOW, buyer);
        String credential = joined.path("queueReadToken").asText();
        JsonNode resumed = join(HTTP_SHOW, buyer);
        String queueId = joined.path("queueId").asText();
        assertEquals(queueId, resumed.path("queueId").asText());
        http.perform(get(path(HTTP_SHOW, "/me")).headers(headers(buyer)))
                .andExpect(jsonPath("$.data.queueId").value(queueId));
        JsonNode readonly = read(HTTP_SHOW, credential);
        assertEquals(queueId, readonly.path("queueId").asText());
        http.perform(get(path(HTTP_SHOW, ";source=fixture/me")).header(AuthHeaders.X_QUEUE_READ_TOKEN, credential))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.queueId").value(queueId));
        assertTrue(readonly.path("retryAfterMs").asInt() >= 20000 && readonly.path("retryAfterMs").asInt() <= 30000);
        http.perform(get(path(HTTP_SHOW, "/me")).headers(headers(buyer + 1)).header(AuthHeaders.X_QUEUE_READ_TOKEN, credential))
                .andExpect(status().isUnauthorized());
        http.perform(get(path(OUTAGE_SHOW, "/me")).header(AuthHeaders.X_QUEUE_READ_TOKEN, credential))
                .andExpect(status().isUnauthorized());
        http.perform(post(path(HTTP_SHOW, "")).header(AuthHeaders.X_QUEUE_READ_TOKEN, credential))
                .andExpect(status().isUnauthorized());
        for (int i = 0; i < 4; i++) assertEquals(queueId, join(HTTP_SHOW, buyer).path("queueId").asText());
        http.perform(post(path(HTTP_SHOW, "")).headers(headers(buyer)))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        assertFalse(Boolean.TRUE.equals(sharedRedis.hasKey(RedisKeys.queueMeta(HTTP_SHOW))));

        schedules.pause(showRows.selectById(HTTP_SHOW));
        http.perform(get(path(HTTP_SHOW, "/me")).header(AuthHeaders.X_QUEUE_READ_TOKEN, credential))
                .andExpect(status().isServiceUnavailable());
        schedules.maintain();
        assertEquals(queueId, read(HTTP_SHOW, credential).path("queueId").asText());

        var committed = reschedules.commitRescheduleWithOutbox(HTTP_SHOW, start.plusMinutes(2), start.plusHours(1));
        assertEquals(2, committed.newVersion());
        http.perform(get(path(HTTP_SHOW, "/me")).header(AuthHeaders.X_QUEUE_READ_TOKEN, credential))
                .andExpect(status().isServiceUnavailable()).andExpect(header().exists("Retry-After"));
        assertTrue(reschedules.retryRescheduleSync(committed.syncTask()));
        JsonNode expired = read(HTTP_SHOW, credential);
        assertEquals("EXPIRED", expired.path("queueStatus").asText());
        assertEquals(2, expired.path("saleConfigVersion").asInt());
        http.perform(post(path(HTTP_SHOW, "")).headers(headers(buyer)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"saleConfigVersion\":1}"))
                .andExpect(jsonPath("$.code").value("A0205"))
                .andExpect(jsonPath("$.data.saleConfigVersion").value(2));

        Path folder = Files.createDirectories(Path.of("target", "issue-015-evidence"));
        var redacted = joined.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) redacted).remove("queueReadToken");
        json.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("waiting-room.json").toFile(),
                Map.of("join", redacted, "read", readonly, "rescheduled", expired));
    }

    @Test
    void exactBoundaryAndConcurrentInstancesShareOneSequenceAndPreBatch() throws Exception {
        long t0 = System.currentTimeMillis() + 120_000;
        long end = t0 + 90_000;
        String pre = timedScript(t0 - 1);
        String exact = timedScript(t0);
        String post = timedScript(t0 + 1);
        assertEquals("OK", store.configure("PUBLISH", ORDER_SHOW,
                new WaitingRoomStore.Projection(1, t0, end, UUID.randomUUID().toString(), ""), ""));
        try (WaitingRoomStore second = new WaitingRoomStore(configuration, json);
             var workers = Executors.newFixedThreadPool(12)) {
            assertEquals("PRE", timedJoin(store, pre, 11, "document-11").entry().phase());
            assertEquals("PRE", timedJoin(second, pre, 12, "document-12").entry().phase());
            Path worker = Files.createTempDirectory(Path.of(System.getenv("WAITING_ROOM_FAULT_DIR")), "worker-");
            var unique = new HashSet<String>();
            Process otherJvm = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx80m", "-Xss512k", "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    WaitingRoomConcurrentWorker.class.getName(), worker.toString(), Long.toString(t0 + 1))
                    .redirectOutput(worker.resolve("worker.log").toFile()).redirectErrorStream(true).start();
            try {
                await(worker.resolve("ready"));
                List<Callable<WaitingRoomStore.Result>> requests = new ArrayList<>();
                for (int i = 0; i < 100; i++) {
                    int attempt = i;
                    long user = userForAttempt(attempt);
                    requests.add(() -> timedJoin(store, attempt == 0 ? exact : post, user, "document-" + user));
                }
                Files.createFile(worker.resolve("start"));
                for (var future : workers.invokeAll(requests, 25, TimeUnit.SECONDS)) {
                    var result = future.get();
                    assertEquals(WAITING, result.status());
                    assertEquals("POST", result.entry().phase());
                    unique.add(result.entry().queueId());
                }
                assertTrue(otherJvm.waitFor(25, TimeUnit.SECONDS), "Second JVM did not finish 100 joins");
                assertEquals(0, otherJvm.exitValue(), () -> workerLog(worker));
                var secondResults = Files.readAllLines(worker.resolve("queue-ids"));
                assertEquals(100, secondResults.size());
                unique.addAll(secondResults);
            } finally {
                if (otherJvm.isAlive()) {
                    otherJvm.destroyForcibly();
                    otherJvm.waitFor(5, TimeUnit.SECONDS);
                }
            }
            assertEquals(101, unique.size());
            assertEquals(IDENTITY_CONFLICT, timedJoin(second, post, 90, "document-11").status());
            assertEquals("POST", timedJoin(second, pre, 91, "document-91").entry().phase());
            assertTrue(store.inspect(RANK, ORDER_SHOW, 1, 11, "").rank() <= 2);
            assertTrue(store.inspect(RANK, ORDER_SHOW, 1, 12, "").rank() <= 2);
            assertNotEquals(store.inspect(RANK, ORDER_SHOW, 1, 11, "").rank(), store.inspect(RANK, ORDER_SHOW, 1, 12, "").rank());
            assertTrue(second.inspect(RANK, ORDER_SHOW, 1, 13, "").rank() > 2);
            assertTrue(second.inspect(RANK, ORDER_SHOW, 1, 91, "").rank() > 2);
            assertEquals(ENDED, second.execute(READ, ORDER_SHOW, 1, 13, "", "", "", "", timedScript(end)).status());
            assertEquals(WAITING, second.execute(READ, ORDER_SHOW, 1, 13, "", "", "", "", timedScript(end - 1)).status());
        }
        System.out.println("ISSUE_015_ORDER jvms=2 attempts=200 uniquePost=101 duplicateAccount=0 frozenPre=2");
    }

    @Test
    void readFloodSkipsTradeAndIdentityAndQueueOutageDoesNotCommitReschedule() throws Exception {
        LocalDateTime start = insertShow(OUTAGE_SHOW);
        long buyer = 91000000L;
        long other = buyer + 2;
        verifyIdentityFixture(buyer);
        verifyIdentityFixture(other);
        schedules.maintain();
        JsonNode joined = join(OUTAGE_SHOW, buyer);
        join(OUTAGE_SHOW, other);
        String token = joined.path("queueReadToken").asText();
        long saleStart = store.projection(OUTAGE_SHOW).startAt();
        store.execute(SEAL, OUTAGE_SHOW, 1, 0, "", "", "", "", timedScript(saleStart));
        var before = store.inspect(READ, OUTAGE_SHOW, 1, buyer, "").entry();
        long buyerRank = store.inspect(RANK, OUTAGE_SHOW, 1, buyer, "").rank();
        long otherRank = store.inspect(RANK, OUTAGE_SHOW, 1, other, "").rank();
        assertEquals(java.util.Set.of(1L, 2L), java.util.Set.of(buyerRank, otherRank));
        clearInvocations(users, identityRows, showRows, orderRows, sharedRedis);
        try (var readers = Executors.newFixedThreadPool(12)) {
            List<Callable<JsonNode>> calls = new ArrayList<>();
            for (int i = 0; i < 200; i++) calls.add(() -> read(OUTAGE_SHOW, token));
            for (var call : readers.invokeAll(calls, 25, TimeUnit.SECONDS)) {
                assertEquals(before.queueId(), call.get().path("queueId").asText());
            }
        }
        verifyNoInteractions(users, identityRows, showRows, orderRows, sharedRedis);
        System.out.println("ISSUE_015_READ polls=200 identity=0 showDb=0 order=0 sharedRedis=0");

        Path control = Path.of(System.getenv("WAITING_ROOM_FAULT_DIR"));
        assertTrue(Files.isDirectory(control));
        Files.createFile(control.resolve("stop-queue"));
        await(control.resolve("queue-offline"));
        try {
            http.perform(get(path(OUTAGE_SHOW, "/me")).header(AuthHeaders.X_QUEUE_READ_TOKEN, token))
                    .andExpect(status().isServiceUnavailable()).andExpect(header().exists("Retry-After"));
            http.perform(post(path(OUTAGE_SHOW, "")).headers(headers(buyer + 1)))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("B0202"));
            verifyNoInteractions(users, identityRows, orderRows, sharedRedis);
            BizException retry = assertThrows(BizException.class, () ->
                    reschedules.commitRescheduleWithOutbox(OUTAGE_SHOW, start.plusMinutes(1), start.plusHours(1)));
            assertEquals(ErrorCode.QUEUE_UNAVAILABLE, retry.getErrorCode());
            assertEquals(1, jdbc.queryForObject("SELECT sale_config_version FROM t_show WHERE id=?", Integer.class, OUTAGE_SHOW));
        } finally {
            Files.createFile(control.resolve("start-queue"));
        }
        await(control.resolve("queue-restored"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        assertEquals(before.queueId(), awaitReadAfterReconnect(OUTAGE_SHOW, token, deadline).path("queueId").asText());
        var restored = awaitRankAfterReconnect(buyer, deadline);
        assertEquals(before, restored.entry());
        assertEquals(buyerRank, restored.rank());
        assertEquals(otherRank, awaitRankAfterReconnect(other, deadline).rank());
        System.out.println("ISSUE_015_RECOVERY queueId=preserved rankKey=preserved ranks=preserved blockedReschedule=true");
    }

    private LocalDateTime insertShow(long id) {
        LocalDateTime start = LocalDateTime.now().plusMinutes(10);
        jdbc.update("""
                INSERT INTO t_show (id,title,city,venue,show_time,sale_start_time,sale_end_time,sale_status)
                VALUES (?, 'Waiting room fixture', 'Shanghai', 'Hall', ?, ?, ?, 'INIT')
                """, id, start.plusDays(1), start, start.plusHours(1));
        return start;
    }

    private void verifyIdentityFixture(long user) {
        jdbc.update("""
                INSERT INTO t_user_identity (user_id,real_name_cipher,id_card_cipher,id_card_masked,id_card_hmac)
                VALUES (?, 'fixture', 'fixture', 'fixture', ?)
                """, user, "%064x".formatted(user));
    }

    private org.springframework.http.HttpHeaders headers(long buyer) {
        var headers = new org.springframework.http.HttpHeaders();
        headers.set(AuthHeaders.X_INTERNAL_GATEWAY_SECRET, auth.getInternalGatewaySecret());
        headers.set(AuthHeaders.X_USER_ID, Long.toString(buyer));
        return headers;
    }

    private JsonNode join(long showId, long buyer) throws Exception {
        var response = http.perform(post(path(showId, "")).headers(headers(buyer)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.queueStatus").value("WAITING")).andReturn().getResponse();
        return json.readTree(response.getContentAsByteArray()).path("data");
    }

    private JsonNode read(long showId, String credential) throws Exception {
        var response = http.perform(get(path(showId, "/me")).header(AuthHeaders.X_QUEUE_READ_TOKEN, credential))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse();
        return json.readTree(response.getContentAsByteArray()).path("data");
    }

    private JsonNode awaitReadAfterReconnect(long showId, String credential, long deadline) throws Exception {
        do {
            var response = http.perform(get(path(showId, "/me")).header(AuthHeaders.X_QUEUE_READ_TOKEN, credential))
                    .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse();
            JsonNode result = json.readTree(response.getContentAsByteArray());
            if (response.getStatus() == 200) {
                assertEquals("WAITING", result.path("data").path("queueStatus").asText());
                return result.path("data");
            }
            assertEquals(503, response.getStatus());
            assertEquals("B0202", result.path("code").asText());
            assertNotNull(response.getHeader("Retry-After"));
            TimeUnit.MILLISECONDS.sleep(150);
        } while (System.nanoTime() < deadline);
        return fail("Waiting room did not recover its existing record within 10 seconds of Redis restarting");
    }

    private WaitingRoomStore.Result awaitRankAfterReconnect(long userId, long deadline) throws InterruptedException {
        do {
            try {
                var result = store.inspect(RANK, OUTAGE_SHOW, 1, userId, "");
                assertEquals(WAITING, result.status(), "The queue record and rank index must both survive the restart");
                return result;
            } catch (BizException ex) {
                assertEquals(ErrorCode.QUEUE_UNAVAILABLE, ex.getErrorCode());
                TimeUnit.MILLISECONDS.sleep(150);
            }
        } while (System.nanoTime() < deadline);
        return fail("Rank lookup did not recover within the same 10-second Redis recovery window");
    }

    private static String path(long showId, String suffix) {
        return "/api/v1/shows/" + showId + "/queue" + suffix;
    }

    static String timedScript(long millis) throws Exception {
        String source = new org.springframework.core.io.ClassPathResource("lua/waiting_room_queue.lua")
                .getContentAsString(StandardCharsets.UTF_8);
        String clockLine = "local clock = redis.call('TIME')";
        assertTrue(source.contains(clockLine));
        return source.replace(clockLine, "local clock = {" + millis / 1000 + "," + millis % 1000 * 1000 + "}");
    }

    static WaitingRoomStore.Result timedJoin(WaitingRoomStore target, String script, long user, String identity) {
        String id = UUID.randomUUID().toString();
        return target.execute(JOIN, ORDER_SHOW, 1, user, "", id, identity, id, script);
    }

    static long userForAttempt(int attempt) {
        return attempt % 100 < 50 ? 13 : 100 + attempt;
    }

    private static String workerLog(Path worker) {
        try {
            return Files.readString(worker.resolve("worker.log"));
        } catch (java.io.IOException ex) {
            return "Could not read verification worker log";
        }
    }

    static void await(Path marker) throws Exception {
        long expires = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!Files.exists(marker) && System.nanoTime() < expires) {
            TimeUnit.MILLISECONDS.sleep(100);
        }
        assertTrue(Files.exists(marker), "Missing cloud fault-runner marker " + marker.getFileName());
    }
}
