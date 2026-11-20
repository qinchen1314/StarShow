package com.starshow.show.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshow.api.dto.ShowBasicDTO;
import com.starshow.api.dto.ShowRescheduleSyncDTO;
import com.starshow.api.facade.ReservationRescheduleFacade;
import com.starshow.api.facade.ShowReadFacade;
import com.starshow.common.exception.BizException;
import com.starshow.common.id.GeneSnowflakeIdGenerator;
import com.starshow.common.result.ErrorCode;
import com.starshow.show.cache.ShowCatalogBloomManager;
import com.starshow.show.cache.ShowDetailCacheService;
import com.starshow.show.cache.ShowGateEvaluator;
import com.starshow.show.config.ShowMetrics;
import com.starshow.show.config.ShowProperties;
import com.starshow.show.domain.entity.ShowDO;
import com.starshow.show.domain.entity.ShowTaskDO;
import com.starshow.show.domain.entity.TicketTierDO;
import com.starshow.show.lua.ShowRedisScripts;
import com.starshow.show.mapper.SeatMapper;
import com.starshow.show.mapper.ShowMapper;
import com.starshow.show.mapper.ShowTaskMapper;
import com.starshow.show.mapper.TicketTierMapper;
import com.starshow.show.vo.ShowPreheatResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * L1 单元测试：安全改期（纳入 PREHEATING + 原子 BLOCKED + 联动提醒重排）与预热版本/Fencing 拒绝防护
 */
@ExtendWith(MockitoExtension.class)
class ShowPreheatAndScheduleTest {

    @Mock
    private ShowMapper showMapper;
    @Mock
    private TicketTierMapper ticketTierMapper;
    @Mock
    private SeatMapper seatMapper;
    @Mock
    private ShowTaskMapper showTaskMapper;
    @Mock
    private ShowRedisScripts redisScripts;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ShowDetailCacheService detailCacheService;
    @Mock
    private ShowGateEvaluator gateEvaluator;
    @Mock
    private ShowCatalogBloomManager bloomManager;
    @Mock
    private ShowReadFacade showReadFacade;
    @Mock
    private ReservationRescheduleFacade reservationRescheduleFacade;

    private ShowProperties properties;
    private ShowMetrics metrics;
    private GeneSnowflakeIdGenerator idGenerator;
    private ShowScheduleService scheduleService;
    private ShowPreheatService preheatService;

    @BeforeEach
    void setUp() {
        properties = new ShowProperties();
        metrics = new ShowMetrics();
        idGenerator = new GeneSnowflakeIdGenerator(1);
        scheduleService = new ShowScheduleService(
                showMapper,
                showReadFacade,
                providerOf(showTaskMapper),
                providerOf(redisScripts),
                providerOf((RedissonClient) null),
                providerOf(detailCacheService),
                providerOf(gateEvaluator),
                providerOf(reservationRescheduleFacade),
                providerOf(idGenerator),
                providerOf(properties)
        );
        preheatService = new ShowPreheatService(
                showMapper,
                ticketTierMapper,
                seatMapper,
                showTaskMapper,
                redisScripts,
                stringRedisTemplate,
                providerOf((RedissonClient) null),
                detailCacheService,
                gateEvaluator,
                bloomManager,
                idGenerator,
                new ObjectMapper(),
                properties,
                metrics
        );
    }

    @Test
    void shouldAllowSafeRescheduleFromPreheatingBlockOldVersionAndSyncReminders() {
        long showId = 10003L;
        LocalDateTime newStart = LocalDateTime.now().plusHours(6);
        LocalDateTime newEnd = newStart.plusDays(10);

        when(showMapper.selectById(showId)).thenReturn(ShowDO.builder()
                .id(showId).saleStatus("PREHEATING").saleConfigVersion(1).build());
        when(redisScripts.blockForReschedule(eq(showId), eq(1), anyLong())).thenReturn(1L);
        when(showMapper.casUpdateSchedule(showId, 1, newStart, newEnd)).thenReturn(1);
        when(showReadFacade.getShowBasic(showId)).thenReturn(ShowBasicDTO.builder()
                .showId(showId).saleStatus("INIT").saleConfigVersion(2)
                .saleStartTime(newStart).saleEndTime(newEnd).entryState("PREPARING").build());
        when(reservationRescheduleFacade.rescheduleShowReminders(showId))
                .thenReturn(ShowRescheduleSyncDTO.builder().showId(showId).saleConfigVersion(2).build());

        ShowBasicDTO updated = scheduleService.reschedule(showId, newStart, newEnd);
        assertEquals(2, updated.getSaleConfigVersion());
        assertEquals("INIT", updated.getSaleStatus());
        verify(redisScripts).blockForReschedule(eq(showId), eq(1), anyLong());
        verify(showTaskMapper).cancelOutdatedTasks(showId, ShowTaskDO.TYPE_PREHEAT, 2);
        verify(redisScripts).publishRescheduled(eq(showId), eq(2), anyLong(), anyLong(), anyLong());
        verify(detailCacheService).refreshFromDb(showId);
        verify(reservationRescheduleFacade).rescheduleShowReminders(showId);
    }

