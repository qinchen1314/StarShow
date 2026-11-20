package com.starshow.show.service;

import com.starshow.common.constant.RedisKeys;
import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.show.cache.ShowDetailCacheService;
import com.starshow.show.cache.ShowGateEvaluator;
import com.starshow.show.config.ShowMetrics;
import com.starshow.show.config.ShowProperties;
import com.starshow.show.domain.entity.SeatDO;
import com.starshow.show.domain.model.EntryState;
import com.starshow.show.domain.model.ShowDetailCachePayload;
import com.starshow.show.domain.model.ShowReadyState;
import com.starshow.show.lua.ShowRedisScripts;
import com.starshow.show.mapper.SeatMapper;
import com.starshow.show.mapper.ShowMapper;
import com.starshow.show.vo.SeatMapVO;
import com.starshow.show.vo.ShowStockVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * L1 单元测试：MSB 最高位优先 Bitmap 解码、稀疏偏移与走道 segmentId、毫秒级准点开售/停售边界与四态绝不误报
 */
@ExtendWith(MockitoExtension.class)
class SeatMapAndStockQueryTest {

    @Mock
    private ShowDetailCacheService detailCacheService;
    @Mock
    private ShowRedisScripts redisScripts;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private ShowMapper showMapper;
    @Mock
    private SeatMapper seatMapper;

    private ShowProperties properties;
    private ShowMetrics metrics;
    private ShowGateEvaluator gateEvaluator;
    private ShowStockQueryService stockQueryService;
    private SeatMapQueryService seatMapQueryService;

    @BeforeEach
    void setUp() {
        properties = new ShowProperties();
        metrics = new ShowMetrics();
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        gateEvaluator = new ShowGateEvaluator(
                providerOf(redisScripts),
                providerOf(stringRedisTemplate),
                showMapper,
                properties,
                metrics
        );
        stockQueryService = new ShowStockQueryService(detailCacheService, gateEvaluator, properties);
        seatMapQueryService = new SeatMapQueryService(
                detailCacheService, gateEvaluator, seatMapper, providerOf(redisScripts), properties, metrics
        );
    }

    @Test
    void shouldDecodeMsbBitmapCorrectlyIncludingSparseOffsetsAndAisleSegments() {
        // 验证 Redis MSB 位序契约：offset 0 -> byte[0] 0x80; offset 7 -> byte[0] 0x01; offset 8 -> byte[1] 0x80; offset 99 -> byte[12] 0x10
        byte[] bitmap = new byte[ShowRedisScripts.requiredBitmapBytes(99)];
        assertEquals(13, bitmap.length);
        ShowRedisScripts.setBitMsb(bitmap, 0);
        ShowRedisScripts.setBitMsb(bitmap, 7);
        ShowRedisScripts.setBitMsb(bitmap, 8);
        ShowRedisScripts.setBitMsb(bitmap, 99);

        assertEquals((byte) 0x81, bitmap[0]);
        assertEquals((byte) 0x80, bitmap[1]);
        assertEquals((byte) 0x10, bitmap[12]);

        assertTrue(ShowRedisScripts.isBitSetMsb(bitmap, 0));
        assertFalse(ShowRedisScripts.isBitSetMsb(bitmap, 1));
        assertTrue(ShowRedisScripts.isBitSetMsb(bitmap, 7));
        assertTrue(ShowRedisScripts.isBitSetMsb(bitmap, 8));
        assertFalse(ShowRedisScripts.isBitSetMsb(bitmap, 9));
        assertTrue(ShowRedisScripts.isBitSetMsb(bitmap, 99));
        assertFalse(ShowRedisScripts.isBitSetMsb(bitmap, 98));

        long now = System.currentTimeMillis();
        ShowDetailCachePayload detail = sampleManualShow(now - 5000, now + 60_000, "PREHEATED");
        when(detailCacheService.getDetail(10002L)).thenReturn(detail);
        when(redisScripts.readReadyState(10002L)).thenReturn(ShowReadyState.builder()
                .version(1).stage(ShowReadyState.STAGE_READY)
                .saleStartEpochMs(now - 5000).saleEndEpochMs(now + 60_000).build());
        when(valueOps.multiGet(List.of(RedisKeys.stock(10002L, 20004L)))).thenReturn(List.of("96"));
        when(redisScripts.readRawSeatBitmap(10002L, "HALL_A")).thenReturn(bitmap);
        when(seatMapper.selectByShowAndZone(10002L, "HALL_A")).thenReturn(List.of(
                SeatDO.builder().showId(10002L).tierId(20004L).zoneId("HALL_A").seatIndex(0).rowNo(1).colNo(1).segmentId("R1-L").status(0).build(),
                SeatDO.builder().showId(10002L).tierId(20004L).zoneId("HALL_A").seatIndex(1).rowNo(1).colNo(2).segmentId("R1-L").status(0).build(),
                SeatDO.builder().showId(10002L).tierId(20004L).zoneId("HALL_A").seatIndex(4).rowNo(1).colNo(5).segmentId("R1-M").status(1).build(), // 维修座
                SeatDO.builder().showId(10002L).tierId(20004L).zoneId("HALL_A").seatIndex(99).rowNo(10).colNo(10).segmentId("R10-R").status(0).build()
        ));

        SeatMapVO seatMap = seatMapQueryService.getZoneSeatMap(10002L, "HALL_A");
        assertTrue(seatMap.isAvailable());
        assertEquals("OPEN", seatMap.getStockState());
        assertEquals(4, seatMap.getSeats().size());
        assertFalse(seatMap.getSeats().get(0).isSelectable(), "offset 0 已在 Bitmap 占用");
        assertTrue(seatMap.getSeats().get(1).isSelectable(), "offset 1 空闲可选");
        assertEquals("R1-L", seatMap.getSeats().get(1).getSegmentId());
        assertFalse(seatMap.getSeats().get(2).isSelectable(), "维修座不可选");
        assertEquals("R1-M", seatMap.getSeats().get(2).getSegmentId());
        assertFalse(seatMap.getSeats().get(3).isSelectable(), "offset 99 已在 Bitmap 占用");
        assertEquals("R10-R", seatMap.getSeats().get(3).getSegmentId());
    }

