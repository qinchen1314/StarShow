package com.starshow.boot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshow.api.dto.ShowBasicDTO;
import com.starshow.api.dto.TierSnapshotDTO;
import com.starshow.api.facade.ShowReadFacade;
import com.starshow.common.constant.RedisKeys;
import com.starshow.common.exception.BizException;
import com.starshow.common.id.GeneSnowflakeIdGenerator;
import com.starshow.common.result.ErrorCode;
import com.starshow.show.cache.ShowCatalogBloomManager;
import com.starshow.show.cache.ShowDetailCacheService;
import com.starshow.show.cache.ShowGateEvaluator;
import com.starshow.show.config.ShowMetrics;
import com.starshow.show.domain.entity.SeatDO;
import com.starshow.show.domain.entity.ShowDO;
import com.starshow.show.domain.entity.ShowTaskDO;
import com.starshow.show.domain.entity.TicketTierDO;
import com.starshow.show.domain.model.ShowDetailCachePayload;
import com.starshow.show.domain.model.ShowReadyState;
import com.starshow.show.lua.ShowRedisScripts;
import com.starshow.show.mapper.SeatMapper;
import com.starshow.show.mapper.ShowMapper;
import com.starshow.show.mapper.ShowTaskMapper;
import com.starshow.show.mapper.TicketTierMapper;
import com.starshow.show.service.SeatMapQueryService;
import com.starshow.show.service.ShowPreheatService;
import com.starshow.show.service.ShowQueryService;
import com.starshow.show.service.ShowSaleLifecycleService;
import com.starshow.show.service.ShowScheduleService;
import com.starshow.show.service.ShowStockQueryService;
import com.starshow.show.service.ShowTaskRecoveryScheduler;
import com.starshow.show.util.ShowTimeUtil;
import com.starshow.show.vo.SeatMapVO;
import com.starshow.show.vo.ShowDetailVO;
import com.starshow.show.vo.ShowListVO;
import com.starshow.show.vo.ShowPreheatResultVO;
import com.starshow.show.vo.ShowStockVO;
import com.starshow.user.domain.entity.UserIdentityDO;
import com.starshow.user.domain.entity.UserNotificationDO;
import com.starshow.user.mapper.ReservationMapper;
import com.starshow.user.mapper.UserIdentityMapper;
import com.starshow.user.mapper.UserNotificationMapper;
import com.starshow.user.service.ReservationService;
import com.starshow.user.service.UserNotificationService;
import com.starshow.user.vo.MyReservationVO;
import com.starshow.user.vo.UserNotificationVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 链路二（Phase 2）真实 Docker 中间件（MySQL 8 + Redis 7 + RocketMQ 5.3）Prove-It 实证门禁套件：
 * 仅在显式开启 -Dstarshow.live.enabled=true 且绑定隔离 MySQL 库 + 非 0 Redis DB 时运行！
 * 覆盖：
 * 1. 并发 100 请求读取同一逻辑过期热点详情 -> MySQL 重建次数严格 == 1；
 * 2. 布隆目录拦截批量不存在 showId -> 详情回源 SQL == 0，且新增场次同步后不被旧布隆永久误杀；
 * 3. 预热容错五连测：幂等 / 中途崩溃换新 executionId 续跑 / 失租约旧写入被 Lua 拒绝 / 改期后旧版任务复活被拒绝 / ON_SALE 后晚到 0 覆盖；
 * 4. 10002 10x10 座位图 MSB 解码与走道 segmentId + 四态展示（PREPARING / 503 B0201 / SOLD_OUT / ENDED）绝不混淆；
 * 5. 10003 跨链路提前/推迟改期闭环：链路一通知、我的预约 entryOpen、详情页、预热版本与毫秒级开售门禁 100% 一致。
 */
@EnabledIfSystemProperty(named = "starshow.live.enabled", matches = "true")
@SpringBootTest(
        classes = StarShowBootAllApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "starshow.reservation.recovery-enabled=false",
                "starshow.reservation.mq-enabled=true",
                "starshow.reservation.test-override-delay-ms=0",
                "starshow.show.task-recovery-enabled=false",
                "starshow.show.lifecycle-sync-enabled=false",
                "starshow.show.bloom-enabled=true",
                "knife4j.enable=false"
        }
)
@ActiveProfiles("monolith")
class Chain2LiveMiddlewareVerificationTest {

