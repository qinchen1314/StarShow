package com.starshow.boot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.starshow.api.constant.MqTopics;
import com.starshow.api.dto.ShowBasicDTO;
import com.starshow.api.event.ShowRemindEvent;
import com.starshow.api.facade.ShowReadFacade;
import com.starshow.api.facade.UserFacade;
import com.starshow.common.constant.RedisKeys;
import com.starshow.common.exception.BizException;
import com.starshow.common.id.GeneSnowflakeIdGenerator;
import com.starshow.common.result.ErrorCode;
import com.starshow.show.domain.entity.ShowDO;
import com.starshow.show.domain.entity.TicketTierDO;
import com.starshow.show.mapper.ShowMapper;
import com.starshow.show.mapper.TicketTierMapper;
import com.starshow.show.service.ShowPreheatService;
import com.starshow.show.service.ShowScheduleService;
import com.starshow.user.config.ReservationProperties;
import com.starshow.user.domain.entity.ReservationDO;
import com.starshow.user.domain.entity.UserIdentityDO;
import com.starshow.user.domain.entity.UserNotificationDO;
import com.starshow.user.mapper.ReservationMapper;
import com.starshow.user.mapper.UserIdentityMapper;
import com.starshow.user.mapper.UserNotificationMapper;
import com.starshow.user.mq.ShowRemindConsumer;
import com.starshow.user.mq.ShowRemindNotifier;
import com.starshow.user.mq.ShowRemindProducer;
import com.starshow.user.mq.ShowRemindRecoveryScheduler;
import com.starshow.user.service.ReservationCountStore;
import com.starshow.user.service.ReservationService;
import com.starshow.user.service.UserNotificationService;
import com.starshow.user.vo.MyReservationVO;
import com.starshow.user.vo.ReservationVO;
import com.starshow.user.vo.ShowRescheduleResultVO;
import com.starshow.user.vo.UserNotificationVO;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;

/**
 * 真实中间件并发、崩溃恢复与无饥饿补偿验收套件（ISSUE-003 / ISSUE-004 / ISSUE-008 / ISSUE-009 实证门禁）：
 * 仅在显式开启 -Dstarshow.live.enabled=true 时运行，直连真实 Docker MySQL 8 + Redis 7 + RocketMQ 5.3！
 */
@EnabledIfSystemProperty(named = "starshow.live.enabled", matches = "true")
@SpringBootTest(
        classes = {StarShowBootAllApplication.class, Chain1LiveMiddlewareVerificationTest.CountingNotifierConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "starshow.reservation.recovery-enabled=false",
                "starshow.reservation.mq-enabled=true",
                "starshow.reservation.test-override-delay-ms=0",
                "starshow.reservation.remind-retry-backoff-seconds=1",
                "knife4j.enable=false"
        }
)
@ActiveProfiles("monolith")
class Chain1LiveMiddlewareVerificationTest {

    private static final long LIVE_SHOW_COUNT = 91003L;
    private static final long LIVE_SHOW_RECOVERY_FAR = 91004L;
    private static final long LIVE_SHOW_RECOVERY_FAIL = 91005L;
    private static final long LIVE_SHOW_RECOVERY_TIMEOUT = 91006L;
    private static final long LIVE_SHOW_FENCE = 91007L;
    private static final long LIVE_SHOW_CRASH_TTL = 91008L;
    private static final long LIVE_SHOW_STARVE_FAR_1 = 91009L;
    private static final long LIVE_SHOW_STARVE_FAR_2 = 91010L;
    private static final long LIVE_SHOW_STARVE_DUE_1 = 91011L;
    private static final long LIVE_SHOW_STARVE_DUE_2 = 91012L;
    private static final long LIVE_SHOW_STARVE_BROKEN = 91013L;
    private static final long LIVE_SHOW_RESCHEDULE_POSTPONE = 91014L;
    private static final long LIVE_SHOW_RESCHEDULE_ADVANCE = 91015L;
    private static final long LIVE_SHOW_SINGLE_FLIGHT = 91016L;

    @Autowired
    private ReservationService reservationService;
    @Autowired
    private ReservationCountStore reservationCountStore;
    @Autowired
    private ReservationMapper reservationMapper;
    @Autowired
    private UserIdentityMapper userIdentityMapper;
    @Autowired
    private UserNotificationMapper userNotificationMapper;
    @Autowired
    private UserNotificationService userNotificationService;
    @Autowired
    private ShowMapper showMapper;
    @Autowired
    private TicketTierMapper ticketTierMapper;
    @Autowired
    private ShowPreheatService showPreheatService;
    @Autowired
    private ShowScheduleService showScheduleService;
    @Autowired
    private UserFacade userFacade;
    @Autowired
    private ShowReadFacade showReadFacade;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private GeneSnowflakeIdGenerator idGenerator;
    @Autowired
    private RocketMQTemplate rocketMQTemplate;
    @Autowired
    private ShowRemindConsumer showRemindConsumer;
    @Autowired
    private ShowRemindRecoveryScheduler recoveryScheduler;
    @Autowired
    private ReservationProperties reservationProperties;
    @Autowired
    private CountingNotifier countingNotifier;

