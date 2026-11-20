package com.starshow.user.service;

import com.starshow.api.dto.ShowBasicDTO;
import com.starshow.api.dto.ShowRescheduleSyncDTO;
import com.starshow.api.facade.ShowReadFacade;
import com.starshow.api.facade.UserFacade;
import com.starshow.common.id.GeneSnowflakeIdGenerator;
import com.starshow.user.config.ReservationProperties;
import com.starshow.user.domain.entity.ReservationDO;
import com.starshow.user.facade.ReservationRescheduleFacadeImpl;
import com.starshow.user.mapper.ReservationMapper;
import com.starshow.user.mq.ShowRemindProducer;
import com.starshow.user.mq.ShowRemindRecoveryScheduler;
import com.starshow.user.vo.MyReservationVO;
import com.starshow.user.vo.ShowRescheduleResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 链路一 <-> 链路二对齐单测：
 * 验证 GET /api/v1/reservations/{showId}/me 的 entryOpen 与 nextAction 严格对齐 ShowBasicDTO.entryState，
 * 以及 ReservationRescheduleFacadeImpl 本地零 HTTP 委派。
 */
@ExtendWith(MockitoExtension.class)
class ReservationEntryStateSyncTest {

    @Mock
    private UserFacade userFacade;
    @Mock
    private ShowReadFacade showReadFacade;
    @Mock
    private ReservationMapper reservationMapper;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private ReservationCountStore reservationCountStore;
    @Mock
    private ShowRemindProducer showRemindProducer;
    @Mock
    private ShowRemindRecoveryScheduler recoveryScheduler;

    private ReservationService reservationService;

    @BeforeEach
    void setUp() {
        reservationService = new ReservationService(
                userFacade,
                showReadFacade,
                reservationMapper,
                redissonClient,
                reservationCountStore,
                new GeneSnowflakeIdGenerator(1),
                showRemindProducer,
                new ReservationProperties()
        );
    }

    @Test
    void shouldAlignEntryOpenWithAuthoritativeShowEntryState() {
        long userId = 1001L;
        long showId = 10003L;
        when(userFacade.isRealNameVerified(userId)).thenReturn(true);
        when(reservationMapper.selectByUserAndShow(userId, showId)).thenReturn(
                ReservationDO.builder().id(9001L).userId(userId).showId(showId).remindStatus(3).build()
        );

        // 1. 即使已过 saleStartTime，只要链路二权威 entryState 仍为 PREPARING（预热未完成/改期中），entryOpen 必须为 false
        when(showReadFacade.getShowBasic(showId)).thenReturn(ShowBasicDTO.builder()
                .showId(showId)
                .title("星演跨年盛典")
                .saleStartTime(LocalDateTime.now().minusSeconds(10))
                .saleEndTime(LocalDateTime.now().plusHours(2))
                .saleStatus("PREHEATING")
                .saleConfigVersion(2)
                .entryState("PREPARING")
                .build());
        MyReservationVO preparing = reservationService.getMyReservation(userId, showId);
        assertFalse(preparing.isEntryOpen());
        assertEquals("WAIT_FOR_SALE", preparing.getNextAction());
        assertEquals(2, preparing.getSaleConfigVersion());

        // 2. 权威 entryState=OPEN -> entryOpen=true, nextAction=ENTER_SECKILL
        when(showReadFacade.getShowBasic(showId)).thenReturn(ShowBasicDTO.builder()
                .showId(showId)
                .title("星演跨年盛典")
                .saleStartTime(LocalDateTime.now().minusSeconds(10))
                .saleEndTime(LocalDateTime.now().plusHours(2))
                .saleStatus("PREHEATED")
                .saleConfigVersion(2)
                .entryState("OPEN")
                .build());
        MyReservationVO open = reservationService.getMyReservation(userId, showId);
        assertTrue(open.isEntryOpen());
        assertEquals("ENTER_SECKILL", open.getNextAction());

        // 3. 权威 entryState=ENDED -> entryOpen=false, nextAction=SALE_ENDED
        when(showReadFacade.getShowBasic(showId)).thenReturn(ShowBasicDTO.builder()
                .showId(showId)
                .title("星演跨年盛典")
                .saleStartTime(LocalDateTime.now().minusHours(2))
                .saleEndTime(LocalDateTime.now().minusMinutes(1))
                .saleStatus("ENDED")
                .saleConfigVersion(2)
                .entryState("ENDED")
                .build());
        MyReservationVO ended = reservationService.getMyReservation(userId, showId);
        assertFalse(ended.isEntryOpen());
        assertEquals("SALE_ENDED", ended.getNextAction());
    }

    @Test
    void shouldDelegateLocalRescheduleFacadeWithoutHttp() {
        LocalDateTime newStart = LocalDateTime.now().plusHours(3);
        when(recoveryScheduler.rescheduleForShowVersion(10003L)).thenReturn(ShowRescheduleResultVO.builder()
                .showId(10003L)
                .saleConfigVersion(3)
                .saleStartTime(newStart)
                .resetReservationCount(5)
                .progressedCount(5)
                .scheduleNotifiedCount(5)
                .build());

        ReservationRescheduleFacadeImpl facade = new ReservationRescheduleFacadeImpl(recoveryScheduler);
        ShowRescheduleSyncDTO dto = facade.rescheduleShowReminders(10003L);
        assertNotNull(dto);
        assertEquals(10003L, dto.getShowId());
        assertEquals(3, dto.getSaleConfigVersion());
        assertEquals(5, dto.getScheduleNotifiedCount());
    }
}