    private static final long LIVE_HOT_DETAIL_SHOW = 92001L;
    private static final long LIVE_NEW_SHOW_BLOOM = 92002L;
    private static final long LIVE_PREHEAT_SHOW = 92003L;
    private static final long LIVE_FOUR_STATE_SHOW = 10002L;
    private static final long LIVE_CROSS_CHAIN_SHOW = 10003L;

    @Autowired
    private ShowMapper showMapper;
    @Autowired
    private TicketTierMapper ticketTierMapper;
    @Autowired
    private SeatMapper seatMapper;
    @Autowired
    private ShowTaskMapper showTaskMapper;
    @Autowired
    private ShowQueryService showQueryService;
    @Autowired
    private ShowStockQueryService showStockQueryService;
    @Autowired
    private SeatMapQueryService seatMapQueryService;
    @Autowired
    private ShowPreheatService showPreheatService;
    @Autowired
    private ShowScheduleService showScheduleService;
    @Autowired
    private ShowSaleLifecycleService showSaleLifecycleService;
    @Autowired
    private ShowTaskRecoveryScheduler showTaskRecoveryScheduler;
    @Autowired
    private ShowDetailCacheService detailCacheService;
    @Autowired
    private ShowCatalogBloomManager bloomManager;
    @Autowired
    private ShowRedisScripts redisScripts;
    @Autowired
    private ShowGateEvaluator gateEvaluator;
    @Autowired
    private ShowReadFacade showReadFacade;
    @Autowired
    private ShowMetrics showMetrics;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private GeneSnowflakeIdGenerator idGenerator;

    @Autowired
    private ReservationService reservationService;
    @Autowired
    private ReservationMapper reservationMapper;
    @Autowired
    private UserIdentityMapper userIdentityMapper;
    @Autowired
    private UserNotificationMapper userNotificationMapper;
    @Autowired
    private UserNotificationService userNotificationService;

    @BeforeEach
    void resetLocalState() {
        detailCacheService.clearLocalCaches();
        showMetrics.resetCountersForTest();
    }

    @Test
    void test1_concurrent100RequestsOnExpiredHotDetailShouldRebuildMysqlExactlyOnce() throws Exception {
        LocalDateTime now = ShowTimeUtil.nowLocalDateTime();
        upsertShow(LIVE_HOT_DETAIL_SHOW, "热点逻辑过期测试场次", "上海", "AUTO_ALLOC", "INIT",
                now.plusHours(4), now.plusDays(10), 1);
        upsertTier(92101L, LIVE_HOT_DETAIL_SHOW, "内场VIP", "VIP_1", 128000L, 500, 500);

        // 先写入一条已经逻辑过期 10 秒（仍在最大陈旧 900s 内）的 Redis L2 快照，并清空 L1
        long nowMs = System.currentTimeMillis();
        ShowDetailCachePayload stalePayload = ShowDetailCachePayload.builder()
                .notFound(false)
                .showId(LIVE_HOT_DETAIL_SHOW)
                .saleConfigVersion(1)
                .title("旧逻辑过期快照")
                .artist("StarShow")
                .city("上海")
                .venue("测试场馆")
                .showTimeEpochMs(ShowTimeUtil.toEpochMs(now.plusDays(15)))
                .saleStartEpochMs(ShowTimeUtil.toEpochMs(now.plusHours(4)))
                .saleEndEpochMs(ShowTimeUtil.toEpochMs(now.plusDays(10)))
                .saleStatus("INIT")
                .seatMode("AUTO_ALLOC")
                .tiers(List.of(ShowDetailCachePayload.CachedTier.builder()
                        .tierId(92101L).tierName("内场VIP").zoneId("VIP_1").priceCent(128000L).totalStock(500).build()))
                .refreshedAtEpochMs(nowMs - 400_000L)
                .expireAtEpochMs(nowMs - 10_000L)
                .build();
        stringRedisTemplate.opsForValue().set(
                RedisKeys.showDetail(LIVE_HOT_DETAIL_SHOW),
                objectMapper.writeValueAsString(stalePayload),
                Duration.ofHours(1)
        );
        stringRedisTemplate.delete(RedisKeys.showDetailRebuildLock(LIVE_HOT_DETAIL_SHOW));
        detailCacheService.clearLocalCaches();
        showMetrics.resetCountersForTest();

        int concurrency = 100;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CyclicBarrier barrier = new CyclicBarrier(concurrency);
        List<Future<ShowDetailCachePayload>> futures = new ArrayList<>(concurrency);
        for (int i = 0; i < concurrency; i++) {
            futures.add(pool.submit((Callable<ShowDetailCachePayload>) () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return detailCacheService.getDetail(LIVE_HOT_DETAIL_SHOW);
            }));
        }
        for (Future<ShowDetailCachePayload> f : futures) {
            ShowDetailCachePayload p = f.get(15, TimeUnit.SECONDS);
            assertNotNull(p);
            assertEquals(LIVE_HOT_DETAIL_SHOW, p.getShowId());
        }
        pool.shutdownNow();

