package com.starshow.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshow.api.constant.MqTopics;
import com.starshow.api.event.ShowRemindEvent;
import com.starshow.api.facade.ShowReadFacade;
import com.starshow.common.auth.AuthHeaders;
import com.starshow.common.result.ErrorCode;
import com.starshow.common.trace.TraceIdContext;
import com.starshow.common.validator.IdCardValidator;
import com.starshow.show.facade.ShowReadFacadeImpl;
import com.starshow.user.client.ShowServiceFeignClient;
import com.starshow.user.domain.entity.ReservationDO;
import com.starshow.user.domain.entity.UserIdentityDO;
import com.starshow.user.mapper.ReservationMapper;
import com.starshow.user.mapper.UserIdentityMapper;
import com.starshow.user.mq.ShowRemindConsumer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.Message;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 链路一单进程聚合态 (starshow-boot-all / monolith) 端到端 Spring Boot 集成测试（H2 + 内存桩隔离外部中间件）：
 * 1. 验证 monolith 下仅装配本地 ShowReadFacadeImpl、零 OpenFeign 客户端注册（ISSUE-005）；
 * 2. 验证独立注入强密钥启动通过（ISSUE-002）；
 * 3. 验证注册 -> 防重 -> 登录 -> 防头伪造 -> 未实名拦截 -> 实名加密+一证一号拦截 -> 预约(0->3->1)与终态不可回退 -> 登出黑名单。
 */
