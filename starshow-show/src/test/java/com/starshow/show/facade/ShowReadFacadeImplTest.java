package com.starshow.show.facade;

import com.starshow.api.dto.ShowBasicDTO;
import com.starshow.show.domain.entity.ShowDO;
import com.starshow.show.mapper.ShowMapper;
import com.starshow.show.mapper.TicketTierMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShowReadFacadeImplTest {

    @Mock
    private ShowMapper showMapper;
    @Mock
    private TicketTierMapper ticketTierMapper;

    @InjectMocks
    private ShowReadFacadeImpl showReadFacade;

    @Test
    void shouldReturnShowBasicWhenExistsAndNullWhenAbsent() {
        LocalDateTime start = LocalDateTime.of(2026, 12, 1, 10, 0);
        LocalDateTime end = LocalDateTime.of(2026, 12, 20, 18, 0);
        ShowDO show = ShowDO.builder()
                .id(10003L)
                .title("星演跨年盛典")
                .saleStartTime(start)
                .saleEndTime(end)
                .saleStatus("INIT")
                .build();
        when(showMapper.selectById(10003L)).thenReturn(show);
        when(showMapper.selectById(99999L)).thenReturn(null);

        ShowBasicDTO dto = showReadFacade.getShowBasic(10003L);
        assertNotNull(dto);
        assertEquals(10003L, dto.getShowId());
        assertEquals("星演跨年盛典", dto.getTitle());
        assertEquals(start, dto.getSaleStartTime());
        assertEquals(end, dto.getSaleEndTime());
        assertEquals("INIT", dto.getSaleStatus());

        assertNull(showReadFacade.getShowBasic(99999L));
    }
}