        // 等待异步重建完成并写回新快照
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            String raw = stringRedisTemplate.opsForValue().get(RedisKeys.showDetail(LIVE_HOT_DETAIL_SHOW));
            if (raw != null && raw.contains("热点逻辑过期测试场次")) {
                break;
            }
            Thread.sleep(30);
        }

        // 硬断言：100 并发下 MySQL 重建次数严格 == 1
        assertEquals(1L, showMetrics.getDetailDbRebuilds(),
                "100 concurrent reads on logically expired detail must rebuild MySQL strictly once");
        detailCacheService.clearLocalCaches();
        ShowDetailCachePayload refreshed = detailCacheService.getDetail(LIVE_HOT_DETAIL_SHOW);
        assertEquals("热点逻辑过期测试场次", refreshed.getTitle());
        assertFalse(refreshed.isLogicallyExpired(System.currentTimeMillis()));
    }

    @Test
    void test2_bloomShouldBlockBatchMissingIdsWithZeroSqlAndNeverPermanentlyBlockNewShow() {
        // 先确保 92002 不存在并同步布隆目录
        showMapper.deleteById(LIVE_NEW_SHOW_BLOOM);
        stringRedisTemplate.delete(RedisKeys.showDetail(LIVE_NEW_SHOW_BLOOM));
        detailCacheService.clearLocalCaches();

        int synced = bloomManager.syncCatalogBloom();
        assertTrue(synced >= 3, "Catalog bloom must load active shows");
        showMetrics.resetCountersForTest();

        // 批量访问 50 个不存在的 showId -> 详情回源 SQL 严格 == 0
        for (long missingId = 990001L; missingId <= 990050L; missingId++) {
            assertNull(detailCacheService.getDetail(missingId));
        }
        assertEquals(0L, showMetrics.getDetailDbRebuilds(),
                "Batch missing showIds blocked by bloom must cause 0 MySQL detail queries");
        assertTrue(showMetrics.getDetailBloomRejects() >= 45,
                "Expected vast majority of missing IDs intercepted by bloom filter");

        // 直接往 DB 新增一条此前不存在的新场次 92002，经目录同步后立即可见，绝不被旧布隆永久误杀
        LocalDateTime now = ShowTimeUtil.nowLocalDateTime();
        upsertShow(LIVE_NEW_SHOW_BLOOM, "新增布隆自愈场次", "广州", "AUTO_ALLOC", "INIT",
                now.plusHours(6), now.plusDays(10), 1);
        upsertTier(92102L, LIVE_NEW_SHOW_BLOOM, "看台票", "GZ_A", 48000L, 200, 200);

        bloomManager.syncCatalogBloom();
        detailCacheService.clearLocalCaches();
        ShowDetailVO newShowDetail = showQueryService.getShowDetail(LIVE_NEW_SHOW_BLOOM);
        assertNotNull(newShowDetail);
        assertEquals("新增布隆自愈场次", newShowDetail.getTitle());

        // 顺带验证稳定游标列表与城市筛选
        ShowListVO gzList = showQueryService.listShows("广州", null, 10);
        assertTrue(gzList.getItems().stream().anyMatch(i -> i.getShowId().equals(LIVE_NEW_SHOW_BLOOM)));
    }

    @Test
    void test3_preheatFaultToleranceFiveCaseSuite() throws Exception {
        LocalDateTime now = ShowTimeUtil.nowLocalDateTime();
        long showId = LIVE_PREHEAT_SHOW;
        long tierId = 92103L;
        cleanupShowRedis(showId, List.of(tierId), List.of("ZONE_P"));
        showTaskMapper.delete(new LambdaQueryWrapper<ShowTaskDO>().eq(ShowTaskDO::getShowId, showId));
        seatMapper.delete(new LambdaQueryWrapper<SeatDO>().eq(SeatDO::getShowId, showId));
        upsertShow(showId, "预热容错五连测专场", "杭州", "MANUAL_SEAT", "INIT",
                now.plusHours(2), now.plusDays(10), 1);
        upsertTier(tierId, showId, "选座测试区", "ZONE_P", 68000L, 10, 10);
        for (int i = 0; i < 10; i++) {
            seatMapper.insert(SeatDO.builder()
                    .showId(showId).tierId(tierId).zoneId("ZONE_P")
                    .seatIndex(i).rowNo(1).colNo(i + 1).segmentId(i < 5 ? "R1-L" : "R1-R")
                    .status(0).deleted(0).build());
        }

        // --- 场景 1：同版本重复预热幂等 ---
        ShowPreheatResultVO r1 = showPreheatService.preheat(showId, 1);
        assertEquals("READY", r1.getTaskStatus());
        assertEquals(1, r1.getSaleConfigVersion());
        assertEquals("10", stringRedisTemplate.opsForValue().get(RedisKeys.stock(showId, tierId)));

        ShowPreheatResultVO r1Repeat = showPreheatService.preheat(showId, 1);
        assertEquals("READY", r1Repeat.getTaskStatus());
        assertEquals(r1.getExecutionId(), r1Repeat.getExecutionId(), "Idempotent preheat must keep same executionId");

        // --- 场景 2：模拟预热中途崩溃（租约过期 RUNNING 任务 + 半写状态），由新 executionId 抢占续跑收敛 ---
        // 先改期至 v2（顺带验证 PREHEATED 改期），随后手工构造一个已崩溃（租约过期）的 v2 RUNNING 任务与脏库存
        ShowBasicDTO v2Show = showScheduleService.reschedule(showId, now.plusHours(3), now.plusDays(10));
        assertEquals(2, v2Show.getSaleConfigVersion());
        assertEquals("INIT", v2Show.getSaleStatus());

        String crashedExecId = "crashed-exec-v2-old";
        showMapper.casMarkPreheating(showId, 2);
        long beginCrashed = redisScripts.preheatBegin(showId, 2, crashedExecId,
                ShowTimeUtil.toEpochMs(now.plusHours(3)), ShowTimeUtil.toEpochMs(now.plusDays(10)), System.currentTimeMillis());
        assertEquals(1L, beginCrashed);
        // 旧崩溃任务只写了一半（库存写错成 999 且未写位图就挂了）
        assertTrue(redisScripts.preheatWriteStockBatch(showId, 2, crashedExecId, Map.of(tierId, 999)));
        showTaskMapper.insert(ShowTaskDO.builder()
                .taskId(idGenerator.nextId())
                .showId(showId)
                .taskType(ShowTaskDO.TYPE_PREHEAT)
                .version(2)
                .executionId(crashedExecId)
                .status(ShowTaskDO.STATUS_RUNNING)
                .retryable(1)
                .attempt(1)
                .nextRetryAt(now.minusMinutes(2))
                .leaseUntil(now.minusSeconds(30)) // 租约已过期 -> 视为崩溃
                .deleted(0)
                .build());

        // 恢复调度器介入：换新 executionId 续跑收敛至 READY & PREHEATED，且库存校正为 10
        int recovered = showTaskRecoveryScheduler.recoverPendingTasks();
        assertTrue(recovered >= 1);
        ShowTaskDO recoveredTask = showTaskMapper.selectByShowTypeVersion(showId, ShowTaskDO.TYPE_PREHEAT, 2);
        assertEquals(ShowTaskDO.STATUS_SUCCEEDED, recoveredTask.getStatus());
        assertNotEquals(crashedExecId, recoveredTask.getExecutionId());
        assertEquals("10", stringRedisTemplate.opsForValue().get(RedisKeys.stock(showId, tierId)));
        assertEquals("PREHEATED", showMapper.selectById(showId).getSaleStatus());

        // --- 场景 3：失去租约的旧任务（crashedExecId）醒来试图覆盖库存/位图 -> Lua 全部原子拒绝（0 覆盖） ---
        assertFalse(redisScripts.preheatWriteStockBatch(showId, 2, crashedExecId, Map.of(tierId, 777)),
                "Lost-lease executionId must be rejected by Lua");
        assertFalse(redisScripts.preheatWriteSeatZone(showId, "ZONE_P", 2, crashedExecId, new byte[]{(byte) 0xFF, (byte) 0xFF}, "{}"),
                "Lost-lease seat write must be rejected by Lua");
        assertEquals("10", stringRedisTemplate.opsForValue().get(RedisKeys.stock(showId, tierId)));

        // --- 场景 4：改期至 v3 后，旧版本 v2 任务复活被拒绝 ---
        ShowBasicDTO v3Show = showScheduleService.reschedule(showId, now.minusMinutes(1), now.plusDays(10));
        assertEquals(3, v3Show.getSaleConfigVersion());
        BizException staleVersionPreheat = assertThrows(BizException.class, () -> showPreheatService.preheat(showId, 2));
        assertEquals(ErrorCode.SHOW_CONFIG_VERSION_CHANGED, staleVersionPreheat.getErrorCode());
        assertEquals(-2L, redisScripts.preheatBegin(showId, 2, "zombie-v2",
                ShowTimeUtil.toEpochMs(now.plusHours(3)), ShowTimeUtil.toEpochMs(now.plusDays(10)), System.currentTimeMillis()));

        // --- 场景 5：完成 v3 预热并进入 ON_SALE（模拟已售出 3 张剩 7 张）-> 任何晚到旧任务 0 覆盖在售库存 ---
        ShowPreheatResultVO r3 = showPreheatService.preheat(showId, 3);
        assertEquals("READY", r3.getTaskStatus());
        int promoted = showSaleLifecycleService.syncSaleLifecycle();
        assertTrue(promoted >= 1);
        assertEquals("ON_SALE", showMapper.selectById(showId).getSaleStatus());
        assertEquals(ShowReadyState.STAGE_OPEN, redisScripts.readReadyState(showId).getStage());

        // 模拟真实售出 3 张，Redis 实时库存变为 7
        stringRedisTemplate.opsForValue().set(RedisKeys.stock(showId, tierId), "7");

        // 晚到旧任务（无论是旧版本还是同版本重放 Lua）均不得覆盖在售库存 7！
        assertEquals(-1L, redisScripts.preheatBegin(showId, 3, "late-v3",
                ShowTimeUtil.toEpochMs(now.minusMinutes(1)), ShowTimeUtil.toEpochMs(now.plusDays(10)), System.currentTimeMillis()));
        assertFalse(redisScripts.preheatWriteStockBatch(showId, 3, r3.getExecutionId(), Map.of(tierId, 10)));
        // 已开售同版本再调 preheat 只读返回 READY，绝对不能重置库存 7 -> 10
        ShowPreheatResultVO afterOnSaleRepeat = showPreheatService.preheat(showId, 3);
        assertEquals("READY", afterOnSaleRepeat.getTaskStatus());
        assertEquals("7", stringRedisTemplate.opsForValue().get(RedisKeys.stock(showId, tierId)),
                "In-sale Redis stock must NEVER be overwritten after ON_SALE");
    }

    @Test
    void test4_show10002SeatMapMsbAndFourStatesMustNeverBeConfused() {
        long showId = LIVE_FOUR_STATE_SHOW;
        long tierId = 20004L;
        String zoneId = "HALL_A";
        LocalDateTime now = ShowTimeUtil.nowLocalDateTime();

        // 将 10002 设为未开售 INIT 准备演练四态全过程
        cleanupShowRedis(showId, List.of(tierId), List.of(zoneId));
        showTaskMapper.delete(new LambdaQueryWrapper<ShowTaskDO>().eq(ShowTaskDO::getShowId, showId));
        ShowDO show = showMapper.selectById(showId);
        assertNotNull(show, "Seed show 10002 must exist in isolated DB");
        show.setSaleStatus("INIT");
        show.setSaleConfigVersion(1);
        show.setSaleStartTime(now.plusHours(1));
        show.setSaleEndTime(now.plusDays(10));
        showMapper.updateById(show);
        detailCacheService.refreshFromDb(showId);

        // 态 1：PREPARING（已预热但未到开售时刻）
        ShowPreheatResultVO preheat = showPreheatService.preheat(showId, 1);
        assertEquals("READY", preheat.getTaskStatus());
        ShowDetailVO preparingDetail = showQueryService.getShowDetail(showId);
        assertEquals("PREPARING", preparingDetail.getEntryState());
        ShowStockVO preparingStock = showStockQueryService.getShowStock(showId);
        assertEquals("PREPARING", preparingStock.getEntryState());
        SeatMapVO preparingSeats = seatMapQueryService.getZoneSeatMap(showId, zoneId);
        assertEquals("PREPARING", preparingSeats.getStockState());
        assertFalse(preparingSeats.isAvailable());
        assertEquals(100, preparingSeats.getSeats().size());
        assertTrue(preparingSeats.getSeats().stream().noneMatch(SeatMapVO.SeatItem::isSelectable));

        // 毫秒级准点开放：改期到当前已开售窗口并重新预热（注意不运行后台定时任务，直接验证 stage=READY 毫秒级开放！）
        ShowBasicDTO rescheduled = showScheduleService.reschedule(showId, now.minusSeconds(2), now.plusHours(2));
        assertEquals(2, rescheduled.getSaleConfigVersion());
        ShowPreheatResultVO v2Preheat = showPreheatService.preheat(showId, 2);
        assertEquals("READY", v2Preheat.getTaskStatus());
        // 此时 DB 为 PREHEATED、Redis 为 READY（尚未推进 ON_SALE/OPEN），但服务端时间已过 saleStartEpochMs -> 毫秒级立即 OPEN！
        ShowDetailVO openDetail = showQueryService.getShowDetail(showId);
        assertEquals("OPEN", openDetail.getEntryState());

        // 使用 Redis SETBIT 按 MSB 占用 offset 0(R1C1)、7(R1C8)、8(R1C9)、99(R10C10)，同步减库存为 96
        String bitmapKey = RedisKeys.seatBitmap(showId, zoneId);
        stringRedisTemplate.opsForValue().setBit(bitmapKey, 0, true);
        stringRedisTemplate.opsForValue().setBit(bitmapKey, 7, true);
        stringRedisTemplate.opsForValue().setBit(bitmapKey, 8, true);
        stringRedisTemplate.opsForValue().setBit(bitmapKey, 99, true);
        stringRedisTemplate.opsForValue().set(RedisKeys.stock(showId, tierId), "96");

        SeatMapVO openSeatMap = seatMapQueryService.getZoneSeatMap(showId, zoneId);
        assertTrue(openSeatMap.isAvailable());
        assertEquals("OPEN", openSeatMap.getStockState());
        assertEquals(2, openSeatMap.getLayoutVersion());
        assertEquals(100, openSeatMap.getSeats().size());
        Map<Integer, SeatMapVO.SeatItem> byIdx = new LinkedHashMap<>();
        for (SeatMapVO.SeatItem item : openSeatMap.getSeats()) {
            byIdx.put(item.getSeatIndex(), item);
        }
        assertFalse(byIdx.get(0).isSelectable());
        assertEquals("R1-L", byIdx.get(0).getSegmentId());
        assertTrue(byIdx.get(3).isSelectable());
        assertEquals("R1-M", byIdx.get(3).getSegmentId());
        assertFalse(byIdx.get(7).isSelectable());
        assertEquals("R1-R", byIdx.get(7).getSegmentId());
        assertFalse(byIdx.get(8).isSelectable());
        assertTrue(byIdx.get(98).isSelectable());
        assertFalse(byIdx.get(99).isSelectable());
        assertEquals("R10-R", byIdx.get(99).getSegmentId());
        long selectableTotal = openSeatMap.getSeats().stream().filter(SeatMapVO.SeatItem::isSelectable).count();
        assertEquals(96L, selectableTotal);

        // 态 2：已开售但 Redis 键缺失 -> STOCK_UNAVAILABLE (503/B0201)，绝不误报 SOLD_OUT！
        stringRedisTemplate.delete(RedisKeys.stock(showId, tierId));
        gateEvaluator.invalidateLocalStockCache(showId);
        ShowDetailVO missingStockDetail = showQueryService.getShowDetail(showId);
        assertEquals("STOCK_UNAVAILABLE", missingStockDetail.getEntryState());
        BizException stockMissingEx = assertThrows(BizException.class, () -> showStockQueryService.getShowStock(showId));
        assertEquals(ErrorCode.STOCK_UNAVAILABLE, stockMissingEx.getErrorCode());
        assertEquals(503, stockMissingEx.getHttpStatus());

        // 恢复库存但删除位图 Key -> 选座接口返回 503/B0201
        stringRedisTemplate.opsForValue().set(RedisKeys.stock(showId, tierId), "96");
        stringRedisTemplate.delete(bitmapKey);
        BizException bitmapMissingEx = assertThrows(BizException.class, () -> seatMapQueryService.getZoneSeatMap(showId, zoneId));
        assertEquals(ErrorCode.STOCK_UNAVAILABLE, bitmapMissingEx.getErrorCode());
        assertEquals(503, bitmapMissingEx.getHttpStatus());

        // 态 3：真实零余量 -> SOLD_OUT（HTTP 200，非 503）
        stringRedisTemplate.opsForValue().set(RedisKeys.stock(showId, tierId), "0");
        ShowStockVO soldOutStock = showStockQueryService.getShowStock(showId);
        assertEquals("SOLD_OUT", soldOutStock.getEntryState());
        assertEquals(0, soldOutStock.getTiers().get(0).getAvailable());

        // 态 4：到达停售时间 -> 即使库存仍有余量也必须返回 ENDED
        stringRedisTemplate.opsForValue().set(RedisKeys.stock(showId, tierId), "50");
        showSaleLifecycleService.syncSaleLifecycle(); // 先推进 ON_SALE
        ShowDO endingShow = showMapper.selectById(showId);
        endingShow.setSaleEndTime(now.minusSeconds(5));
        showMapper.updateById(endingShow);
        showSaleLifecycleService.syncSaleLifecycle(); // 推进 ENDED
        assertEquals("ENDED", showMapper.selectById(showId).getSaleStatus());
        ShowDetailVO endedDetail = showQueryService.getShowDetail(showId);
        assertEquals("ENDED", endedDetail.getEntryState());
        ShowStockVO endedStock = showStockQueryService.getShowStock(showId);
        assertEquals("ENDED", endedStock.getEntryState());

        // 顺带校验 TierSnapshotDTO 内部契约
        TierSnapshotDTO snap = showReadFacade.getTierSnapshot(showId, tierId);
        assertNotNull(snap);
        assertEquals(88000L, snap.getPriceCent());
        assertEquals(2, snap.getSaleConfigVersion());
        assertEquals("2", snap.getLayoutVersion());
        assertEquals(4, snap.getMaxTicketsPerOrder());
    }

    @Test
    void test5_crossChainReschedulePostponeAndAdvanceConsistencyOnShow10003() {
        long showId = LIVE_CROSS_CHAIN_SHOW;
        long userId = 920888L;
        LocalDateTime now = ShowTimeUtil.nowLocalDateTime();

        // 清理测试用户预约与通知，准备 10003 初始未开售状态
        reservationMapper.delete(new LambdaQueryWrapper<com.starshow.user.domain.entity.ReservationDO>()
                .eq(com.starshow.user.domain.entity.ReservationDO::getUserId, userId));
        userNotificationMapper.delete(new LambdaQueryWrapper<UserNotificationDO>()
                .eq(UserNotificationDO::getUserId, userId));
        userIdentityMapper.delete(new LambdaQueryWrapper<UserIdentityDO>()
                .eq(UserIdentityDO::getUserId, userId));
        userIdentityMapper.insert(UserIdentityDO.builder()
                .userId(userId)
                .realNameCipher("cipher-name")
                .idCardCipher("cipher-id")
                .idCardMasked("1101**********1234")
                .idCardHmac("hmac-chain2-live-" + userId)
                .verifiedTime(now)
                .deleted(0)
                .build());
        stringRedisTemplate.delete(RedisKeys.userVerified(userId));
        stringRedisTemplate.delete(RedisKeys.reservationBloom(showId));
        cleanupShowRedis(showId, List.of(20005L), List.of());
        showTaskMapper.delete(new LambdaQueryWrapper<ShowTaskDO>().eq(ShowTaskDO::getShowId, showId));

        ShowDO show = showMapper.selectById(showId);
        assertNotNull(show, "Seed show 10003 must exist in isolated DB");
        show.setSaleStatus("INIT");
        show.setSaleConfigVersion(1);
        show.setSaleStartTime(now.plusHours(12));
        show.setSaleEndTime(now.plusDays(20));
        showMapper.updateById(show);
        detailCacheService.refreshFromDb(showId);

        // 用户完成预约（锚定 v1）
        reservationService.reserve(userId, showId);
        MyReservationVO before = reservationService.getMyReservation(userId, showId);
        assertEquals(1, before.getSaleConfigVersion());
        assertFalse(before.isEntryOpen());
        assertEquals("WAIT_FOR_SALE", before.getNextAction());

        // 1. 推迟改期至 +5 天（v1 -> v2）：链路二自动联动链路一重排提醒并下发 SCHEDULE_CHANGED 通知
        LocalDateTime postponedStart = now.plusDays(5);
        ShowBasicDTO v2 = showScheduleService.reschedule(showId, postponedStart, postponedStart.plusDays(10));
        assertEquals(2, v2.getSaleConfigVersion());

        ShowDetailVO detailV2 = showQueryService.getShowDetail(showId);
        assertEquals(2, detailV2.getSaleConfigVersion());
        assertEquals("PREPARING", detailV2.getEntryState());
        MyReservationVO myV2 = reservationService.getMyReservation(userId, showId);
        assertEquals(2, myV2.getSaleConfigVersion());
        assertFalse(myV2.isEntryOpen());
        List<UserNotificationVO> notifsV2 = userNotificationService.listMyNotifications(userId, 20);
        assertTrue(notifsV2.stream().anyMatch(n -> "SCHEDULE_CHANGED".equals(n.getNotificationType())
                && Integer.valueOf(2).equals(n.getSaleConfigVersion())));

        // 2. 提前改期至已开售窗口（now - 2s，v2 -> v3）：未预热前入口必须保持关闭（PREPARING），绝不误放行！
        LocalDateTime advancedStart = now.minusSeconds(2);
        ShowBasicDTO v3 = showScheduleService.reschedule(showId, advancedStart, now.plusDays(5));
        assertEquals(3, v3.getSaleConfigVersion());

        ShowDetailVO detailV3BeforePreheat = showQueryService.getShowDetail(showId);
        assertEquals(3, detailV3BeforePreheat.getSaleConfigVersion());
        assertEquals("PREPARING", detailV3BeforePreheat.getEntryState());
        MyReservationVO myV3BeforePreheat = reservationService.getMyReservation(userId, showId);
        assertEquals(3, myV3BeforePreheat.getSaleConfigVersion());
        assertFalse(myV3BeforePreheat.isEntryOpen(), "Unpreheated show must keep entryOpen=false even past saleStartTime");

        // 3. 按新版本 v3 完成预热 -> 详情页与链路一我的预约入口毫秒级同步开放 OPEN！
        ShowPreheatResultVO preheatV3 = showPreheatService.preheat(showId, 3);
        assertEquals("READY", preheatV3.getTaskStatus());

        ShowDetailVO detailV3Open = showQueryService.getShowDetail(showId);
        assertEquals(3, detailV3Open.getSaleConfigVersion());
        assertEquals("OPEN", detailV3Open.getEntryState());
        MyReservationVO myV3Open = reservationService.getMyReservation(userId, showId);
        assertEquals(3, myV3Open.getSaleConfigVersion());
        assertTrue(myV3Open.isEntryOpen(), "Once v3 preheated and in sale window, Chain1 entryOpen must open immediately");
        assertEquals("ENTER_SECKILL", myV3Open.getNextAction());

        List<UserNotificationVO> notifsV3 = userNotificationService.listMyNotifications(userId, 20);
        assertTrue(notifsV3.stream().anyMatch(n -> "SCHEDULE_CHANGED".equals(n.getNotificationType())
                && Integer.valueOf(3).equals(n.getSaleConfigVersion())));
    }

    private void upsertShow(long showId, String title, String city, String seatMode, String saleStatus,
                            LocalDateTime saleStart, LocalDateTime saleEnd, int version) {
        ShowDO existing = showMapper.selectById(showId);
        ShowDO entity = ShowDO.builder()
                .id(showId)
                .title(title)
                .artist("StarShow Live")
                .city(city)
                .venue(city + "大剧院")
                .showTime(saleEnd.plusDays(1))
                .saleStartTime(saleStart)
                .saleEndTime(saleEnd)
                .saleStatus(saleStatus)
                .saleConfigVersion(version)
                .seatMode(seatMode)
                .posterUrl("/assets/poster-" + showId + ".jpg")
                .description("Live verification show " + showId)
                .deleted(0)
                .build();
        if (existing == null) {
            showMapper.insert(entity);
        } else {
            showMapper.updateById(entity);
        }
    }

    private void upsertTier(long tierId, long showId, String name, String zoneId,
                            long priceCent, int totalStock, int availStock) {
        TicketTierDO existing = ticketTierMapper.selectById(tierId);
        TicketTierDO entity = TicketTierDO.builder()
                .id(tierId)
                .showId(showId)
                .tierName(name)
                .zoneId(zoneId)
                .priceCent(priceCent)
                .totalStock(totalStock)
                .availableStock(availStock)
                .version(0)
                .deleted(0)
                .build();
        if (existing == null) {
            ticketTierMapper.insert(entity);
        } else {
            ticketTierMapper.updateById(entity);
        }
    }

    private void cleanupShowRedis(long showId, List<Long> tierIds, List<String> zoneIds) {
        stringRedisTemplate.delete(RedisKeys.showReady(showId));
        stringRedisTemplate.delete(RedisKeys.showDetail(showId));
        stringRedisTemplate.delete(RedisKeys.showDetailRebuildLock(showId));
        for (Long tierId : tierIds) {
            stringRedisTemplate.delete(RedisKeys.stock(showId, tierId));
        }
        for (String zoneId : zoneIds) {
            stringRedisTemplate.delete(RedisKeys.seatBitmap(showId, zoneId));
            stringRedisTemplate.delete(RedisKeys.seatLayout(showId, zoneId));
        }
    }
}