@SpringBootTest(
        classes = StarShowBootAllApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.url=jdbc:h2:mem:starshow_chain1;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.autoconfigure.exclude=" +
                        "org.redisson.spring.starter.RedissonAutoConfigurationV2," +
                        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                        "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration," +
                        "org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration",
                "starshow.security.jwt-secret=monolith-it-jwt-secret-9a8b7c6d5e4f3a2b1c0d",
                "starshow.security.aes-key=M7f9K2m4P6q8R1t3V5w7X9y0Z2b4D6f8",
                "starshow.security.idcard-pepper=monolith-it-pepper-7f8e9d6c5b4a",
                "starshow.security.internal-gateway-secret=monolith-it-internal-5c4b3a2f1e",
                "starshow.reservation.mq-enabled=true",
                "starshow.reservation.recovery-enabled=false",
                "starshow.reservation.test-override-delay-ms=5000",
                "knife4j.enable=false"
        }
)
@ActiveProfiles("monolith")
@Sql(scripts = "classpath:schema-chain1-h2.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class Chain1MonolithIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ShowReadFacade showReadFacade;
    @Autowired
    private ObjectProvider<ShowServiceFeignClient> feignClientProvider;
    @Autowired
    private UserIdentityMapper userIdentityMapper;
    @Autowired
    private ReservationMapper reservationMapper;
    @Autowired
    private ShowRemindConsumer showRemindConsumer;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;
    @MockBean
    private RedissonClient redissonClient;
    @MockBean
    private RocketMQTemplate rocketMQTemplate;

    private final Map<String, String> redisKv = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> redisHash = new ConcurrentHashMap<>();
    private final Set<Long> bloomSet = ConcurrentHashMap.newKeySet();

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUpInMemoryMiddlewareStubs() throws Exception {
        redisKv.clear();
        redisHash.clear();
        bloomSet.clear();

        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOps);
        when(stringRedisTemplate.hasKey(anyString())).thenAnswer(inv -> redisKv.containsKey(inv.getArgument(0)));
        when(valueOps.get(anyString())).thenAnswer(inv -> redisKv.get(inv.getArgument(0)));
        doAnswer(inv -> {
            redisKv.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));

        when(hashOps.get(anyString(), any())).thenAnswer(inv -> {
            Map<String, String> m = redisHash.get(inv.getArgument(0));
            return m == null ? null : m.get(String.valueOf(inv.getArgument(1)));
        });
        when(hashOps.multiGet(anyString(), anyCollection())).thenAnswer(inv -> {
            Map<String, String> m = redisHash.get(inv.getArgument(0));
            return ((Collection<Object>) inv.getArgument(1)).stream()
                    .map(f -> m == null ? null : m.get(String.valueOf(f)))
                    .toList();
        });
        when(hashOps.entries(anyString())).thenAnswer(inv -> {
            Map<String, String> m = redisHash.get(inv.getArgument(0));
            return m == null ? Map.of() : new java.util.HashMap<Object, Object>(m);
        });
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(inv -> {
            RedisScript<?> script = inv.getArgument(0);
            String key = ((List<String>) inv.getArgument(1)).get(0);
            Object rawArg2 = inv.getRawArguments()[2];
            Object[] args = rawArg2 instanceof Object[] arr ? arr : new Object[]{rawArg2};
            String lua = script.getScriptAsString();
            Map<String, String> h = redisHash.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
            if (lua.contains("stage', 'BLOCKED'")) {
                h.put("version", String.valueOf(args[0]));
                h.put("stage", "BLOCKED");
                return 1L;
            }
            if (lua.contains("stage', 'INIT'")) {
                h.put("version", String.valueOf(args[0]));
                h.put("stage", "INIT");
                h.put("saleStartEpochMs", String.valueOf(args[1]));
                h.put("saleEndEpochMs", String.valueOf(args[2]));
                return 1L;
            }
            if (lua.contains("HINCRBY")) {
                String expectedEpoch = String.valueOf(args[0]);
                if (expectedEpoch.equals(h.get("epoch"))) {
                    long next = Long.parseLong(h.getOrDefault("count", "0")) + 1L;
                    h.put("count", String.valueOf(next));
                    return next;
                }
                return -1L;
            }
            if (lua.contains("'count', ARGV[2]")) {
                if (String.valueOf(args[0]).equals(h.get("epoch"))) {
                    h.put("count", String.valueOf(args[1]));
                    h.put("epoch", String.valueOf(args[2]));
                    return Long.parseLong(String.valueOf(args[1]));
                }
                return -1L;
            }
            // FENCE_SCRIPT
            h.put("epoch", String.valueOf(args[0]));
            return 1L;
        });

        RLock lock = mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        RBloomFilter<Long> bloom = mock(RBloomFilter.class);
        doReturn(bloom).when(redissonClient).getBloomFilter(anyString());
        when(bloom.tryInit(anyLong(), anyDouble())).thenReturn(true);
        when(bloom.contains(anyLong())).thenAnswer(inv -> bloomSet.contains(inv.getArgument(0)));
        when(bloom.add(anyLong())).thenAnswer(inv -> bloomSet.add(inv.getArgument(0)));

        SendResult okResult = new SendResult();
        okResult.setSendStatus(SendStatus.SEND_OK);
        okResult.setMsgId("mock-rmq-msg-1");
        when(rocketMQTemplate.syncSendDeliverTimeMills(eq(MqTopics.SHOW_REMIND), any(Message.class), anyLong()))
                .thenReturn(okResult);
    }

    @Test
    void shouldUseLocalShowReadFacadeOnlyInMonolithModeAndCompleteChain1Flow() throws Exception {
        // 0. 实证 ISSUE-005：monolith 下严格只注入本地 ShowReadFacadeImpl，绝不注册 OpenFeign 客户端
        assertInstanceOf(ShowReadFacadeImpl.class, showReadFacade);
        assertNull(feignClientProvider.getIfAvailable(), "OpenFeign client must NOT be registered in monolith mode");

        // 1. 注册账号 A（及重复手机号物理约束 uk_phone 验证）
        ResponseEntity<String> regA = postJson("/api/v1/auth/register",
                "{\"phone\":\"13800001001\",\"password\":\"StarShow2026\",\"nickname\":\"粉丝A\"}", null);
        assertEquals(HttpStatus.OK, regA.getStatusCode());
        assertNotNull(regA.getHeaders().getFirst(TraceIdContext.HEADER_TRACE_ID));
        JsonNode regAJson = objectMapper.readTree(regA.getBody());
        assertEquals("00000", regAJson.path("code").asText());
        assertTrue(regAJson.path("data").path("userId").isTextual(), "64-bit userId must be JSON String");

        ResponseEntity<String> regDup = postJson("/api/v1/auth/register",
                "{\"phone\":\"13800001001\",\"password\":\"StarShow2026\"}", null);
        assertEquals(ErrorCode.PHONE_ALREADY_EXISTS.getCode(),
                objectMapper.readTree(regDup.getBody()).path("code").asText());

        // 2. 登录账号 A 获取 JWT
        ResponseEntity<String> loginA = postJson("/api/v1/auth/login",
                "{\"phone\":\"13800001001\",\"password\":\"StarShow2026\"}", null);
        String tokenA = objectMapper.readTree(loginA.getBody()).path("data").path("token").asText();
        assertFalse(tokenA.isBlank());

        // 3. 伪造 X-User-Id 请求头必须被 401 拦截
        HttpHeaders spoofHeaders = new HttpHeaders();
        spoofHeaders.set(AuthHeaders.X_USER_ID, "999");
        ResponseEntity<String> spoofResp = restTemplate.exchange(
                "/api/v1/users/identity", HttpMethod.GET, new HttpEntity<>(spoofHeaders), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, spoofResp.getStatusCode());
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(),
                objectMapper.readTree(spoofResp.getBody()).path("code").asText());

        // 4. 账号 A 未实名查 /me 前置提示 VERIFY_IDENTITY，直接预约 -> A0104
        HttpHeaders authAHeaders = new HttpHeaders();
        authAHeaders.set(AuthHeaders.AUTHORIZATION, AuthHeaders.BEARER_PREFIX + tokenA);
        ResponseEntity<String> myUnverified = restTemplate.exchange(
                "/api/v1/reservations/10003/me", HttpMethod.GET, new HttpEntity<>(authAHeaders), String.class);
        JsonNode myUnverifiedData = objectMapper.readTree(myUnverified.getBody()).path("data");
        assertFalse(myUnverifiedData.path("realNameVerified").asBoolean());
        assertEquals("VERIFY_IDENTITY", myUnverifiedData.path("nextAction").asText());
        assertFalse(myUnverifiedData.path("serverTime").asText().isBlank());

        ResponseEntity<String> rsvBeforeId = postJson("/api/v1/reservations", "{\"showId\":10003}", tokenA);
        assertEquals(ErrorCode.IDENTITY_REQUIRED.getCode(),
                objectMapper.readTree(rsvBeforeId.getBody()).path("code").asText());

        // 5. 账号 A 实名认证 + 验证 DB 零明文
        String validIdCard = buildValidIdCard("11010119920808001");
        ResponseEntity<String> verifyA = postJson("/api/v1/users/identity",
                "{\"realName\":\"林星演\",\"idCard\":\"" + validIdCard + "\"}", tokenA);
        JsonNode verifyAJson = objectMapper.readTree(verifyA.getBody());
        assertEquals("00000", verifyAJson.path("code").asText());
        assertTrue(verifyAJson.path("data").path("verified").asBoolean());

        UserIdentityDO storedIdentity = userIdentityMapper.selectList(null).get(0);
        assertFalse(storedIdentity.getRealNameCipher().contains("林星演"));
        assertFalse(storedIdentity.getIdCardCipher().contains(validIdCard));
        assertEquals(64, storedIdentity.getIdCardHmac().length());

        // 6. 注册账号 B 并用同一身份证抢绑 -> 撞真实 DB uk_id_card_hmac 拦截 A0105
        postJson("/api/v1/auth/register", "{\"phone\":\"13800001002\",\"password\":\"StarShow2026\"}", null);
        ResponseEntity<String> loginB = postJson("/api/v1/auth/login",
                "{\"phone\":\"13800001002\",\"password\":\"StarShow2026\"}", null);
        String tokenB = objectMapper.readTree(loginB.getBody()).path("data").path("token").asText();

        ResponseEntity<String> verifyB = postJson("/api/v1/users/identity",
                "{\"realName\":\"黄牛号\",\"idCard\":\"" + validIdCard + "\"}", tokenB);
        assertEquals(ErrorCode.IDENTITY_DUPLICATE.getCode(),
                objectMapper.readTree(verifyB.getBody()).path("code").asText());

        // 7. 账号 A 预约 10003 成功 -> 状态 CAS 进入 3(已排期) -> 我的预约列表可见 -> 查询计数为 1 -> 重复预约返回 A0106
        ResponseEntity<String> myBefore = restTemplate.exchange(
                "/api/v1/reservations/10003/me", HttpMethod.GET, new HttpEntity<>(authAHeaders), String.class);
        JsonNode myBeforeData = objectMapper.readTree(myBefore.getBody()).path("data");
        assertFalse(myBeforeData.path("reserved").asBoolean());
        assertTrue(myBeforeData.path("realNameVerified").asBoolean());
        assertEquals("RESERVE", myBeforeData.path("nextAction").asText());
        assertEquals(1, myBeforeData.path("saleConfigVersion").asInt());

        ResponseEntity<String> rsvA = postJson("/api/v1/reservations", "{\"showId\":10003}", tokenA);
        JsonNode rsvAJson = objectMapper.readTree(rsvA.getBody());
        assertEquals("00000", rsvAJson.path("code").asText());
        assertTrue(rsvAJson.path("data").path("reservationId").isTextual());
        assertTrue(rsvAJson.path("data").path("remindScheduled").asBoolean());
        assertEquals(1, rsvAJson.path("data").path("saleConfigVersion").asInt());
        long reservationId = Long.parseLong(rsvAJson.path("data").path("reservationId").asText());

        ResponseEntity<String> myAfter = restTemplate.exchange(
                "/api/v1/reservations/10003/me", HttpMethod.GET, new HttpEntity<>(authAHeaders), String.class);
        JsonNode myAfterData = objectMapper.readTree(myAfter.getBody()).path("data");
        assertTrue(myAfterData.path("reserved").asBoolean());
        assertEquals(String.valueOf(reservationId), myAfterData.path("reservationId").asText());
        assertEquals("SCHEDULED", myAfterData.path("remindStatusLabel").asText());
        assertEquals("WAIT_FOR_SALE", myAfterData.path("nextAction").asText());

        ResponseEntity<String> myListResp = restTemplate.exchange(
                "/api/v1/reservations/me", HttpMethod.GET, new HttpEntity<>(authAHeaders), String.class);
        JsonNode myListData = objectMapper.readTree(myListResp.getBody()).path("data");
        assertEquals(1, myListData.size());
        assertEquals("10003", myListData.get(0).path("showId").asText());

        ResponseEntity<String> countResp = restTemplate.getForEntity("/api/v1/reservations/10003/count", String.class);
        JsonNode countData = objectMapper.readTree(countResp.getBody()).path("data");
        assertEquals("1", countData.path("count").asText());
        assertTrue(countData.path("countAvailable").asBoolean());
        assertFalse(countData.path("stale").asBoolean());
        assertTrue(countData.path("asOfEpochMs").asLong() > 0L);

        ResponseEntity<String> rsvDup = postJson("/api/v1/reservations", "{\"showId\":10003}", tokenA);
        assertEquals(ErrorCode.RESERVATION_DUPLICATE.getCode(),
                objectMapper.readTree(rsvDup.getBody()).path("code").asText());

        // 8. 校验发往 RocketMQ 5.x 的延迟事件：落库后先处于 3(已排期) -> 消费两次经 CAS 3->1 幂等 -> 同事务落 1 条站内通知并支持已读
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Message<ShowRemindEvent>> msgCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rocketMQTemplate).syncSendDeliverTimeMills(eq(MqTopics.SHOW_REMIND), msgCaptor.capture(), anyLong());
        ShowRemindEvent event = msgCaptor.getValue().getPayload();
        assertEquals(reservationId, event.getReservationId());
        assertEquals(1, event.getSaleConfigVersion());

        ReservationDO beforeConsume = reservationMapper.selectById(reservationId);
        assertEquals(ReservationDO.REMIND_SCHEDULED, beforeConsume.getRemindStatus());

        showRemindConsumer.onMessage(event);
        showRemindConsumer.onMessage(event);
        assertEquals(0, reservationMapper.casMarkFailedWithRetry(reservationId, java.time.LocalDateTime.now().plusSeconds(30)),
                "Terminal status 1 must NEVER regress to 2");

        ReservationDO afterConsume = reservationMapper.selectById(reservationId);
        assertEquals(ReservationDO.REMIND_SENT, afterConsume.getRemindStatus());

        ResponseEntity<String> notifResp = restTemplate.exchange(
                "/api/v1/notifications/me", HttpMethod.GET, new HttpEntity<>(authAHeaders), String.class);
        JsonNode notifList = objectMapper.readTree(notifResp.getBody()).path("data");
        assertEquals(1, notifList.size(), "Duplicate remind consumption must produce exactly 1 notification");
        String notifId = notifList.get(0).path("notificationId").asText();
        assertFalse(notifList.get(0).path("read").asBoolean());
        assertEquals("/shows/10003", notifList.get(0).path("actionUrl").asText());

        ResponseEntity<String> readResp = restTemplate.exchange(
                "/api/v1/notifications/" + notifId + "/read", HttpMethod.POST, new HttpEntity<>(authAHeaders), String.class);
        assertTrue(objectMapper.readTree(readResp.getBody()).path("data").path("read").asBoolean());

        // 8B. 主办方改期联动（v1 -> v2）：Phase 2 改期自动本地联动重排预约与落盘 SCHEDULE_CHANGED 通知；
        //     二次调用 /internal/reservations/shows/10003/reschedule 幂等不重复发通知
        HttpHeaders internalHeaders = new HttpHeaders();
        internalHeaders.setContentType(MediaType.APPLICATION_JSON);
        internalHeaders.set(AuthHeaders.X_INTERNAL_GATEWAY_SECRET, "monolith-it-internal-5c4b3a2f1e");
        String newStartStr = java.time.LocalDateTime.now().plusHours(24).withNano(0).toString();
        String newEndStr = java.time.LocalDateTime.now().plusDays(20).withNano(0).toString();
        ResponseEntity<String> scheduleResp = restTemplate.exchange(
                "/internal/shows/10003/schedule",
                HttpMethod.PUT,
                new HttpEntity<>("{\"saleStartTime\":\"" + newStartStr + "\",\"saleEndTime\":\"" + newEndStr + "\"}", internalHeaders),
                String.class
        );
        assertEquals(HttpStatus.OK, scheduleResp.getStatusCode());
        assertEquals(2, objectMapper.readTree(scheduleResp.getBody()).path("data").path("saleConfigVersion").asInt());

        // 改期完成后用户无需额外手工触发即可立即收到 SCHEDULE_CHANGED 站内变更通知
        ResponseEntity<String> notifAfterSchedule = restTemplate.exchange(
                "/api/v1/notifications/me", HttpMethod.GET, new HttpEntity<>(authAHeaders), String.class);
        JsonNode notifListAfter = objectMapper.readTree(notifAfterSchedule.getBody()).path("data");
        assertEquals(2, notifListAfter.size());
        assertEquals("SCHEDULE_CHANGED", notifListAfter.get(0).path("notificationType").asText());
        assertEquals(2, notifListAfter.get(0).path("saleConfigVersion").asInt());

        // 幂等重放 /internal/reservations/shows/10003/reschedule：版本已同步至 v2，不得重复重置或重复发通知
        ResponseEntity<String> rescheduleResp = restTemplate.exchange(
                "/internal/reservations/shows/10003/reschedule",
                HttpMethod.POST,
                new HttpEntity<>("", internalHeaders),
                String.class
        );
        assertEquals(HttpStatus.OK, rescheduleResp.getStatusCode());
        JsonNode reschedData = objectMapper.readTree(rescheduleResp.getBody()).path("data");
        assertEquals(2, reschedData.path("saleConfigVersion").asInt());
        assertEquals(0, reschedData.path("resetReservationCount").asInt());
        assertEquals(0, reschedData.path("scheduleNotifiedCount").asInt());

        ResponseEntity<String> notifAfterReplay = restTemplate.exchange(
                "/api/v1/notifications/me", HttpMethod.GET, new HttpEntity<>(authAHeaders), String.class);
        assertEquals(2, objectMapper.readTree(notifAfterReplay.getBody()).path("data").size());

        // 模拟旧版 v1 消息晚到 -> 必须被版本守卫丢弃，不把 v2 预约误标成已送达
        showRemindConsumer.onMessage(event);
        assertEquals(ReservationDO.REMIND_SCHEDULED, reservationMapper.selectById(reservationId).getRemindStatus());
        ResponseEntity<String> myAfterReschedule = restTemplate.exchange(
                "/api/v1/reservations/10003/me", HttpMethod.GET, new HttpEntity<>(authAHeaders), String.class);
        assertEquals(2, objectMapper.readTree(myAfterReschedule.getBody()).path("data").path("saleConfigVersion").asInt());

        // 9. 主动登出 -> 复用旧 JWT 请求实名查询返回 401 + A0102
        ResponseEntity<String> logoutResp = postJson("/api/v1/auth/logout", "", tokenA);
        assertEquals(HttpStatus.OK, logoutResp.getStatusCode());

        HttpHeaders revokedHeaders = new HttpHeaders();
        revokedHeaders.set(AuthHeaders.AUTHORIZATION, AuthHeaders.BEARER_PREFIX + tokenA);
        ResponseEntity<String> afterLogoutResp = restTemplate.exchange(
                "/api/v1/users/identity", HttpMethod.GET, new HttpEntity<>(revokedHeaders), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, afterLogoutResp.getStatusCode());
        assertEquals(ErrorCode.TOKEN_REVOKED.getCode(),
                objectMapper.readTree(afterLogoutResp.getBody()).path("code").asText());
    }

    private ResponseEntity<String> postJson(String url, String body, String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearerToken != null) {
            headers.set(AuthHeaders.AUTHORIZATION, AuthHeaders.BEARER_PREFIX + bearerToken);
        }
        return restTemplate.postForEntity(url, new HttpEntity<>(body, headers), String.class);
    }

    private String buildValidIdCard(String first17) {
        return first17 + IdCardValidator.computeCheckDigit(first17);
    }
}