    @Test
    void shouldRejectRescheduleWhenRedisAlreadyEnteredSaleWindow() {
        long showId = 10003L;
        LocalDateTime newStart = LocalDateTime.now().plusHours(6);
        LocalDateTime newEnd = newStart.plusDays(10);

        when(showMapper.selectById(showId)).thenReturn(ShowDO.builder()
                .id(showId).saleStatus("PREHEATED").saleConfigVersion(1).build());
        when(redisScripts.blockForReschedule(eq(showId), eq(1), anyLong())).thenReturn(-1L);

        BizException ex = assertThrows(BizException.class, () -> scheduleService.reschedule(showId, newStart, newEnd));
        assertEquals(ErrorCode.SHOW_NOT_ON_SALE, ex.getErrorCode());
        verify(showMapper, never()).casUpdateSchedule(anyLong(), anyInt(), any(), any());
    }

    @Test
    void shouldRejectStaleVersionAndOnSalePreheatAndAbortOnFencingLoss() {
        long showId = 10003L;
        // 1. 旧版本号请求 -> A0205
        when(showMapper.selectById(showId)).thenReturn(ShowDO.builder()
                .id(showId).saleStatus("INIT").saleConfigVersion(2).build());
        BizException verEx = assertThrows(BizException.class, () -> preheatService.preheat(showId, 1));
        assertEquals(ErrorCode.SHOW_CONFIG_VERSION_CHANGED, verEx.getErrorCode());

        // 2. 已开售场次且无同版成功任务 -> 严禁覆盖在售库存 A0202
        when(showMapper.selectById(10001L)).thenReturn(ShowDO.builder()
                .id(10001L).saleStatus("ON_SALE").saleConfigVersion(1).build());
        when(showTaskMapper.selectByShowTypeVersion(10001L, ShowTaskDO.TYPE_PREHEAT, 1)).thenReturn(null);
        BizException onSaleEx = assertThrows(BizException.class, () -> preheatService.preheat(10001L, 1));
        assertEquals(ErrorCode.SHOW_NOT_ON_SALE, onSaleEx.getErrorCode());

        // 3. 写入库存中途 Fencing Token 被新任务抢占 -> 返回 FAILED 且不提交 READY 与 PREHEATED
        when(showMapper.selectById(showId)).thenReturn(ShowDO.builder()
                .id(showId).saleStatus("INIT").saleConfigVersion(2).seatMode("AUTO_ALLOC")
                .saleStartTime(LocalDateTime.now().plusHours(2))
                .saleEndTime(LocalDateTime.now().plusDays(10)).build());
        ShowTaskDO task = ShowTaskDO.builder().taskId(5001L).showId(showId)
                .taskType(ShowTaskDO.TYPE_PREHEAT).version(2).status(ShowTaskDO.STATUS_PENDING).build();
        when(showTaskMapper.selectByShowTypeVersion(showId, ShowTaskDO.TYPE_PREHEAT, 2)).thenReturn(task);
        when(showTaskMapper.casAcquireLease(eq(5001L), anyString(), any(), any())).thenReturn(1);
        when(showMapper.casMarkPreheating(showId, 2)).thenReturn(1);
        when(redisScripts.preheatBegin(eq(showId), eq(2), anyString(), anyLong(), anyLong(), anyLong())).thenReturn(1L);
        when(ticketTierMapper.selectByShowId(showId)).thenReturn(List.of(
                TicketTierDO.builder().id(20005L).showId(showId).tierName("内场").zoneId("NYE_A").priceCent(128000L).availableStock(5000).build()
        ));
        when(redisScripts.preheatWriteStockBatch(eq(showId), eq(2), anyString(), anyMap())).thenReturn(false);

        ShowPreheatResultVO res = preheatService.preheat(showId, 2);
        assertEquals("FAILED", res.getTaskStatus());
        assertEquals("FENCING_REJECTED_STOCK", res.getFailedStage());
        verify(redisScripts, never()).preheatCommitReady(anyLong(), anyInt(), anyString(), anyLong(), anyLong(), anyLong());
        verify(showMapper, never()).casMarkPreheated(anyLong(), anyInt());
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
