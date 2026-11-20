package com.starshow.show.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshow.common.config.JacksonLongToStringConfig;
import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.common.web.GlobalExceptionHandler;
import com.starshow.show.cache.ShowDetailCacheService;
import com.starshow.show.cache.ShowGateEvaluator;
import com.starshow.show.controller.ShowQueryController;
import com.starshow.show.domain.entity.ShowDO;
import com.starshow.show.domain.model.EntryState;
import com.starshow.show.domain.model.ShowDetailCachePayload;
import com.starshow.show.mapper.ShowMapper;
import com.starshow.show.util.ShowTimeUtil;
import com.starshow.show.vo.ShowDetailVO;
import com.starshow.show.vo.ShowListVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * L1 单元测试：稳定游标分页、城市筛选、游标篡改校验、未知场次 A0201 及 Long 金额/ID 字符串序列化
 */
@ExtendWith(MockitoExtension.class)
class ShowQueryServiceTest {

    @Mock
    private ShowMapper showMapper;
    @Mock
    private ShowDetailCacheService detailCacheService;
    @Mock
    private ShowGateEvaluator gateEvaluator;
    @Mock
    private ShowStockQueryService stockQueryService;
    @Mock
    private SeatMapQueryService seatMapQueryService;

    private ObjectMapper objectMapper;
    private ShowQueryService showQueryService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        showQueryService = new ShowQueryService(showMapper, detailCacheService, gateEvaluator, objectMapper);
    }

    @Test
    void shouldPaginateWithStableCursorAndBindCityFilter() {
        LocalDateTime t1 = LocalDateTime.of(2026, 12, 20, 19, 30);
        LocalDateTime t2 = LocalDateTime.of(2026, 12, 20, 19, 30);
        LocalDateTime t3 = LocalDateTime.of(2026, 12, 28, 19, 30);
        ShowDO s1 = ShowDO.builder().id(10001L).title("演出1").city("上海").venue("场馆1")
                .showTime(t1).saleStartTime(t1.minusDays(5)).saleEndTime(t1.minusHours(1)).seatMode("AUTO_ALLOC").build();
        ShowDO s2 = ShowDO.builder().id(10002L).title("演出2").city("上海").venue("场馆2")
                .showTime(t2).saleStartTime(t2.minusDays(5)).saleEndTime(t2.minusHours(1)).seatMode("MANUAL_SEAT").build();
        ShowDO s3 = ShowDO.builder().id(10003L).title("演出3").city("上海").venue("场馆3")
                .showTime(t3).saleStartTime(t3.minusDays(5)).saleEndTime(t3.minusHours(1)).seatMode("AUTO_ALLOC").build();

        // 第一页 limit=2 返回 3 条（limit+1 判定 hasMore）
        when(showMapper.selectPageByCursor("上海", null, null, 3)).thenReturn(List.of(s1, s2, s3));
        ShowListVO page1 = showQueryService.listShows("上海", null, 2);
        assertEquals(2, page1.getItems().size());
        assertEquals(10001L, page1.getItems().get(0).getShowId());
        assertEquals(10002L, page1.getItems().get(1).getShowId());
        assertNotNull(page1.getNextCursor());

        // 游标绑定城市筛选：换城市复用游标必须拒绝 A0103
        BizException cityMismatch = assertThrows(BizException.class,
                () -> showQueryService.listShows("北京", page1.getNextCursor(), 2));
        assertEquals(ErrorCode.INVALID_PARAM, cityMismatch.getErrorCode());

        // 合法翻第二页：传入 (t2, 10002)
        when(showMapper.selectPageByCursor("上海", t2, 10002L, 3)).thenReturn(List.of(s3));
        ShowListVO page2 = showQueryService.listShows("上海", page1.getNextCursor(), 2);
        assertEquals(1, page2.getItems().size());
        assertEquals(10003L, page2.getItems().get(0).getShowId());
        assertNull(page2.getNextCursor());
    }

    @Test
    void shouldRejectInvalidLimitAndMalformedCursor() {
        assertEquals(ErrorCode.INVALID_PARAM,
                assertThrows(BizException.class, () -> showQueryService.listShows(null, null, 0)).getErrorCode());
        assertEquals(ErrorCode.INVALID_PARAM,
                assertThrows(BizException.class, () -> showQueryService.listShows(null, null, 51)).getErrorCode());
        assertEquals(ErrorCode.INVALID_PARAM,
                assertThrows(BizException.class, () -> showQueryService.listShows(null, "not-valid-base64!!!", 10)).getErrorCode());
    }

    @Test
    void shouldThrowShowNotFoundForUnknownShowAndSerializeLongsAsStrings() throws Exception {
        when(detailCacheService.getDetail(999999L)).thenReturn(null);
        BizException notFound = assertThrows(BizException.class, () -> showQueryService.getShowDetail(999999L));
        assertEquals(ErrorCode.SHOW_NOT_FOUND, notFound.getErrorCode());

        long now = System.currentTimeMillis();
        ShowDetailCachePayload payload = ShowDetailCachePayload.builder()
                .showId(10002L)
                .saleConfigVersion(2)
                .title("星演交响音乐会")
                .city("北京")
                .venue("国家大剧院")
                .showTimeEpochMs(now + 86_400_000L)
                .saleStartEpochMs(now - 60_000L)
                .saleEndEpochMs(now + 3_600_000L)
                .saleStatus("ON_SALE")
                .seatMode("MANUAL_SEAT")
                .tiers(List.of(ShowDetailCachePayload.CachedTier.builder()
                        .tierId(20004L)
                        .tierName("音乐厅一楼A区")
                        .zoneId("HALL_A")
                        .priceCent(88000L)
                        .totalStock(100)
                        .build()))
                .refreshedAtEpochMs(now)
                .expireAtEpochMs(now + 300_000L)
                .build();
        when(detailCacheService.getDetail(10002L)).thenReturn(payload);
        when(gateEvaluator.evaluate(eq(payload), anyLong(), eq(true)))
                .thenReturn(ShowGateEvaluator.GateEvaluation.builder()
                        .showId(10002L)
                        .saleConfigVersion(2)
                        .saleStatus("ON_SALE")
                        .saleStartEpochMs(now - 60_000L)
                        .saleEndEpochMs(now + 3_600_000L)
                        .asOfEpochMs(now)
                        .entryState(EntryState.OPEN)
                        .anyTierValid(true)
                        .redisHealthy(true)
                        .tiers(List.of(ShowGateEvaluator.TierStockEval.builder()
                                .tierId(20004L).available(98).stockState(EntryState.OPEN).build()))
                        .build());

        ShowDetailVO detail = showQueryService.getShowDetail(10002L);
        assertEquals("OPEN", detail.getEntryState());
        assertEquals(88000L, detail.getTiers().get(0).getPriceCent());

        // 通过 JacksonLongToStringConfig 验证 JSON 输出的 showId / tierId / priceCent 均为字符串（绝不丢 JS 精度）
        ObjectMapper webMapper = new ObjectMapper();
        webMapper.findAndRegisterModules();
        org.springframework.http.converter.json.Jackson2ObjectMapperBuilder builder =
                new org.springframework.http.converter.json.Jackson2ObjectMapperBuilder();
        new JacksonLongToStringConfig().longToStringCustomizer().customize(builder);
        ObjectMapper configuredMapper = builder.build();

        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new ShowQueryController(showQueryService, stockQueryService, seatMapQueryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(configuredMapper))
                .build();

        mockMvc.perform(get("/api/v1/shows/10002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.showId").isString())
                .andExpect(jsonPath("$.data.showId").value("10002"))
                .andExpect(jsonPath("$.data.tiers[0].tierId").isString())
                .andExpect(jsonPath("$.data.tiers[0].tierId").value("20004"))
                .andExpect(jsonPath("$.data.tiers[0].priceCent").isString())
                .andExpect(jsonPath("$.data.tiers[0].priceCent").value("88000"))
                .andExpect(jsonPath("$.data.entryState").value("OPEN"));

        // 验证 503/B0201 返回 HTTP 503 + Retry-After 头
        when(stockQueryService.getShowStock(10002L))
                .thenThrow(BizException.serviceUnavailable(ErrorCode.STOCK_UNAVAILABLE, 1000L, "库存暂不可用"));
        mockMvc.perform(get("/api/v1/shows/10002/stock"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("B0201"));
    }
}
