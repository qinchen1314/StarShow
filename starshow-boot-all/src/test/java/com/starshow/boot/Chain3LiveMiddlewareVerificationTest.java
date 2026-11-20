package com.starshow.boot;

import com.starshow.api.event.OrderCreateEvent;
import com.starshow.common.constant.RedisKeys;
import com.starshow.common.exception.BizException;
import com.starshow.common.id.GeneSnowflakeIdGenerator;
import com.starshow.common.result.ErrorCode;
import com.starshow.order.config.SeckillProperties;
import com.starshow.order.domain.entity.OrderActionDO;
import com.starshow.order.domain.entity.OrderDO;
import com.starshow.order.dto.SeckillOrderRequest;
import com.starshow.order.dto.SeckillTokenRequest;
import com.starshow.order.lua.OrderRedisScripts;
import com.starshow.order.mapper.OrderActionMapper;
import com.starshow.order.mapper.OrderInventoryMapper;
import com.starshow.order.mapper.OrderShardMapper;
import com.starshow.order.mapper.OrderShardTableResolver;
import com.starshow.order.mq.OrderCreateConsumer;
import com.starshow.order.mq.OrderCreateTransactionListener;
import com.starshow.order.mq.OrderTimeoutDispatchScheduler;
import com.starshow.order.mq.PendingDeductReconcileScheduler;
import com.starshow.order.service.OrderPersistenceService;
import com.starshow.order.service.OrderQueryService;
import com.starshow.order.service.SeckillOrderService;
import com.starshow.order.service.SeckillRequestFingerprint;
import com.starshow.order.service.SeckillTokenService;
import com.starshow.order.vo.CurrentShowOrderVO;
import com.starshow.order.vo.OrderDetailVO;
import com.starshow.order.vo.OrderStatusVO;
import com.starshow.order.vo.SeckillOrderVO;
import com.starshow.order.vo.SeckillTokenVO;
import com.starshow.show.domain.entity.SeatDO;
import com.starshow.show.domain.entity.ShowDO;
import com.starshow.show.domain.entity.TicketTierDO;
import com.starshow.show.mapper.SeatMapper;
import com.starshow.show.mapper.ShowMapper;
import com.starshow.show.mapper.TicketTierMapper;
import com.starshow.show.service.ShowPreheatService;
import com.starshow.user.domain.entity.UserIdentityDO;
import com.starshow.user.mapper.UserIdentityMapper;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 链路三（Phase 3）真实 Docker 中间件（MySQL 8 + Redis 7 + RocketMQ 5.3）Prove-It 实证门禁套件：
 * 仅在显式开启 -Dstarshow.live.enabled=true 且绑定隔离 MySQL 库 + 非 0 Redis DB 时运行！
 *
 * 完整覆盖 L2 与 L3 全部硬性剧本：
 * 1. L2 四策略对比（V1/V2/V3/V4）：200 独立实名用户并发抢 20 张票，零超卖、零重复单、V4 排空与耗时观测；
 * 2. L2 自主选座连座：50 并发抢同一双座连座组（仅 1 单占 2 座，49 单 A0204）+ 非法/跨走道/重复/越界 0 变更拦截；
 * 3. L3 剧本①②：COMMIT 丢包 -> Broker 真实回查 deduct_log 自动补单；回查先立墓碑 -> 迟到 Lua 返回 -3 零悬挂；
 * 4. L3 剧本③：消费 3 次重投 + CLOSE_CHECK 延迟关单投递幂等唯一；
 * 5. L3 剧本④：首请丢响应重放找回 + 换设备 /orders/current 找回 + A0307 + A0308 + 售罄保留 Token 换档重试；
 * 6. L3 剧本⑤：悬挂巡检 vs 延迟创单消费并发争夺 CREATE_DECISION 行锁，严格二选一零漂移。
 */
@EnabledIfSystemProperty(named = "starshow.live.enabled", matches = "true")
@SpringBootTest(
        classes = StarShowBootAllApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "starshow.reservation.recovery-enabled=false",
                "starshow.reservation.mq-enabled=false",
                "starshow.show.task-recovery-enabled=false",
                "starshow.show.lifecycle-sync-enabled=false",
                "starshow.show.bloom-enabled=false",
                "starshow.show.max-tickets-per-order=2",
                "starshow.seckill.mq-enabled=true",
                "starshow.seckill.timeout-dispatch-enabled=false",
                "starshow.seckill.pending-reconcile-enabled=false",
                "starshow.seckill.per-tier-consumer-concurrency=2",
                "knife4j.enable=false"
        }
)
@ActiveProfiles("monolith")
class Chain3LiveMiddlewareVerificationTest {

    private static final Logger log = LoggerFactory.getLogger(Chain3LiveMiddlewareVerificationTest.class);

    @Autowired private ShowMapper showMapper;
    @Autowired private TicketTierMapper ticketTierMapper;
    @Autowired private SeatMapper seatMapper;
    @Autowired private UserIdentityMapper userIdentityMapper;
    @Autowired private ShowPreheatService showPreheatService;
    @Autowired private SeckillTokenService seckillTokenService;
    @Autowired private SeckillOrderService seckillOrderService;
    @Autowired private OrderQueryService orderQueryService;
    @Autowired private OrderShardMapper orderShardMapper;
    @Autowired private OrderActionMapper orderActionMapper;
    @Autowired private OrderInventoryMapper orderInventoryMapper;
    @Autowired private OrderRedisScripts orderRedisScripts;
    @Autowired private OrderCreateConsumer orderCreateConsumer;
    @Autowired private OrderCreateTransactionListener txListener;
    @Autowired private OrderTimeoutDispatchScheduler timeoutDispatchScheduler;
    @Autowired private PendingDeductReconcileScheduler reconcileScheduler;
    @Autowired private GeneSnowflakeIdGenerator idGenerator;
    @Autowired private SeckillProperties seckillProperties;
    @Autowired private StringRedisTemplate stringRedisTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    // -------------------------------------------------------------------------
    // 1. L2：V1 / V2 / V3 / V4 四策略分别实跑 200 并发抢 20 张票（零超卖、零重复单）
    // -------------------------------------------------------------------------