    @Test
    void shouldEvaluateMillisecondSaleBoundaryAndNeverConfuseFourStates() {
        long start = 1_800_000_000_000L;
        long end = start + 3_600_000L;
        ShowDetailCachePayload detail = ShowDetailCachePayload.builder()
                .showId(10001L)
                .saleConfigVersion(1)
                .saleStatus("PREHEATED")
                .saleStartEpochMs(start)
                .saleEndEpochMs(end)
                .seatMode("AUTO_ALLOC")
                .tiers(List.of(
                        ShowDetailCachePayload.CachedTier.builder().tierId(20001L).tierName("VIP").zoneId("Z1").priceCent(168000L).build(),
                        ShowDetailCachePayload.CachedTier.builder().tierId(20002L).tierName("A区").zoneId("Z2").priceCent(98000L).build()
                ))
                .build();

        when(redisScripts.readReadyState(10001L)).thenReturn(ShowReadyState.builder()
                .version(1).stage(ShowReadyState.STAGE_READY)
                .saleStartEpochMs(start).saleEndEpochMs(end).build());
        when(valueOps.multiGet(anyList())).thenReturn(List.of("10", "0"));

        // 1. 开售前 1ms -> PREPARING
        ShowGateEvaluator.GateEvaluation before1ms = gateEvaluator.evaluate(detail, start - 1, false);
        assertEquals(EntryState.PREPARING, before1ms.getEntryState());

        // 2. 开售准点 0ms（即便 DB 仍为 PREHEATED、Redis 仍为 READY，不等待定时任务）-> 立即 OPEN！
        ShowGateEvaluator.GateEvaluation exactStart = gateEvaluator.evaluate(detail, start, false);
        assertEquals(EntryState.OPEN, exactStart.getEntryState());
        assertEquals(EntryState.OPEN, exactStart.findTier(20001L).getStockState());
        assertEquals(EntryState.SOLD_OUT, exactStart.findTier(20002L).getStockState());

        // 3. 全部有效 0 余量 -> SOLD_OUT
        when(valueOps.multiGet(anyList())).thenReturn(List.of("0", "0"));
        ShowGateEvaluator.GateEvaluation soldOut = gateEvaluator.evaluate(detail, start + 1000, false);
        assertEquals(EntryState.SOLD_OUT, soldOut.getEntryState());

        // 4. 一档 0 一档键缺失 -> STOCK_UNAVAILABLE（绝不把缺失误报 SOLD_OUT）
        when(valueOps.multiGet(anyList())).thenReturn(java.util.Arrays.asList("0", null));
        ShowGateEvaluator.GateEvaluation partialMissing = gateEvaluator.evaluate(detail, start + 2000, false);
        assertEquals(EntryState.STOCK_UNAVAILABLE, partialMissing.getEntryState());
        assertEquals(EntryState.SOLD_OUT, partialMissing.findTier(20001L).getStockState());
        assertEquals(EntryState.STOCK_UNAVAILABLE, partialMissing.findTier(20002L).getStockState());
        assertNull(partialMissing.findTier(20002L).getAvailable());

        // 5. 到达停售时刻 [start, end) 右开边界 -> 即使未再查库存也立即短路返回 ENDED
        ShowGateEvaluator.GateEvaluation exactEnd = gateEvaluator.evaluate(detail, end, false);
        assertEquals(EntryState.ENDED, exactEnd.getEntryState());
        verify(valueOps, times(3)).multiGet(anyList());
    }