    @BeforeEach
    void cleanLiveTestNamespace() {
        countingNotifier.reset();
        List<Long> showIds = List.of(
                LIVE_SHOW_COUNT, LIVE_SHOW_RECOVERY_FAR, LIVE_SHOW_RECOVERY_FAIL, LIVE_SHOW_RECOVERY_TIMEOUT,
                LIVE_SHOW_FENCE, LIVE_SHOW_CRASH_TTL, LIVE_SHOW_STARVE_FAR_1, LIVE_SHOW_STARVE_FAR_2,
                LIVE_SHOW_STARVE_DUE_1, LIVE_SHOW_STARVE_DUE_2, LIVE_SHOW_STARVE_BROKEN,
                LIVE_SHOW_RESCHEDULE_POSTPONE, LIVE_SHOW_RESCHEDULE_ADVANCE, LIVE_SHOW_SINGLE_FLIGHT
        );
        for (long sid : showIds) {
            userNotificationMapper.delete(new LambdaQueryWrapper<UserNotificationDO>().eq(UserNotificationDO::getShowId, sid));
            reservationMapper.delete(new LambdaQueryWrapper<ReservationDO>().eq(ReservationDO::getShowId, sid));
            stringRedisTemplate.delete(RedisKeys.reservationCount(sid));
            stringRedisTemplate.delete(RedisKeys.reservationBloom(sid));
            showMapper.deleteById(sid);
        }
        userIdentityMapper.delete(new LambdaQueryWrapper<UserIdentityDO>()
                .ge(UserIdentityDO::getUserId, 9000000L)
                .le(UserIdentityDO::getUserId, 9000999L));
        for (long uid = 9000001L; uid <= 9000090L; uid++) {
            stringRedisTemplate.delete(RedisKeys.userVerified(uid));
        }
    }

