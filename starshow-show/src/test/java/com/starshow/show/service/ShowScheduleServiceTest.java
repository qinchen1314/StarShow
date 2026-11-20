package com.starshow.show.service;

import com.starshow.api.dto.ShowBasicDTO;
import com.starshow.api.facade.ShowReadFacade;
import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.show.domain.entity.ShowDO;
import com.starshow.show.mapper.ShowMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShowScheduleServiceTest {

    @Mock
    private ShowMapper showMapper;
    @Mock
    private ShowReadFacade showReadFacade;

    private ShowScheduleService scheduleService;

    @BeforeEach
    void setUp() {
        scheduleService = new ShowScheduleService(showMapper, showReadFacade);
    }

    @Test
    void shouldIncrementSaleConfigVersionWhenReschedulingInitOrPreheatedShow() {
        long showId = 10003L;
        LocalDateTime newStart = LocalDateTime.now().plusDays(3);
        LocalDateTime newEnd = newStart.plusDays(10);

        when(showMapper.selectById(showId)).thenReturn(ShowDO.builder()
                .id(showId)
                .title("星演跨年盛典")
                .saleStatus("PREHEATED")
                .saleConfigVersion(1)
                .build());
        when(showMapper.casUpdateSchedule(showId, 1, newStart, newEnd)).thenReturn(1);
        when(showReadFacade.getShowBasic(showId)).thenReturn(ShowBasicDTO.builder()
                .showId(showId)
                .title("星演跨年盛典")
                .saleStartTime(newStart)
                .saleEndTime(newEnd)
                .saleStatus("INIT")
                .saleConfigVersion(2)
                .build());

        ShowBasicDTO updated = scheduleService.reschedule(showId, newStart, newEnd);
        assertEquals(2, updated.getSaleConfigVersion());
        assertEquals("INIT", updated.getSaleStatus());
        assertEquals(newStart, updated.getSaleStartTime());
        verify(showMapper).casUpdateSchedule(showId, 1, newStart, newEnd);
    }

    @Test
    void shouldRejectRescheduleForOnSaleShowOrInvalidTimeRange() {
        LocalDateTime start = LocalDateTime.now().plusDays(1);
        BizException invalidRange = assertThrows(BizException.class,
                () -> scheduleService.reschedule(10003L, start, start.minusHours(1)));
        assertEquals(ErrorCode.INVALID_PARAM, invalidRange.getErrorCode());

        when(showMapper.selectById(10001L)).thenReturn(ShowDO.builder()
                .id(10001L)
                .saleStatus("ON_SALE")
                .saleConfigVersion(1)
                .build());
        BizException onSaleEx = assertThrows(BizException.class,
                () -> scheduleService.reschedule(10001L, start, start.plusDays(5)));
        assertEquals(ErrorCode.SHOW_NOT_ON_SALE, onSaleEx.getErrorCode());
    }
}