    @Test
    void shouldThrow503B0201WhenBitmapMissingOrAllStockKeysMissingDuringOnSale() {
        long now = System.currentTimeMillis();
        ShowDetailCachePayload detail = sampleManualShow(now - 5000, now + 60_000, "ON_SALE");
        when(detailCacheService.getDetail(10002L)).thenReturn(detail);
        when(redisScripts.readReadyState(10002L)).thenReturn(ShowReadyState.builder()
                .version(1).stage(ShowReadyState.STAGE_OPEN)
                .saleStartEpochMs(now - 5000).saleEndEpochMs(now + 60_000).build());

        // 整体库存键缺失 -> 503/B0201
        when(valueOps.multiGet(List.of(RedisKeys.stock(10002L, 20004L)))).thenReturn(java.util.Collections.singletonList(null));
        BizException stockMissing = assertThrows(BizException.class, () -> stockQueryService.getShowStock(10002L));
        assertEquals(ErrorCode.STOCK_UNAVAILABLE, stockMissing.getErrorCode());
        assertEquals(503, stockMissing.getHttpStatus());

        // 库存存在但位图缺失 -> 503/B0201
        when(valueOps.multiGet(List.of(RedisKeys.stock(10002L, 20004L)))).thenReturn(List.of("100"));
        when(seatMapper.selectByShowAndZone(10002L, "HALL_A")).thenReturn(List.of(
                SeatDO.builder().showId(10002L).tierId(20004L).zoneId("HALL_A").seatIndex(0).rowNo(1).colNo(1).segmentId("R1-L").status(0).build()
        ));
        when(redisScripts.readRawSeatBitmap(10002L, "HALL_A")).thenReturn(null);
        BizException bitmapMissing = assertThrows(BizException.class, () -> seatMapQueryService.getZoneSeatMap(10002L, "HALL_A"));
        assertEquals(ErrorCode.STOCK_UNAVAILABLE, bitmapMissing.getErrorCode());
        assertEquals(503, bitmapMissing.getHttpStatus());

        // 真实零余量 -> 200 + SOLD_OUT（不抛 503）
        when(valueOps.multiGet(List.of(RedisKeys.stock(10002L, 20004L)))).thenReturn(List.of("0"));
        ShowStockVO soldOutVo = stockQueryService.getShowStock(10002L);
        assertEquals("SOLD_OUT", soldOutVo.getEntryState());
        assertEquals(0, soldOutVo.getTiers().get(0).getAvailable());
        assertTrue(soldOutVo.getTiers().get(0).getHint().contains("回流"));
    }

    private ShowDetailCachePayload sampleManualShow(long startMs, long endMs, String status) {
        return ShowDetailCachePayload.builder()
                .showId(10002L)
                .saleConfigVersion(1)
                .saleStatus(status)
                .saleStartEpochMs(startMs)
                .saleEndEpochMs(endMs)
                .seatMode("MANUAL_SEAT")
                .tiers(List.of(ShowDetailCachePayload.CachedTier.builder()
                        .tierId(20004L).tierName("A区").zoneId("HALL_A").priceCent(88000L).totalStock(100).build()))
                .build();
    }

    private <T> ObjectProvider<T> providerOf(T instance) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return instance;
            }

            @Override
            public T getIfAvailable() {
                return instance;
            }

            @Override
            public T getIfUnique() {
                return instance;
            }

            @Override
            public T getObject() {
                return instance;
            }
        };
    }
}