    @Test
    void issue003_shouldKeepExactCountUnderColdStartConcurrencyGetInterleavingDeleteAndRedisFault() throws Exception {
        insertShow(LIVE_SHOW_COUNT, "并发计数验证专场", LocalDateTime.now().plusDays(10));
        for (long uid = 9000001L; uid <= 9000040L; uid++) {
            ensureVerifiedUser(uid);
        }

        int batch1 = 20;
        ExecutorService pool = Executors.newFixedThreadPool(batch1);
        try {
            CyclicBarrier barrier1 = new CyclicBarrier(batch1);
            List<Future<ReservationVO>> futures = new ArrayList<>();
            for (int i = 0; i < batch1; i++) {
                long uid = 9000001L + i;
                futures.add(pool.submit(() -> {
                    barrier1.await(10, TimeUnit.SECONDS);
                    return reservationService.reserve(uid, LIVE_SHOW_COUNT);
                }));
            }
            for (Future<ReservationVO> f : futures) {
                assertNotNull(f.get(15, TimeUnit.SECONDS).getReservationId());
            }
            assertAllCountsMatch(LIVE_SHOW_COUNT, 20L, "After cold-start 20 concurrent POSTs");

            // 交错 2：删键后 POST 落库卡同步屏障，GET 抢先回填，释放 POST 后零多计
            stringRedisTemplate.delete(RedisKeys.reservationCount(LIVE_SHOW_COUNT));
            CountDownLatch postInsertedLatch = new CountDownLatch(1);
            CountDownLatch getRebuiltLatch = new CountDownLatch(1);

            long interleaveUserId = 9000025L;
            Future<Long> postFuture = pool.submit(() -> {
                String preEpoch = reservationCountStore.captureEpochBeforeInsert(LIVE_SHOW_COUNT);
                assertNull(preEpoch);
                reservationMapper.insert(ReservationDO.builder()
                        .id(idGenerator.nextId())
                        .userId(interleaveUserId)
                        .showId(LIVE_SHOW_COUNT)
                        .remindStatus(ReservationDO.REMIND_PENDING)
                        .nextRetryTime(LocalDateTime.now())
                        .deleted(0)
                        .build());
                postInsertedLatch.countDown();
                assertTrue(getRebuiltLatch.await(10, TimeUnit.SECONDS));
                return reservationCountStore.incrementAfterDbInsert(LIVE_SHOW_COUNT, preEpoch);
            });

            Future<Long> getFuture = pool.submit(() -> {
                assertTrue(postInsertedLatch.await(10, TimeUnit.SECONDS));
                long countSeenByGet = reservationService.getReservationCount(LIVE_SHOW_COUNT).getCount();
                getRebuiltLatch.countDown();
                return countSeenByGet;
            });

            assertEquals(21L, getFuture.get(15, TimeUnit.SECONDS));
            assertEquals(21L, postFuture.get(15, TimeUnit.SECONDS));
            assertAllCountsMatch(LIVE_SHOW_COUNT, 21L, "After POST-insert / GET-backfill interleaving (0 over-count)");

            // 场景 3：瞬时 Redis 故障触发脏标记并恢复收敛
            AtomicBoolean injectRedisFault = new AtomicBoolean(true);
            StringRedisTemplate faultyTemplate = org.mockito.Mockito.spy(stringRedisTemplate);
            org.mockito.Mockito.doAnswer(inv -> {
                if (injectRedisFault.get()) {
                    throw new RuntimeException("Simulated transient Redis outage");
                }
                return inv.callRealMethod();
            }).when(faultyTemplate).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList(),
                    org.mockito.ArgumentMatchers.any(Object[].class));

            ReservationCountStore faultStore = new ReservationCountStore(
                    reservationMapper, faultyTemplate, redissonClient, reservationProperties
            );
            String epochBeforeFault = faultStore.captureEpochBeforeInsert(LIVE_SHOW_COUNT);
            assertNotNull(epochBeforeFault);
            reservationMapper.insert(ReservationDO.builder()
                    .id(idGenerator.nextId())
                    .userId(9000026L)
                    .showId(LIVE_SHOW_COUNT)
                    .remindStatus(ReservationDO.REMIND_PENDING)
                    .nextRetryTime(LocalDateTime.now())
                    .deleted(0)
                    .build());
            assertEquals(22L, faultStore.incrementAfterDbInsert(LIVE_SHOW_COUNT, epochBeforeFault));
            assertTrue(faultStore.isDirty(LIVE_SHOW_COUNT));
            injectRedisFault.set(false);
            assertEquals(22L, faultStore.getCount(LIVE_SHOW_COUNT));
            assertFalse(faultStore.isDirty(LIVE_SHOW_COUNT));
            assertAllCountsMatch(LIVE_SHOW_COUNT, 22L, "After transient Redis outage and recovery");

            // 场景 4：同用户 16 线程并发重复预约 -> 仅 1 成功、15 次 A0106
            long dupUserId = 9000030L;
            int dupThreads = 16;
            CyclicBarrier dupBarrier = new CyclicBarrier(dupThreads);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger dupErrCount = new AtomicInteger(0);
            List<Callable<Void>> dupTasks = new ArrayList<>();
            for (int i = 0; i < dupThreads; i++) {
                dupTasks.add(() -> {
                    dupBarrier.await(10, TimeUnit.SECONDS);
                    try {
                        reservationService.reserve(dupUserId, LIVE_SHOW_COUNT);
                        successCount.incrementAndGet();
                    } catch (BizException ex) {
                        if (ex.getErrorCode() == ErrorCode.RESERVATION_DUPLICATE) {
                            dupErrCount.incrementAndGet();
                        } else {
                            throw ex;
                        }
                    }
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(dupTasks)) {
                f.get(15, TimeUnit.SECONDS);
            }
            assertEquals(1, successCount.get());
            assertEquals(dupThreads - 1, dupErrCount.get());
            assertAllCountsMatch(LIVE_SHOW_COUNT, 23L, "After 16 concurrent duplicate reservations");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void issue008_shouldFenceStaleRebuilderAfterLockLeaseExpiresAndRecoverWithinBoundedTtl() throws Exception {
        insertShow(LIVE_SHOW_FENCE, "Fencing防旧快照覆盖专场", LocalDateTime.now().plusDays(10));
        insertShow(LIVE_SHOW_CRASH_TTL, "有界TTL自愈专场", LocalDateTime.now().plusDays(10));

        // 构造短租约(1s)与短有界TTL(2s)的独立 Store 实例
        ReservationProperties fastProps = new ReservationProperties();
        fastProps.setCountRebuildLockLeaseSeconds(1L);
        fastProps.setCountCacheTtlSeconds(2L);
        ReservationCountStore fastStore = new ReservationCountStore(
                reservationMapper, stringRedisTemplate, redissonClient, fastProps
        );

        // 1. 预置 2 条预约记录
        insertRawReservation(idGenerator.nextId(), 9000051L, LIVE_SHOW_FENCE, ReservationDO.REMIND_SENT, LocalDateTime.now());
        insertRawReservation(idGenerator.nextId(), 9000052L, LIVE_SHOW_FENCE, ReservationDO.REMIND_SENT, LocalDateTime.now());

        CountDownLatch rebuilderASnapshotTaken = new CountDownLatch(1);
        CountDownLatch rebuilderBFinished = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 重建者 A 拿到锁并读到旧快照 2 后挂起，等待锁租约(1s)过期、B 完成较新重建(3)后再醒来尝试发布旧快照
            Future<ReservationCountStore.RebuildResult> futureA = pool.submit(() ->
                    fastStore.rebuildFromDbWithHook(LIVE_SHOW_FENCE, () -> {
                        rebuilderASnapshotTaken.countDown();
                        try {
                            assertTrue(rebuilderBFinished.await(10, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }));

            assertTrue(rebuilderASnapshotTaken.await(10, TimeUnit.SECONDS));
            // 等待重建者 A 的 1 秒锁租约到期，新增第 3 条预约并由重建者 B 发布新快照 3
            Thread.sleep(1500);
            insertRawReservation(idGenerator.nextId(), 9000053L, LIVE_SHOW_FENCE, ReservationDO.REMIND_SENT, LocalDateTime.now());
            ReservationCountStore.RebuildResult resultB = fastStore.rebuildFromDbWithHook(LIVE_SHOW_FENCE, null);
            assertTrue(resultB.committed(), "Rebuilder B must commit newer snapshot");
            assertEquals(3L, resultB.count());
            rebuilderBFinished.countDown();

            // 恢复重建者 A：Fencing Token 必须拦截 A 的旧快照提交，绝不覆盖 B 的 3！
            ReservationCountStore.RebuildResult resultA = futureA.get(10, TimeUnit.SECONDS);
            assertFalse(resultA.committed(), "Stale rebuilder A must be fenced out and refused commit");
            assertEquals(3L, fastStore.getCount(LIVE_SHOW_FENCE));
            Object redisCount = stringRedisTemplate.opsForHash().get(RedisKeys.reservationCount(LIVE_SHOW_FENCE), "count");
            assertEquals("3", String.valueOf(redisCount));

            // 2. 验证有界 TTL 自愈：预热空场次为 0 -> 直接写 DB 模拟写入中断 -> 超过 2s TTL 后自动回源收敛为 1
            assertEquals(0L, fastStore.getCount(LIVE_SHOW_CRASH_TTL));
            insertRawReservation(idGenerator.nextId(), 9000054L, LIVE_SHOW_CRASH_TTL, ReservationDO.REMIND_SENT, LocalDateTime.now());
            assertEquals(0L, fastStore.getCount(LIVE_SHOW_CRASH_TTL), "Before TTL expires, cached 0 is temporarily served");
            Thread.sleep(2600);
            assertEquals(1L, fastStore.getCount(LIVE_SHOW_CRASH_TTL), "After bounded TTL expires, count automatically converges to DB=1");
            assertEquals("1", String.valueOf(stringRedisTemplate.opsForHash().get(RedisKeys.reservationCount(LIVE_SHOW_CRASH_TTL), "count")));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void issue004_shouldRecoverOutOfWindowBrokerFailureAndTimeoutWithoutRegressingTerminalState() throws Exception {
        long userFar = 9000041L;
        long userFail = 9000042L;
        long userTimeout = 9000043L;
        ensureVerifiedUser(userFar);
        ensureVerifiedUser(userFail);
        ensureVerifiedUser(userTimeout);

        // === 剧本 A：超 72h 窗口（status=0 且 next_retry_time 推迟到入窗点）-> 模拟时间推进到入窗 -> 补偿补投真实 RocketMQ 5.3 -> 终态 1 ===
        insertShow(LIVE_SHOW_RECOVERY_FAR, "超窗补偿专场", LocalDateTime.now().plusDays(10));
        ReservationVO farVo = reservationService.reserve(userFar, LIVE_SHOW_RECOVERY_FAR);
        assertFalse(farVo.isRemindScheduled());
        ReservationDO farRow = reservationMapper.selectById(farVo.getReservationId());
        assertEquals(ReservationDO.REMIND_PENDING, farRow.getRemindStatus());
        assertTrue(farRow.getNextRetryTime().isAfter(LocalDateTime.now().plusDays(5)), "Out-of-window record must defer next_retry_time");

        // 模拟进入时间窗：更新开票时间并将 next_retry_time 拨到当前时刻
        ShowDO updateFar = showMapper.selectById(LIVE_SHOW_RECOVERY_FAR);
        updateFar.setSaleStartTime(LocalDateTime.now().plusMinutes(5));
        showMapper.updateById(updateFar);
        reservationMapper.casDeferNextRetryTime(farVo.getReservationId(), LocalDateTime.now().minusSeconds(1));

        int recoveredFar = recoveryScheduler.recoverBatch(100);
        assertTrue(recoveredFar >= 1);
        awaitRemindSent(farVo.getReservationId(), 15);
        assertEquals(1, countingNotifier.countFor(farVo.getReservationId()), "Scenario A must notify exactly once");

        // === 剧本 B：Broker 临时不可用 -> 预约成功不回滚且 CAS 置 2+退避 -> Broker 恢复且退避到期后补偿补投真实 RocketMQ 5.3 -> 终态 1 ===
        insertShow(LIVE_SHOW_RECOVERY_FAIL, "Broker故障恢复专场", LocalDateTime.now().plusMinutes(5));
        AtomicBoolean brokerDown = new AtomicBoolean(true);
        ObjectProvider<RocketMQTemplate> flakyProvider = flakyProvider(rocketMQTemplate, () -> {
            if (brokerDown.get()) {
                throw new RuntimeException("Simulated Broker unavailable");
            }
            return null;
        });
        ShowRemindProducer flakyProducer = new ShowRemindProducer(flakyProvider, reservationMapper, reservationProperties);
        ReservationService flakyService = new ReservationService(
                userFacade, showReadFacade, reservationMapper, redissonClient,
                reservationCountStore, idGenerator, flakyProducer, reservationProperties
        );

        ReservationVO failVo = flakyService.reserve(userFail, LIVE_SHOW_RECOVERY_FAIL);
        assertFalse(failVo.isRemindScheduled());
        assertEquals(ReservationDO.REMIND_FAILED, reservationMapper.selectById(failVo.getReservationId()).getRemindStatus());

        brokerDown.set(false);
        Thread.sleep(2200); // 等待 1 秒测试退避到期（含 MySQL DATETIME 秒级四舍五入边界）
        int recoveredFail = recoveryScheduler.recoverBatch(100);
        assertTrue(recoveredFail >= 1);
        awaitRemindSent(failVo.getReservationId(), 15);
        assertEquals(1, countingNotifier.countFor(failVo.getReservationId()), "Scenario B must notify exactly once");

        // === 剧本 C：Broker 已真实收消息但发送端抛超时异常（CAS 置 2）-> 原消息与补投双到 -> 终态 1 绝不回退且通知严格 1 次 ===
        insertShow(LIVE_SHOW_RECOVERY_TIMEOUT, "发送超时终态收敛专场", LocalDateTime.now().plusMinutes(5));
        RocketMQTemplate sendOkThenThrowTimeout = org.mockito.Mockito.spy(rocketMQTemplate);
        org.mockito.Mockito.doAnswer(inv -> {
            SendResult realRes = (SendResult) inv.callRealMethod();
            assertEquals(SendStatus.SEND_OK, realRes.getSendStatus());
            throw new RuntimeException("Simulated client-side send timeout after broker persisted message");
        }).when(sendOkThenThrowTimeout).syncSendDeliverTimeMills(eq(MqTopics.SHOW_REMIND),
                org.mockito.ArgumentMatchers.any(Message.class), org.mockito.ArgumentMatchers.anyLong());

        ShowRemindProducer timeoutProducer = new ShowRemindProducer(
                fixedProvider(sendOkThenThrowTimeout), reservationMapper, reservationProperties
        );
        ReservationService timeoutService = new ReservationService(
                userFacade, showReadFacade, reservationMapper, redissonClient,
                reservationCountStore, idGenerator, timeoutProducer, reservationProperties
        );

        ReservationVO timeoutVo = timeoutService.reserve(userTimeout, LIVE_SHOW_RECOVERY_TIMEOUT);
        long timeoutRsvId = timeoutVo.getReservationId();
        assertEquals(ReservationDO.REMIND_FAILED, reservationMapper.selectById(timeoutRsvId).getRemindStatus());

        Thread.sleep(2200);
        recoveryScheduler.recoverBatch(100);
        awaitRemindSent(timeoutRsvId, 15);

        assertEquals(0, reservationMapper.casMarkFailedWithRetry(timeoutRsvId, LocalDateTime.now().plusSeconds(30)));
        showRemindConsumer.onMessage(ShowRemindEvent.builder()
                .reservationId(timeoutRsvId)
                .userId(userTimeout)
                .showId(LIVE_SHOW_RECOVERY_TIMEOUT)
                .showTitle("发送超时终态收敛专场")
                .saleStartTime(LocalDateTime.now().plusMinutes(5))
                .traceId("replay-trace")
                .build());

        assertEquals(ReservationDO.REMIND_SENT, reservationMapper.selectById(timeoutRsvId).getRemindStatus());
        assertEquals(1, countingNotifier.countFor(timeoutRsvId));
    }

    @Test
    void issue009_shouldNotStarveDueRemindersBehind120OutOfWindowRecordsAcrossShowsAndMetadataFailure() throws Exception {
        // 准备 2 个远期超窗场次、2 个已入窗到期场次、1 个元数据查询临时报错场次
        insertShow(LIVE_SHOW_STARVE_FAR_1, "远期超窗场次一", LocalDateTime.now().plusDays(10));
        insertShow(LIVE_SHOW_STARVE_FAR_2, "远期超窗场次二", LocalDateTime.now().plusDays(12));
        insertShow(LIVE_SHOW_STARVE_DUE_1, "到期补发场次一", LocalDateTime.now().plusMinutes(5));
        insertShow(LIVE_SHOW_STARVE_DUE_2, "到期补发场次二", LocalDateTime.now().plusMinutes(5));
        insertShow(LIVE_SHOW_STARVE_BROKEN, "元数据故障场次", LocalDateTime.now().plusMinutes(5));

        LocalDateTime dueNow = LocalDateTime.now().minusSeconds(5);
        long baseId = 770000000000L;

        // 1. 故意用更小的 ID 插入 120 条超窗记录（跨 2 个场次，远超 batchSize=50），占满前两批队头！
        for (int i = 0; i < 60; i++) {
            insertRawReservation(baseId + i, 9001000L + i, LIVE_SHOW_STARVE_FAR_1, ReservationDO.REMIND_PENDING, dueNow);
        }
        for (int i = 60; i < 120; i++) {
            insertRawReservation(baseId + i, 9001000L + i, LIVE_SHOW_STARVE_FAR_2, ReservationDO.REMIND_PENDING, dueNow);
        }

        // 2. 在 120 条超窗记录之后，插入 1 条元数据异常场次记录 + 2 条跨场次已入窗失败提醒
        long brokenRsvId = baseId + 121;
        long dueRsv1 = baseId + 122;
        long dueRsv2 = baseId + 123;
        insertRawReservation(brokenRsvId, 9002001L, LIVE_SHOW_STARVE_BROKEN, ReservationDO.REMIND_FAILED, dueNow);
        insertRawReservation(dueRsv1, 9002002L, LIVE_SHOW_STARVE_DUE_1, ReservationDO.REMIND_FAILED, dueNow);
        insertRawReservation(dueRsv2, 9002003L, LIVE_SHOW_STARVE_DUE_2, ReservationDO.REMIND_FAILED, dueNow);

        // 包装 ShowReadFacade：仅对 LIVE_SHOW_STARVE_BROKEN 抛出临时查询异常，其余走真实本地 Facade
        ShowReadFacade faultInjectedFacade = new ShowReadFacade() {
            @Override
            public ShowBasicDTO getShowBasic(long showId) {
                if (showId == LIVE_SHOW_STARVE_BROKEN) {
                    throw new RuntimeException("Simulated temporary ShowReadFacade outage for show " + showId);
                }
                return showReadFacade.getShowBasic(showId);
            }

            @Override
            public com.starshow.api.dto.TierSnapshotDTO getTierSnapshot(long showId, long tierId) {
                return showReadFacade.getTierSnapshot(showId, tierId);
            }
        };

        ShowRemindProducer realProducer = new ShowRemindProducer(
                fixedProvider(rocketMQTemplate), reservationMapper, reservationProperties
        );
        ShowRemindRecoveryScheduler isolatedScheduler = new ShowRemindRecoveryScheduler(
                reservationMapper, faultInjectedFacade, realProducer, reservationProperties
        );

        // 3. 以 batchSize=50 执行单轮连续多批推进：前 120 条超窗记录推迟让出，故障场次被隔离，后 2 条跨场次到期提醒当轮全部补投！
        int progressed = isolatedScheduler.recoverDueReminders(50, 10);
        assertEquals(2, progressed, "Both due reminders across two shows must progress in the same run despite 120 out-of-window rows ahead");

        awaitRemindSent(dueRsv1, 15);
        awaitRemindSent(dueRsv2, 15);
        assertEquals(1, countingNotifier.countFor(dueRsv1));
        assertEquals(1, countingNotifier.countFor(dueRsv2));

        // 断言：120 条超窗记录均保持 status=0 且 next_retry_time 已被推迟到未来（下轮不会再进入到期候选集）
        List<ReservationDO> remainingDue = reservationMapper.selectDueRecoverableReminders(LocalDateTime.now(), 0L, 200);
        long farStillDue = remainingDue.stream()
                .filter(r -> r.getShowId() == LIVE_SHOW_STARVE_FAR_1 || r.getShowId() == LIVE_SHOW_STARVE_FAR_2)
                .count();
        assertEquals(0L, farStillDue, "Out-of-window records must vacate due candidate set immediately");
        assertEquals(ReservationDO.REMIND_PENDING, reservationMapper.selectById(baseId).getRemindStatus());
        assertEquals(ReservationDO.REMIND_FAILED, reservationMapper.selectById(brokenRsvId).getRemindStatus());
    }

    @Test
    void phase11_shouldSupportRescheduleAdvanceAndPostponeVersionGuardNotificationsAndSingleFlightCount() throws Exception {
        long userPostpone = 9000071L;
        long userAdvance = 9000072L;
        ensureVerifiedUser(userPostpone);
        ensureVerifiedUser(userAdvance);

        // === 1. 推迟改期：v1 排期入 RocketMQ -> 立即推迟至 10 天后(v2) -> 旧 v1 消息到达被版本守卫丢弃 -> 再调回近窗口(v3) 真实送达并同事务落 1 条站内通知 ===
        insertShow(LIVE_SHOW_RESCHEDULE_POSTPONE, "改期推迟验证专场", LocalDateTime.now().plusMinutes(5));
        ReservationVO v1Rsv = reservationService.reserve(userPostpone, LIVE_SHOW_RESCHEDULE_POSTPONE);
        assertEquals(1, v1Rsv.getSaleConfigVersion());
        assertTrue(v1Rsv.isRemindScheduled());

        LocalDateTime postponedStart = LocalDateTime.now().plusDays(10);
        ShowBasicDTO v2Show = showScheduleService.reschedule(LIVE_SHOW_RESCHEDULE_POSTPONE, postponedStart, postponedStart.plusDays(10));
        assertEquals(2, v2Show.getSaleConfigVersion());
        // Phase 2: 改期时 ShowScheduleService 已自动本地联动完成预约重排与 SCHEDULE_CHANGED 通知；
        // 再次调用 rescheduleForShowVersion 必须幂等不重复重置、不重复落通知
        ShowRescheduleResultVO v2RescheduleDup = recoveryScheduler.rescheduleForShowVersion(LIVE_SHOW_RESCHEDULE_POSTPONE);
        assertEquals(2, v2RescheduleDup.getSaleConfigVersion());
        assertEquals(0, v2RescheduleDup.getResetReservationCount());
        assertEquals(0, v2RescheduleDup.getScheduleNotifiedCount());

        // 等待原本 2 秒后触发的旧 v1 延迟消息被真实 RocketMQ 投递并由消费端按版本守卫丢弃
        Thread.sleep(3500);
        assertEquals(0, countingNotifier.countFor(v1Rsv.getReservationId()), "Stale v1 message must be dropped after reschedule to v2");
        assertEquals(ReservationDO.REMIND_PENDING, reservationMapper.selectById(v1Rsv.getReservationId()).getRemindStatus());
        List<UserNotificationVO> notifsAfterPostpone = userNotificationService.listMyNotifications(userPostpone, 10);
        assertEquals(1, notifsAfterPostpone.size());
        assertEquals(UserNotificationDO.TYPE_SCHEDULE_CHANGED, notifsAfterPostpone.get(0).getNotificationType());
        assertEquals(2, notifsAfterPostpone.get(0).getSaleConfigVersion());

        // 将排期调整为近窗口 v3 -> 自动联动重排 -> 真实 RocketMQ 送达 v3 且同事务落 1 条 REMIND_BEFORE_SALE，重放仍只保留 1 条
        LocalDateTime v3Start = LocalDateTime.now().plusMinutes(5);
        ShowBasicDTO v3Show = showScheduleService.reschedule(LIVE_SHOW_RESCHEDULE_POSTPONE, v3Start, v3Start.plusDays(10));
        assertEquals(3, v3Show.getSaleConfigVersion());
        awaitRemindSent(v1Rsv.getReservationId(), 15);
        assertEquals(1, countingNotifier.countFor(v1Rsv.getReservationId()));

        List<UserNotificationVO> notifs = userNotificationService.listMyNotifications(userPostpone, 10);
        List<UserNotificationVO> remindNotifs = notifs.stream()
                .filter(n -> UserNotificationDO.TYPE_REMIND_BEFORE_SALE.equals(n.getNotificationType()))
                .toList();
        assertEquals(1, remindNotifs.size());
        assertEquals(3, remindNotifs.get(0).getSaleConfigVersion());
        assertEquals("rsv:" + v1Rsv.getReservationId() + ":v3", remindNotifs.get(0).getBizKey());
        assertFalse(remindNotifs.get(0).isRead());

        // 标记已读 + 重放 v3 事件验证通知严格不重复
        UserNotificationVO afterRead = userNotificationService.markAsRead(userPostpone, remindNotifs.get(0).getNotificationId());
        assertTrue(afterRead.isRead());
        showRemindConsumer.onMessage(ShowRemindEvent.builder()
                .reservationId(v1Rsv.getReservationId())
                .userId(userPostpone)
                .showId(LIVE_SHOW_RESCHEDULE_POSTPONE)
                .showTitle("改期推迟验证专场")
                .saleStartTime(v3Start)
                .saleConfigVersion(3)
                .traceId("replay-v3")
                .build());
        assertEquals(notifs.size(), userNotificationService.listMyNotifications(userPostpone, 10).size());

        // === 2. 提前改期至已开售：远期 v1(status=0) -> 提前至已开售(v2) -> 置 4(SKIPPED)、即时落 SCHEDULE_CHANGED；
        //       未预热前 entryOpen=false，预热就绪后 /me 毫秒级开放 entryOpen=true & ENTER_SECKILL ===
        insertShow(LIVE_SHOW_RESCHEDULE_ADVANCE, "改期提前验证专场", LocalDateTime.now().plusDays(10));
        if (ticketTierMapper.selectById(910151L) == null) {
            ticketTierMapper.insert(TicketTierDO.builder()
                    .id(910151L).showId(LIVE_SHOW_RESCHEDULE_ADVANCE).tierName("内场").zoneId("ADV_A")
                    .priceCent(68000L).totalStock(100).availableStock(100).version(0).deleted(0).build());
        }
        ReservationVO advRsv = reservationService.reserve(userAdvance, LIVE_SHOW_RESCHEDULE_ADVANCE);
        assertEquals(ReservationDO.REMIND_PENDING, reservationMapper.selectById(advRsv.getReservationId()).getRemindStatus());

        LocalDateTime alreadyStarted = LocalDateTime.now().minusMinutes(1);
        showScheduleService.reschedule(LIVE_SHOW_RESCHEDULE_ADVANCE, alreadyStarted, alreadyStarted.plusDays(10));

        ReservationDO advAfter = reservationMapper.selectById(advRsv.getReservationId());
        assertEquals(ReservationDO.REMIND_SKIPPED, advAfter.getRemindStatus());
        MyReservationVO myAdvBeforePreheat = reservationService.getMyReservation(userAdvance, LIVE_SHOW_RESCHEDULE_ADVANCE);
        assertFalse(myAdvBeforePreheat.isEntryOpen(), "Phase 2: 未预热前即使已过开售时刻也不得误放行入口");
        assertEquals("READY", showPreheatService.preheat(LIVE_SHOW_RESCHEDULE_ADVANCE, 2).getTaskStatus());

        MyReservationVO myAdv = reservationService.getMyReservation(userAdvance, LIVE_SHOW_RESCHEDULE_ADVANCE);
        assertTrue(myAdv.isReserved());
        assertTrue(myAdv.isRealNameVerified());
        assertNotNull(myAdv.getServerTime());
        assertEquals(2, myAdv.getSaleConfigVersion());
        assertEquals(ReservationDO.REMIND_SKIPPED, myAdv.getRemindStatus());
        assertEquals("SKIPPED_ALREADY_ON_SALE", myAdv.getRemindStatusLabel());
        assertTrue(myAdv.isEntryOpen());
        assertEquals("ENTER_SECKILL", myAdv.getNextAction());
        List<UserNotificationVO> advNotifs = userNotificationService.listMyNotifications(userAdvance, 10);
        assertEquals(1, advNotifs.size());
        assertEquals(UserNotificationDO.TYPE_SCHEDULE_CHANGED, advNotifs.get(0).getNotificationType());
        assertEquals(1, reservationService.listMyReservations(userAdvance, 20).size());

        // === 3. 真实 Redis + MySQL SingleFlight 并发回源合并验证 ===
        insertShow(LIVE_SHOW_SINGLE_FLIGHT, "SingleFlight回源合并专场", LocalDateTime.now().plusDays(10));
        for (int i = 0; i < 5; i++) {
            insertRawReservation(idGenerator.nextId(), 9003000L + i, LIVE_SHOW_SINGLE_FLIGHT, ReservationDO.REMIND_SENT, LocalDateTime.now());
        }
        stringRedisTemplate.delete(RedisKeys.reservationCount(LIVE_SHOW_SINGLE_FLIGHT));
        reservationCountStore.resetDbFallbackCount();

        int readers = 24;
        ExecutorService pool = Executors.newFixedThreadPool(readers);
        try {
            CyclicBarrier barrier = new CyclicBarrier(readers);
            List<Future<Long>> results = new ArrayList<>();
            for (int i = 0; i < readers; i++) {
                results.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return reservationService.getReservationCount(LIVE_SHOW_SINGLE_FLIGHT).getCount();
                }));
            }
            for (Future<Long> f : results) {
                assertEquals(5L, f.get(15, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1L, reservationCountStore.getDbFallbackCount(),
                "24 concurrent cache misses on live Redis+MySQL must coalesce into exact 1 DB fallback");
    }

    private void assertAllCountsMatch(long showId, long expected, String phase) {
        long dbCount = reservationCountStore.countByShowId(showId);
        long getCount = reservationService.getReservationCount(showId).getCount();
        Object redisRaw = stringRedisTemplate.opsForHash().get(RedisKeys.reservationCount(showId), "count");
        assertNotNull(redisRaw, phase + ": Redis hash count field must exist");
        long redisCount = Long.parseLong(String.valueOf(redisRaw));
        assertEquals(expected, dbCount, phase + ": MySQL count mismatch");
        assertEquals(expected, getCount, phase + ": GET API count mismatch");
        assertEquals(expected, redisCount, phase + ": Redis hash count mismatch");
    }

    private void awaitRemindSent(long reservationId, int maxSeconds) throws InterruptedException {
        for (int i = 0; i < maxSeconds * 2; i++) {
            ReservationDO row = reservationMapper.selectById(reservationId);
            if (row != null && Integer.valueOf(ReservationDO.REMIND_SENT).equals(row.getRemindStatus())) {
                return;
            }
            Thread.sleep(500);
        }
        fail("Timed out waiting for reservationId=" + reservationId + " to reach remind_status=1");
    }

    private void insertShow(long showId, String title, LocalDateTime saleStart) {
        showMapper.insert(ShowDO.builder()
                .id(showId)
                .title(title)
                .artist("StarShow Live Test")
                .city("深圳")
                .venue("测试场馆")
                .showTime(saleStart.plusDays(15))
                .saleStartTime(saleStart)
                .saleEndTime(saleStart.plusDays(14))
                .saleStatus("INIT")
                .saleConfigVersion(1)
                .seatMode("AUTO_ALLOC")
                .posterUrl("")
                .description("Live verification show")
                .deleted(0)
                .build());
    }

    private void insertRawReservation(long id, long userId, long showId, int status, LocalDateTime nextRetryTime) {
        reservationMapper.insert(ReservationDO.builder()
                .id(id)
                .userId(userId)
                .showId(showId)
                .remindStatus(status)
                .remindConfigVersion(1)
                .nextRetryTime(nextRetryTime)
                .deleted(0)
                .build());
    }

    private void ensureVerifiedUser(long userId) {
        userIdentityMapper.insert(UserIdentityDO.builder()
                .userId(userId)
                .realNameCipher("live-cipher-name-" + userId)
                .idCardCipher("live-cipher-card-" + userId)
                .idCardMasked("3101**********" + String.format("%04d", userId % 10000))
                .idCardHmac(String.format("%064d", userId))
                .verifiedTime(LocalDateTime.now())
                .deleted(0)
                .build());
    }

    private ObjectProvider<RocketMQTemplate> flakyProvider(RocketMQTemplate delegate, Callable<Void> beforeSend) {
        RocketMQTemplate spy = org.mockito.Mockito.spy(delegate);
        org.mockito.Mockito.doAnswer(inv -> {
            beforeSend.call();
            return inv.callRealMethod();
        }).when(spy).syncSendDeliverTimeMills(eq(MqTopics.SHOW_REMIND),
                org.mockito.ArgumentMatchers.any(Message.class), org.mockito.ArgumentMatchers.anyLong());
        return fixedProvider(spy);
    }

    private ObjectProvider<RocketMQTemplate> fixedProvider(RocketMQTemplate template) {
        return new ObjectProvider<>() {
            @Override
            public RocketMQTemplate getObject(Object... args) {
                return template;
            }

            @Override
            public RocketMQTemplate getIfAvailable() {
                return template;
            }

            @Override
            public RocketMQTemplate getIfUnique() {
                return template;
            }

            @Override
            public RocketMQTemplate getObject() {
                return template;
            }
        };
    }

    @TestConfiguration
    static class CountingNotifierConfig {
        @Bean
        @Primary
        public CountingNotifier countingNotifier() {
            return new CountingNotifier();
        }
    }

    static class CountingNotifier implements ShowRemindNotifier {
        private final Map<Long, AtomicInteger> counts = new ConcurrentHashMap<>();

        @Override
        public void notifyRemind(ShowRemindEvent event) {
            counts.computeIfAbsent(event.getReservationId(), k -> new AtomicInteger(0)).incrementAndGet();
        }

        int countFor(long reservationId) {
            AtomicInteger c = counts.get(reservationId);
            return c == null ? 0 : c.get();
        }

        void reset() {
            counts.clear();
        }
    }
}