    @Test
    void l2_shouldGuaranteeZeroOversellAndZeroDuplicatesAcrossV1ToV4Under200ConcurrencyFor20Tickets() throws Exception {
        String[] strategies = {"V1", "V2", "V3", "V4"};
        long baseShowId = 93100L;
        long baseTierId = 94100L;
        int concurrency = 200;
        int initialStock = 20;

        for (int idx = 0; idx < strategies.length; idx++) {
            String strategy = strategies[idx];
            long showId = baseShowId + idx;
            long tierId = baseTierId + idx;
            long userBase = 830000L + idx * 1000L;

            seckillProperties.setStrategy(strategy);
            prepareAutoShow(showId, tierId, initialStock, 88000L, "V4".equals(strategy));

            // 预发 200 个独立实名用户的 seckillToken
            List<String> tokens = new ArrayList<>(concurrency);
            for (int i = 0; i < concurrency; i++) {
                long userId = userBase + i;
                ensureVerifiedUser(userId);
                SeckillTokenVO tok = seckillTokenService.issueToken(userId,
                        SeckillTokenRequest.builder().showId(showId).saleConfigVersion(1).build());
                tokens.add(tok.getSeckillToken());
            }

            // V1~V3 写一个哨兵检查它们绝不触碰 V4 Redis 实时库存键
            if (!"V4".equals(strategy)) {
                stringRedisTemplate.delete(RedisKeys.stock(showId, tierId));
            }

            ExecutorService pool = Executors.newFixedThreadPool(concurrency);
            CyclicBarrier barrier = new CyclicBarrier(concurrency);
            AtomicInteger acceptedCount = new AtomicInteger();
            AtomicInteger stockShortageCount = new AtomicInteger();
            AtomicInteger lockBusyCount = new AtomicInteger();
            Set<Long> unexpectedErrors = ConcurrentHashMap.newKeySet();

            long apiStartNs = System.nanoTime();
            List<Future<Void>> futures = new ArrayList<>(concurrency);
            for (int i = 0; i < concurrency; i++) {
                final int uIdx = i;
                final long userId = userBase + uIdx;
                final String tok = tokens.get(uIdx);
                futures.add(pool.submit(() -> {
                    barrier.await(15, TimeUnit.SECONDS);
                    SeckillOrderRequest req = SeckillOrderRequest.builder()
                            .showId(showId)
                            .tierId(tierId)
                            .qty(1)
                            .saleConfigVersion(1)
                            .expectedUnitPriceCent(88000L)
                            .seckillToken(tok)
                            .clientRequestId("l2-" + strategy + "-" + uIdx + "-" + UUID.randomUUID().toString().substring(0, 8))
                            .build();
                    try {
                        SeckillOrderVO vo = seckillOrderService.placeOrderWithStrategyOverride(userId, req, strategy, false);
                        assertNotNull(vo.getOrderNo());
                        acceptedCount.incrementAndGet();
                    } catch (BizException ex) {
                        if (ex.getErrorCode() == ErrorCode.STOCK_NOT_ENOUGH) {
                            stockShortageCount.incrementAndGet();
                        } else if (ex.getErrorCode() == ErrorCode.LOCK_ACQUIRE_FAILED) {
                            lockBusyCount.incrementAndGet();
                        } else {
                            unexpectedErrors.add(userId);
                            log.error("Unexpected BizException in {} userId={}: {} {}", strategy, userId, ex.getErrorCode(), ex.getMessage());
                        }
                    }
                    return null;
                }));
            }
            for (Future<Void> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
            long apiElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - apiStartNs);
            pool.shutdownNow();

            assertTrue(unexpectedErrors.isEmpty(), strategy + " had unexpected errors: " + unexpectedErrors);
            assertEquals(initialStock, acceptedCount.get(),
                    strategy + " accepted orders must strictly equal initialStock=" + initialStock);

            long drainElapsedMs = apiElapsedMs;
            if ("V4".equals(strategy)) {
                // 等待 RocketMQ 消费端全部排空落单
                waitUntil(25000L, () -> sumActiveQtyAcrossShards(showId, tierId) == initialStock
                        && zcard(RedisKeys.deductPendingZset(showId)) == 0L);
                drainElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - apiStartNs);

                String redisStock = stringRedisTemplate.opsForValue().get(RedisKeys.stock(showId, tierId));
                assertEquals("0", redisStock, "V4 Redis stock must be 0 after 200->20 seckill");
                assertEquals(0L, zcard(RedisKeys.deductPendingZset(showId)), "V4 deduct_pending ZSET must drain to 0");
            } else {
                // V1~V3 严禁污染 V4 Redis 实时库存
                assertNull(stringRedisTemplate.opsForValue().get(RedisKeys.stock(showId, tierId)),
                        strategy + " must NOT write V4 Redis stock key");
            }

            int dbActiveQty = sumActiveQtyAcrossShards(showId, tierId);
            int dbActiveOrders = countActiveOrdersAcrossShards(showId);
            int maxOrdersPerUser = queryMaxActiveOrdersPerUser(showId);
            OrderInventoryMapper.TierStockRow tierRow = orderInventoryMapper.selectTierStock(showId, tierId);

            assertEquals(initialStock, dbActiveQty, strategy + " DB active qty must equal 20 (0 oversell)");
            assertEquals(initialStock, dbActiveOrders, strategy + " DB active orders must equal 20");
            assertEquals(0, tierRow.getAvailableStock(), strategy + " DB available_stock must be 0");
            assertEquals(1, maxOrdersPerUser, strategy + " max active orders per (user_id, show_id) must be 1");

            log.info("[L2_RESULT] strategy={}, concurrency={}, stock={}, accepted={}, dbOrders={}, dbRemain={}, shortage={}, lockBusy={}, apiMs={}ms, e2eDrainMs={}ms",
                    strategy, concurrency, initialStock, acceptedCount.get(), dbActiveOrders,
                    tierRow.getAvailableStock(), stockShortageCount.get(), lockBusyCount.get(), apiElapsedMs, drainElapsedMs);
        }
        seckillProperties.setStrategy("V4");
    }

    // -------------------------------------------------------------------------
    // 2. L2 自主选座：50 并发抢同一双座连座组 + 跨走道/重复/错误票档/越界全拦截
    // -------------------------------------------------------------------------

    @Test
    void l2_shouldLockAdjacentPairAtomicallyUnder50ConcurrencyAndRejectInvalidOrAcrossAisleSeats() throws Exception {
        long showId = 93201L;
        long tierId = 94201L;
        long otherTierId = 94202L;
        String zoneId = "HALL_A";
        seckillProperties.setStrategy("V4");

        prepareManualSeatShow(showId, tierId, otherTierId, zoneId);

        long probeUser = 840000L;
        ensureVerifiedUser(probeUser);
        String probeToken = seckillTokenService.issueToken(probeUser,
                SeckillTokenRequest.builder().showId(showId).saleConfigVersion(1).build()).getSeckillToken();

        String stockBefore = stringRedisTemplate.opsForValue().get(RedisKeys.stock(showId, tierId));
        assertEquals("100", stockBefore);

        // 2a. 传重复索引 [3, 3] -> 拒绝
        BizException dupEx = assertThrows(BizException.class, () -> seckillOrderService.placeOrder(probeUser,
                manualSeatReq(showId, tierId, zoneId, List.of(3, 3), probeToken, "seat-invalid-dup-1")));
        assertEquals(ErrorCode.INVALID_PARAM, dupEx.getErrorCode());

        // 2b. 跨越走道 [2, 3] (R1-L col3 -> R1-M col4) -> 拒绝
        BizException aisleEx = assertThrows(BizException.class, () -> seckillOrderService.placeOrder(probeUser,
                manualSeatReq(showId, tierId, zoneId, List.of(2, 3), probeToken, "seat-invalid-aisle-1")));
        assertEquals(ErrorCode.INVALID_PARAM, aisleEx.getErrorCode());

        // 2c. 错误票档 (otherTierId 不包含 HALL_A 座位，传其真实票价 68000 确保真正命中座位归属校验) -> 拒绝
        SeckillOrderRequest wrongTierReq = manualSeatReq(showId, otherTierId, zoneId, List.of(3, 4), probeToken, "seat-invalid-tier-1");
        wrongTierReq.setExpectedUnitPriceCent(68000L);
        BizException wrongTierEx = assertThrows(BizException.class, () -> seckillOrderService.placeOrder(probeUser, wrongTierReq));
        assertEquals(ErrorCode.INVALID_PARAM, wrongTierEx.getErrorCode());

        // 2d. 越界偏移 [105, 106] -> 拒绝
        BizException oobEx = assertThrows(BizException.class, () -> seckillOrderService.placeOrder(probeUser,
                manualSeatReq(showId, tierId, zoneId, List.of(105, 106), probeToken, "seat-invalid-oob-1")));
        assertEquals(ErrorCode.INVALID_PARAM, oobEx.getErrorCode());

        // 校验非法请求后库存与位图 0 变化
        assertEquals("100", stringRedisTemplate.opsForValue().get(RedisKeys.stock(showId, tierId)));
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.opsForValue().getBit(RedisKeys.seatBitmap(showId, zoneId), 3)));
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.opsForValue().getBit(RedisKeys.seatBitmap(showId, zoneId), 4)));

        // 2e. 50 并发抢同一双座连座组 [3, 4] (同排同段 R1-M)
        int concurrency = 50;
        long userBase = 841000L;
        List<String> tokens = new ArrayList<>(concurrency);
        for (int i = 0; i < concurrency; i++) {
            long uid = userBase + i;
            ensureVerifiedUser(uid);
            tokens.add(seckillTokenService.issueToken(uid,
                    SeckillTokenRequest.builder().showId(showId).saleConfigVersion(1).build()).getSeckillToken());
        }

        ExecutorService pool = Executors.newFixedThreadPool(50);
        CyclicBarrier barrier = new CyclicBarrier(concurrency);
        AtomicInteger winCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();
        Set<Long> winners = ConcurrentHashMap.newKeySet();

        List<Future<Void>> futures = new ArrayList<>(concurrency);
        for (int i = 0; i < concurrency; i++) {
            final int uIdx = i;
            final long uid = userBase + uIdx;
            final String tok = tokens.get(uIdx);
            futures.add(pool.submit(() -> {
                barrier.await(15, TimeUnit.SECONDS);
                try {
                    SeckillOrderVO vo = seckillOrderService.placeOrder(uid,
                            manualSeatReq(showId, tierId, zoneId, List.of(3, 4), tok, "l2-pair-" + uIdx + "-" + UUID.randomUUID().toString().substring(0, 6)));
                    winCount.incrementAndGet();
                    winners.add(vo.getOrderNo());
                } catch (BizException ex) {
                    assertEquals(ErrorCode.SEAT_CONFLICT, ex.getErrorCode(), "Expected A0204 but got " + ex.getErrorCode());
                    assertNotNull(ex.getData(), "A0204 must carry conflict seat payload");
                    conflictCount.incrementAndGet();
                }
                return null;
            }));
        }
        for (Future<Void> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertEquals(1, winCount.get(), "Exactly 1 order must win the pair [3,4]");
        assertEquals(49, conflictCount.get(), "All other 49 concurrent requests must get A0204 conflict");

        long winOrderNo = winners.iterator().next();
        waitUntil(15000L, () -> orderShardMapper.selectByOrderNo(OrderShardTableResolver.tableForOrderNo(winOrderNo), winOrderNo) != null);

        OrderDO persisted = orderShardMapper.selectByOrderNo(OrderShardTableResolver.tableForOrderNo(winOrderNo), winOrderNo);
        assertEquals("3,4", persisted.getSeatIndices());
        assertEquals(2, persisted.getQty());
        assertEquals("98", stringRedisTemplate.opsForValue().get(RedisKeys.stock(showId, tierId)));
        assertEquals(98, orderInventoryMapper.selectTierStock(showId, tierId).getAvailableStock());
        assertTrue(Boolean.TRUE.equals(stringRedisTemplate.opsForValue().getBit(RedisKeys.seatBitmap(showId, zoneId), 3)));
        assertTrue(Boolean.TRUE.equals(stringRedisTemplate.opsForValue().getBit(RedisKeys.seatBitmap(showId, zoneId), 4)));
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.opsForValue().getBit(RedisKeys.seatBitmap(showId, zoneId), 2)));
        assertFalse(Boolean.TRUE.equals(stringRedisTemplate.opsForValue().getBit(RedisKeys.seatBitmap(showId, zoneId), 5)));
    }

    // -------------------------------------------------------------------------
    // 3. L3 剧本①②：COMMIT 丢包 -> Broker 回查补单；回查先写墓碑 -> 迟到 Lua -3 零悬挂
    // -------------------------------------------------------------------------

    @Test
    void l3_scenarios1And2_txCommitDropRecoveryAndTombstoneAntiHang() throws Exception {
        long showId = 93301L;
        long tierId = 94301L;
        seckillProperties.setStrategy("V4");
        prepareAutoShow(showId, tierId, 10, 68000L, true);

        // 剧本①：Lua 预扣成功后强制返回 UNKNOWN 模拟 COMMIT 丢包 -> 真实 Broker 触发 checkLocalTransaction -> 补单
        long user1 = 850001L;
        ensureVerifiedUser(user1);
        String tok1 = seckillTokenService.issueToken(user1,
                SeckillTokenRequest.builder().showId(showId).saleConfigVersion(1).build()).getSeckillToken();

        SeckillOrderRequest req1 = SeckillOrderRequest.builder()
                .showId(showId).tierId(tierId).qty(1).saleConfigVersion(1)
                .expectedUnitPriceCent(68000L).seckillToken(tok1).clientRequestId("l3-tx-drop-" + UUID.randomUUID().toString().substring(0, 8))
                .build();

        BizException sendUnknownEx = assertThrows(BizException.class,
                () -> seckillOrderService.placeOrderWithStrategyOverride(user1, req1, "V4", true));
        assertEquals(ErrorCode.MQ_SEND_FAILED, sendUnknownEx.getErrorCode());
        assertEquals(503, sendUnknownEx.getHttpStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> unknownData = (Map<String, Object>) sendUnknownEx.getData();
        long boundOrderNo = Long.parseLong(String.valueOf(unknownData.get("orderNo")));

        // 确认 deduct_log 已存在且等待 RocketMQ Broker 真实回查自动 COMMIT 并消费落单（RocketMQ 默认首次回查约 6s~60s，等待上限 75s）
        assertNotNull(orderRedisScripts.readDeductLog(showId, boundOrderNo));
        waitUntil(75000L, () -> orderShardMapper.selectByOrderNo(OrderShardTableResolver.tableForOrderNo(boundOrderNo), boundOrderNo) != null);

        OrderDO recovered = orderShardMapper.selectByOrderNo(OrderShardTableResolver.tableForOrderNo(boundOrderNo), boundOrderNo);
        assertNotNull(recovered, "Broker checkLocalTransaction must COMMIT and persist order after COMMIT drop");
        assertEquals("WAIT_PAY", recovered.getStatus());
        assertEquals("9", stringRedisTemplate.opsForValue().get(RedisKeys.stock(showId, tierId)));
        assertEquals(9, orderInventoryMapper.selectTierStock(showId, tierId).getAvailableStock());

        // 剧本②：回查先于延迟 Lua 到达 -> 原子写入 rollback_done 墓碑 -> 迟到 Lua 返回 -3 零悬挂
        long user2 = 850002L;
        long lateOrderNo = idGenerator.nextOrderId(user2);
        RocketMQLocalTransactionState checkState = txListener.checkByShowAndOrder(showId, lateOrderNo);
        assertEquals(RocketMQLocalTransactionState.ROLLBACK, checkState);
        assertTrue(orderRedisScripts.hasRollbackDone(showId, lateOrderNo), "Tombstone must be written atomically by tx check");

        long nowMs = System.currentTimeMillis();
        long lateLuaCode = orderRedisScripts.executeAutoDeduct(showId, tierId, user2, 1, lateOrderNo, nowMs / 1000L, 86400L, 1, nowMs);
        assertEquals(-3L, lateLuaCode, "Delayed Lua deduct after tombstone must return -3 (A0306)");
        assertEquals("9", stringRedisTemplate.opsForValue().get(RedisKeys.stock(showId, tierId)), "Stock must remain untouched");
        assertNull(orderRedisScripts.readDeductLog(showId, lateOrderNo), "No deduct_log should be written for tombstoned order");
    }

    // -------------------------------------------------------------------------
    // 4. L3 剧本③④：消费 3x 重投 + 超时延迟投递幂等 + 首请重放/换设备找回/A0307/A0308/售罄换档保留 Token
    // -------------------------------------------------------------------------

    @Test
    void l3_scenarios3And4_consumerDuplicate3xAndRecoveryReplayAndTokenReuse() throws Exception {
        long showId = 93401L;
        long tierSoldOut = 94401L;
        long tierBackup = 94402L;
        seckillProperties.setStrategy("V4");
        prepareTwoTierAutoShow(showId, tierSoldOut, 0, 58000L, tierBackup, 5, 98000L);

        // 4a. 售罄快速失败保留 Token -> 无需重新申请凭证直接切换同场另一票档下单成功
        long userA = 860001L;
        ensureVerifiedUser(userA);
        String tokenA = seckillTokenService.issueToken(userA,
                SeckillTokenRequest.builder().showId(showId).saleConfigVersion(1).build()).getSeckillToken();

        SeckillOrderRequest reqSoldOut = SeckillOrderRequest.builder()
                .showId(showId).tierId(tierSoldOut).qty(1).saleConfigVersion(1)
                .expectedUnitPriceCent(58000L).seckillToken(tokenA).clientRequestId("l3-soldout-switch-1")
                .build();
        BizException soldOutEx = assertThrows(BizException.class, () -> seckillOrderService.placeOrder(userA, reqSoldOut));
        assertEquals(ErrorCode.STOCK_NOT_ENOUGH, soldOutEx.getErrorCode());

        // 价格变动拦截 -> A0308 + 当前权威价
        SeckillOrderRequest wrongPriceReq = SeckillOrderRequest.builder()
                .showId(showId).tierId(tierBackup).qty(1).saleConfigVersion(1)
                .expectedUnitPriceCent(88000L).seckillToken(tokenA).clientRequestId("l3-wrong-price-1")
                .build();
        BizException priceEx = assertThrows(BizException.class, () -> seckillOrderService.placeOrder(userA, wrongPriceReq));
        assertEquals(ErrorCode.PRICE_CHANGED, priceEx.getErrorCode());

        // 复用同一 tokenA 直接抢备用票档 tierBackup
        String reqIdA = "l3-replay-recover-01";
        SeckillOrderRequest reqBackup = SeckillOrderRequest.builder()
                .showId(showId).tierId(tierBackup).qty(1).saleConfigVersion(1)
                .expectedUnitPriceCent(98000L).seckillToken(tokenA).clientRequestId(reqIdA)
                .build();
        SeckillOrderVO accepted = seckillOrderService.placeOrder(userA, reqBackup);
        assertEquals("PENDING_CONFIRM", accepted.getStatus());
        long orderNoA = accepted.getOrderNo();

        // 首次响应丢失重放：即使换了无效 Token，同 clientRequestId 摘要一致精准找回原单号
        SeckillOrderRequest replayReq = SeckillOrderRequest.builder()
                .showId(showId).tierId(tierBackup).qty(1).saleConfigVersion(1)
                .expectedUnitPriceCent(98000L).seckillToken("invalid-expired-token").clientRequestId(reqIdA)
                .build();
        SeckillOrderVO replayed = seckillOrderService.placeOrder(userA, replayReq);
        assertEquals(orderNoA, replayed.getOrderNo());

        // 篡改参数用同 clientRequestId -> A0307
        SeckillOrderRequest tampered = SeckillOrderRequest.builder()
                .showId(showId).tierId(tierBackup).qty(2).saleConfigVersion(1)
                .expectedUnitPriceCent(98000L).seckillToken("any").clientRequestId(reqIdA)
                .build();
        BizException conflictEx = assertThrows(BizException.class, () -> seckillOrderService.placeOrder(userA, tampered));
        assertEquals(ErrorCode.REQUEST_CONFLICT, conflictEx.getErrorCode());

        // 等待 V4 消费端落库完成
        waitUntil(15000L, () -> orderShardMapper.selectByOrderNo(OrderShardTableResolver.tableForOrderNo(orderNoA), orderNoA) != null);

        // 换设备恢复 GET /api/v1/orders/current?showId=...
        CurrentShowOrderVO current = orderQueryService.getCurrentShowOrder(userA, showId);
        assertTrue(current.isHasOrder());
        assertEquals(orderNoA, current.getOrderNo());
        assertEquals("WAIT_PAY", current.getStatus());

        // 跨用户访问拒绝 A0304
        BizException otherUserEx = assertThrows(BizException.class, () -> orderQueryService.getOrderDetail(userA + 99, orderNoA));
        assertEquals(ErrorCode.ORDER_NOT_FOUND, otherUserEx.getErrorCode());

        // 剧本③：对另一笔独立事件重复消费 3 次 + 验证 CLOSE_CHECK 唯一且补发幂等
        long userB = 860002L;
        ensureVerifiedUser(userB);
        long orderNoB = idGenerator.nextOrderId(userB);
        OrderCreateEvent dupEvent = OrderCreateEvent.builder()
                .msgKey("order-create:" + orderNoB)
                .orderNo(orderNoB)
                .userId(userB)
                .showId(showId)
                .tierId(tierBackup)
                .zoneId("Z_BAK")
                .qty(1)
                .amountCent(98000L)
                .deductEpochSec(System.currentTimeMillis() / 1000L)
                .clientRequestId("l3-dup3x-" + UUID.randomUUID().toString().substring(0, 8))
                .requestFingerprint("fp-dup3x-" + orderNoB)
                .saleConfigVersion(1)
                .strategy("V4")
                .traceId("trace-dup3x")
                .build();

        OrderPersistenceService.V4ConsumeOutcome o1 = orderCreateConsumer.handleEvent(dupEvent);
        OrderPersistenceService.V4ConsumeOutcome o2 = orderCreateConsumer.handleEvent(dupEvent);
        OrderPersistenceService.V4ConsumeOutcome o3 = orderCreateConsumer.handleEvent(dupEvent);

        assertEquals(OrderPersistenceService.V4ConsumeStatus.APPLIED, o1.status());
        assertEquals(OrderPersistenceService.V4ConsumeStatus.ALREADY_APPLIED, o2.status());
        assertEquals(OrderPersistenceService.V4ConsumeStatus.ALREADY_APPLIED, o3.status());
        assertEquals(3, orderInventoryMapper.selectTierStock(showId, tierBackup).getAvailableStock(),
                "DB stock should decrease only twice (orderA + orderB), 3x duplicate delivery must deduct once");

        OrderActionDO closeAction = orderActionMapper.selectByOrderAndType(orderNoB, OrderActionDO.TYPE_CLOSE_CHECK);
        assertNotNull(closeAction);
        assertEquals(OrderActionDO.STATUS_SENT, closeAction.getStatus(), "CLOSE_CHECK must be dispatched to TOPIC_ORDER_TIMEOUT");

        // 模拟落库提交后进程崩溃导致 CLOSE_CHECK 仍为 PENDING -> 调度器可靠补发且幂等
        jdbcTemplate.update("UPDATE t_order_action SET status = 'PENDING', next_retry_at = NOW() WHERE action_id = ?", closeAction.getActionId());
        int dispatched = timeoutDispatchScheduler.dispatchDueCloseChecks(50);
        assertTrue(dispatched >= 1);
        assertEquals(OrderActionDO.STATUS_SENT,
                orderActionMapper.selectByOrderAndType(orderNoB, OrderActionDO.TYPE_CLOSE_CHECK).getStatus());
        assertEquals(0, timeoutDispatchScheduler.dispatchDueCloseChecks(50), "Already SENT CLOSE_CHECK must not re-dispatch");

        // 详情与状态校验
        OrderDetailVO detailB = orderQueryService.getOrderDetail(userB, orderNoB);
        assertEquals("WAIT_PAY", detailB.getStatus());
        OrderStatusVO statusB = orderQueryService.getOrderStatus(userB, orderNoB, showId);
        assertEquals("WAIT_PAY", statusB.getStatus());
    }

    // -------------------------------------------------------------------------
    // 5. L3 剧本⑤：悬挂巡检 vs 延迟创单消费并发竞速（CREATE_DECISION 唯一行锁裁决）
    // -------------------------------------------------------------------------

    @Test
    void l3_scenario5_pendingReconcileVsDelayedConsumerRaceUnderCreateDecisionLock() throws Exception {
        long showId = 93501L;
        long tierId = 94501L;
        int initialStock = 20;
        prepareAutoShow(showId, tierId, initialStock, 78000L, true);

        // 5a. 无终止证据时，即使超过 5 分钟巡检也绝不盲目回补
        long safeUser = 870000L;
        ensureVerifiedUser(safeUser);
        long safeOrderNo = idGenerator.nextOrderId(safeUser);
        long oldSec = (System.currentTimeMillis() / 1000L) - 600L;
        long deductCode = orderRedisScripts.executeAutoDeduct(showId, tierId, safeUser, 1, safeOrderNo,
                oldSec, 86400L, 1, System.currentTimeMillis());
        assertEquals(1L, deductCode);

        PendingDeductReconcileScheduler.ReconcileResult noEvidenceRes =
                reconcileScheduler.reconcileSinglePending(showId, safeOrderNo, false, "NO_EVIDENCE");
        assertEquals(PendingDeductReconcileScheduler.ReconcileResult.KEPT_FOR_VERIFICATION, noEvidenceRes);
        assertEquals("19", stringRedisTemplate.opsForValue().get(RedisKeys.stock(showId, tierId)),
                "Reconcile without terminal evidence must NOT blindly rollback stock");

        // 5b. 连续 10 轮并发竞速：每轮同时启动「延迟消费落单」与「持终止证据的悬挂巡检」争夺 CREATE_DECISION 行锁
        int rounds = 10;
        int appliedCount = 0;
        int rolledBackCount = 0;
        ExecutorService pool = Executors.newFixedThreadPool(4);

        for (int i = 1; i <= rounds; i++) {
            long uid = 870000L + i;
            ensureVerifiedUser(uid);
            long orderNo = idGenerator.nextOrderId(uid);
            long nowMs = System.currentTimeMillis();
            assertEquals(1L, orderRedisScripts.executeAutoDeduct(showId, tierId, uid, 1, orderNo,
                    (nowMs / 1000L) - 400L, 86400L, 1, nowMs));

            OrderCreateEvent delayedEvent = OrderCreateEvent.builder()
                    .msgKey("order-create:" + orderNo)
                    .orderNo(orderNo)
                    .userId(uid)
                    .showId(showId)
                    .tierId(tierId)
                    .zoneId("VIP_A")
                    .qty(1)
                    .amountCent(78000L)
                    .deductEpochSec((nowMs / 1000L) - 400L)
                    .clientRequestId("l3-race-" + i + "-" + UUID.randomUUID().toString().substring(0, 6))
                    .requestFingerprint("fp-race-" + orderNo)
                    .saleConfigVersion(1)
                    .strategy("V4")
                    .traceId("trace-race-" + i)
                    .build();

            CyclicBarrier raceBarrier = new CyclicBarrier(2);
            Future<OrderPersistenceService.V4ConsumeOutcome> consumerFuture = pool.submit(() -> {
                raceBarrier.await(10, TimeUnit.SECONDS);
                return orderCreateConsumer.handleEvent(delayedEvent);
            });
            Future<PendingDeductReconcileScheduler.ReconcileResult> reconcileFuture = pool.submit(() -> {
                raceBarrier.await(10, TimeUnit.SECONDS);
                return reconcileScheduler.reconcileSinglePending(showId, orderNo, true, "TERMINATED_RACE");
            });

            consumerFuture.get(15, TimeUnit.SECONDS);
            reconcileFuture.get(15, TimeUnit.SECONDS);

            // 严格验证二选一不变量：绝不出现“已退库存却落单”或“已扣库存丢单”！
            OrderDO orderInDb = orderShardMapper.selectByOrderNo(OrderShardTableResolver.tableForOrderNo(orderNo), orderNo);
            OrderActionDO decision = orderActionMapper.selectByOrderAndType(orderNo, OrderActionDO.TYPE_CREATE_DECISION);
            boolean hasTombstone = orderRedisScripts.hasRollbackDone(showId, orderNo);

            assertNotNull(decision, "CREATE_DECISION row must exist after race");
            if (OrderActionDO.STATUS_APPLIED.equals(decision.getStatus())) {
                assertNotNull(orderInDb, "APPLIED decision must have persisted order");
                assertFalse(hasTombstone, "APPLIED order must NOT have rollback_done tombstone");
                appliedCount++;
            } else if (OrderActionDO.STATUS_REJECTED.equals(decision.getStatus())) {
                assertNull(orderInDb, "REJECTED decision must NOT have persisted order");
                assertTrue(hasTombstone, "REJECTED order must have idempotent rollback_done tombstone");
                rolledBackCount++;
            } else {
                fail("Unexpected CREATE_DECISION status after race: " + decision.getStatus());
            }
        }
        pool.shutdownNow();

        // 总体资金与库存守恒验证：初始 20 - safeOrder(1) - appliedCount == Redis 剩余库存
        int expectedRedisRemain = initialStock - 1 - appliedCount;
        int expectedDbRemain = initialStock - appliedCount;
        assertEquals(rounds, appliedCount + rolledBackCount);
        assertEquals(String.valueOf(expectedRedisRemain), stringRedisTemplate.opsForValue().get(RedisKeys.stock(showId, tierId)),
                "Redis stock must strictly conserve after 10 concurrent races");
        assertEquals(expectedDbRemain, orderInventoryMapper.selectTierStock(showId, tierId).getAvailableStock(),
                "DB stock must strictly match appliedCount after 10 concurrent races");

        // 5c. 确定性验证「巡检先抢占 CREATE_DECISION=REJECTED 并回补，迟到消费到达被拦截、0 落单、0 重复回补」
        long lateUser = 870099L;
        ensureVerifiedUser(lateUser);
        long lateOrderNo = idGenerator.nextOrderId(lateUser);
        long nowMs = System.currentTimeMillis();
        assertEquals(1L, orderRedisScripts.executeAutoDeduct(showId, tierId, lateUser, 1, lateOrderNo,
                (nowMs / 1000L) - 500L, 86400L, 1, nowMs));
        assertEquals(String.valueOf(expectedRedisRemain - 1), stringRedisTemplate.opsForValue().get(RedisKeys.stock(showId, tierId)));

        PendingDeductReconcileScheduler.ReconcileResult firstReconcile =
                reconcileScheduler.reconcileSinglePending(showId, lateOrderNo, true, "TERMINATED_BEFORE_CONSUME");
        assertEquals(PendingDeductReconcileScheduler.ReconcileResult.ROLLED_BACK, firstReconcile);
        assertEquals(String.valueOf(expectedRedisRemain), stringRedisTemplate.opsForValue().get(RedisKeys.stock(showId, tierId)),
                "Reconcile win must restore Redis stock by 1");

        // 迟到消费到达：发现 CREATE_DECISION=REJECTED，绝不落单，且幂等回补不重复加库存
        OrderCreateEvent veryLateEvent = OrderCreateEvent.builder()
                .msgKey("order-create:" + lateOrderNo).orderNo(lateOrderNo).userId(lateUser).showId(showId)
                .tierId(tierId).zoneId("VIP_A").qty(1).amountCent(78000L).deductEpochSec((nowMs / 1000L) - 500L)
                .clientRequestId("l3-late-after-rej-1").requestFingerprint("fp-late-" + lateOrderNo)
                .saleConfigVersion(1).strategy("V4").traceId("trace-late-rej").build();
        OrderPersistenceService.V4ConsumeOutcome lateOutcome = orderCreateConsumer.handleEvent(veryLateEvent);
        assertEquals(OrderPersistenceService.V4ConsumeStatus.ALREADY_REJECTED, lateOutcome.status());
        assertNull(orderShardMapper.selectByOrderNo(OrderShardTableResolver.tableForOrderNo(lateOrderNo), lateOrderNo),
                "Late consumer after REJECTED must NOT create DB order");
        assertEquals(String.valueOf(expectedRedisRemain), stringRedisTemplate.opsForValue().get(RedisKeys.stock(showId, tierId)),
                "Late consumer after REJECTED must NOT double-rollback Redis stock");
        assertEquals(expectedDbRemain, orderInventoryMapper.selectTierStock(showId, tierId).getAvailableStock(),
                "Late consumer after REJECTED must NOT deduct DB stock");

        log.info("[L3_RACE_RESULT] rounds={}, applied={}, rolledBack={}, redisRemain={}, dbRemain={}, lateConsumerAfterReconcileWin=ALREADY_REJECTED",
                rounds, appliedCount, rolledBackCount, expectedRedisRemain, expectedDbRemain);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void prepareAutoShow(long showId, long tierId, int stock, long priceCent, boolean preheat) {
        cleanupShowData(showId, List.of(tierId), List.of("VIP_A"));
        LocalDateTime now = LocalDateTime.now();
        ShowDO show = ShowDO.builder()
                .id(showId).title("L2/L3 自动配座测试场 " + showId).artist("Test").city("上海").venue("测试馆")
                .showTime(now.plusDays(10)).saleStartTime(now.minusMinutes(10)).saleEndTime(now.plusHours(4))
                .saleStatus(preheat ? "INIT" : "ON_SALE").saleConfigVersion(1).seatMode("AUTO_ALLOC")
                .posterUrl("/p.jpg").description("live test").deleted(0).build();
        showMapper.insert(show);

        TicketTierDO tier = TicketTierDO.builder()
                .id(tierId).showId(showId).tierName("VIP").zoneId("VIP_A")
                .priceCent(priceCent).totalStock(stock).availableStock(stock).version(0).deleted(0).build();
        ticketTierMapper.insert(tier);

        if (preheat) {
            showPreheatService.preheat(showId, 1);
            jdbcTemplate.update("UPDATE t_show SET sale_status = 'ON_SALE' WHERE id = ?", showId);
        }
    }

    private void prepareTwoTierAutoShow(long showId, long tier1, int stock1, long price1,
                                        long tier2, int stock2, long price2) {
        cleanupShowData(showId, List.of(tier1, tier2), List.of("Z_OUT", "Z_BAK"));
        LocalDateTime now = LocalDateTime.now();
        showMapper.insert(ShowDO.builder()
                .id(showId).title("L3 双档测试场 " + showId).artist("Test").city("上海").venue("测试馆")
                .showTime(now.plusDays(10)).saleStartTime(now.minusMinutes(10)).saleEndTime(now.plusHours(4))
                .saleStatus("INIT").saleConfigVersion(1).seatMode("AUTO_ALLOC")
                .posterUrl("/p.jpg").description("live test").deleted(0).build());
        ticketTierMapper.insert(TicketTierDO.builder()
                .id(tier1).showId(showId).tierName("售罄档").zoneId("Z_OUT")
                .priceCent(price1).totalStock(10).availableStock(stock1).version(0).deleted(0).build());
        ticketTierMapper.insert(TicketTierDO.builder()
                .id(tier2).showId(showId).tierName("备用档").zoneId("Z_BAK")
                .priceCent(price2).totalStock(stock2).availableStock(stock2).version(0).deleted(0).build());
        showPreheatService.preheat(showId, 1);
        jdbcTemplate.update("UPDATE t_show SET sale_status = 'ON_SALE' WHERE id = ?", showId);
    }

    private void prepareManualSeatShow(long showId, long tierId, long otherTierId, String zoneId) {
        cleanupShowData(showId, List.of(tierId, otherTierId), List.of(zoneId, "HALL_B"));
        LocalDateTime now = LocalDateTime.now();
        showMapper.insert(ShowDO.builder()
                .id(showId).title("L2 连座选座测试场 " + showId).artist("Test").city("北京").venue("国家大剧院")
                .showTime(now.plusDays(10)).saleStartTime(now.minusMinutes(10)).saleEndTime(now.plusHours(4))
                .saleStatus("INIT").saleConfigVersion(1).seatMode("MANUAL_SEAT")
                .posterUrl("/p.jpg").description("seat live test").deleted(0).build());
        ticketTierMapper.insert(TicketTierDO.builder()
                .id(tierId).showId(showId).tierName("音乐厅A区").zoneId(zoneId)
                .priceCent(88000L).totalStock(100).availableStock(100).version(0).deleted(0).build());
        ticketTierMapper.insert(TicketTierDO.builder()
                .id(otherTierId).showId(showId).tierName("音乐厅B区").zoneId("HALL_B")
                .priceCent(68000L).totalStock(10).availableStock(10).version(0).deleted(0).build());

        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 10; c++) {
                String seg = c < 3 ? ("R" + (r + 1) + "-L") : (c < 7 ? ("R" + (r + 1) + "-M") : ("R" + (r + 1) + "-R"));
                seatMapper.insert(SeatDO.builder()
                        .showId(showId).tierId(tierId).zoneId(zoneId)
                        .seatIndex(r * 10 + c).rowNo(r + 1).colNo(c + 1).segmentId(seg).status(0).deleted(0).build());
            }
        }
        showPreheatService.preheat(showId, 1);
        jdbcTemplate.update("UPDATE t_show SET sale_status = 'ON_SALE' WHERE id = ?", showId);
    }

    private void cleanupShowData(long showId, List<Long> tierIds, List<String> zones) {
        for (String t : OrderShardTableResolver.allShardTables()) {
            jdbcTemplate.update("DELETE FROM " + t + " WHERE show_id = ?", showId);
        }
        jdbcTemplate.update("DELETE FROM t_order_action WHERE show_id = ?", showId);
        jdbcTemplate.update("DELETE FROM t_show_task WHERE show_id = ?", showId);
        jdbcTemplate.update("DELETE FROM t_seat WHERE show_id = ?", showId);
        jdbcTemplate.update("DELETE FROM t_ticket_tier WHERE show_id = ?", showId);
        jdbcTemplate.update("DELETE FROM t_show WHERE id = ?", showId);

        stringRedisTemplate.delete(RedisKeys.showReady(showId));
        stringRedisTemplate.delete(RedisKeys.showDetail(showId));
        stringRedisTemplate.delete(RedisKeys.boughtUsers(showId));
        stringRedisTemplate.delete(RedisKeys.boughtOwner(showId));
        stringRedisTemplate.delete(RedisKeys.deductPendingZset(showId));
        stringRedisTemplate.delete(RedisKeys.seckillSubmitPending(showId));
        for (Long tid : tierIds) {
            stringRedisTemplate.delete(RedisKeys.stock(showId, tid));
        }
        for (String z : zones) {
            stringRedisTemplate.delete(RedisKeys.seatBitmap(showId, z));
            stringRedisTemplate.delete(RedisKeys.seatLayout(showId, z));
        }
    }

    private void ensureVerifiedUser(long userId) {
        Integer exists = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM t_user_identity WHERE user_id = ?", Integer.class, userId);
        if (exists == null || exists == 0) {
            String hmac = String.format("%064x", userId);
            userIdentityMapper.insert(UserIdentityDO.builder()
                    .userId(userId)
                    .realNameCipher("cipher-name-" + userId)
                    .idCardCipher("cipher-id-" + userId)
                    .idCardMasked("1101**********" + String.format("%04d", userId % 10000))
                    .idCardHmac(hmac)
                    .verifiedTime(LocalDateTime.now())
                    .deleted(0)
                    .build());
        }
        stringRedisTemplate.opsForValue().set(RedisKeys.userVerified(userId), "1");
    }

    private SeckillOrderRequest manualSeatReq(long showId, long tierId, String zoneId,
                                              List<Integer> seats, String token, String reqId) {
        return SeckillOrderRequest.builder()
                .showId(showId).tierId(tierId).qty(seats.size()).zoneId(zoneId)
                .seatIndices(seats).layoutVersion("1").saleConfigVersion(1)
                .expectedUnitPriceCent(88000L).seckillToken(token).clientRequestId(reqId)
                .build();
    }

    private int sumActiveQtyAcrossShards(long showId, long tierId) {
        int sum = 0;
        for (String t : OrderShardTableResolver.allShardTables()) {
            sum += orderShardMapper.sumActiveQtyByShowAndTier(t, showId, tierId);
        }
        return sum;
    }

    private int countActiveOrdersAcrossShards(long showId) {
        int count = 0;
        for (String t : OrderShardTableResolver.allShardTables()) {
            count += orderShardMapper.countActiveOrdersByShow(t, showId);
        }
        return count;
    }

    private int queryMaxActiveOrdersPerUser(long showId) {
        int max = 0;
        for (String t : OrderShardTableResolver.allShardTables()) {
            Integer m = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(MAX(cnt), 0) FROM (SELECT COUNT(1) AS cnt FROM " + t +
                            " WHERE show_id = ? AND active_flag = 1 AND deleted = 0 GROUP BY user_id) x",
                    Integer.class, showId);
            if (m != null && m > max) {
                max = m;
            }
        }
        return max;
    }

    private long zcard(String key) {
        Long c = stringRedisTemplate.opsForZSet().zCard(key);
        return c == null ? 0L : c;
    }

    private void waitUntil(long timeoutMs, Callable<Boolean> condition) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.call())) {
                return;
            }
            Thread.sleep(150L);
        }
        assertTrue(Boolean.TRUE.equals(condition.call()), "Timed out after " + timeoutMs + "ms waiting for condition");
    }
}
