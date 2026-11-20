package com.starshow.order.service;

import com.starshow.api.dto.TierSnapshotDTO;
import com.starshow.api.dto.ZoneSeatSpecDTO;
import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.order.dto.SeckillOrderRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SeatSelectionValidatorTest {

    private SeatSelectionValidator validator;
    private TierSnapshotDTO manualTier;
    private ZoneSeatSpecDTO zoneSpec;

    @BeforeEach
    void setUp() {
        validator = new SeatSelectionValidator();
        manualTier = TierSnapshotDTO.builder()
                .showId(10002L)
                .tierId(20004L)
                .zoneId("HALL_A")
                .priceCent(88000L)
                .seatMode("MANUAL_SEAT")
                .saleConfigVersion(1)
                .layoutVersion("1")
                .maxTicketsPerOrder(2)
                .build();

        // 构造与 02_seed.sql 一致的 10x10 走道分布：col 1..3 = R?-L, col 4..7 = R?-M, col 8..10 = R?-R
        List<ZoneSeatSpecDTO.SeatItem> items = new ArrayList<>();
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 10; c++) {
                String seg = c < 3 ? ("R" + (r + 1) + "-L") : (c < 7 ? ("R" + (r + 1) + "-M") : ("R" + (r + 1) + "-R"));
                items.add(ZoneSeatSpecDTO.SeatItem.builder()
                        .seatIndex(r * 10 + c)
                        .tierId(20004L)
                        .rowNo(r + 1)
                        .colNo(c + 1)
                        .segmentId(seg)
                        .status(0)
                        .build());
            }
        }
        // 把 99 号座标记为禁售(status=2)
        items.get(99).setStatus(2);

        zoneSpec = ZoneSeatSpecDTO.builder()
                .showId(10002L)
                .zoneId("HALL_A")
                .layoutVersion("1")
                .seats(items)
                .build();
    }

    @Test
    void shouldAcceptValidAdjacentSameSegmentSeats() {
        SeckillOrderRequest req = baseReq(List.of(4, 3)); // row1 col4 & col5 -> both R1-M
        List<Integer> normalized = validator.validateAndNormalize(req, manualTier, zoneSpec);
        assertEquals(List.of(3, 4), normalized);
    }

    @Test
    void shouldRejectAcrossAisleEvenWhenIndexAndColAreAdjacent() {
        // seatIndex=2 (row1, col3, R1-L) 与 seatIndex=3 (row1, col4, R1-M) 中间隔走道！
        SeckillOrderRequest req = baseReq(List.of(2, 3));
        BizException ex = assertThrows(BizException.class,
                () -> validator.validateAndNormalize(req, manualTier, zoneSpec));
        assertEquals(ErrorCode.INVALID_PARAM, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("走道"));
    }

    @Test
    void shouldRejectDuplicateOutOfBoundsForbiddenOrWrongLayoutVersion() {
        // 重复座位
        BizException dup = assertThrows(BizException.class,
                () -> validator.validateAndNormalize(baseReq(List.of(3, 3)), manualTier, zoneSpec));
        assertEquals(ErrorCode.INVALID_PARAM, dup.getErrorCode());

        // 越界偏移
        BizException oob = assertThrows(BizException.class,
                () -> validator.validateAndNormalize(baseReq(List.of(100, 101)), manualTier, zoneSpec));
        assertEquals(ErrorCode.INVALID_PARAM, oob.getErrorCode());

        // 禁售座位
        SeckillOrderRequest forbidden = baseReq(List.of(99));
        forbidden.setQty(1);
        BizException forbidEx = assertThrows(BizException.class,
                () -> validator.validateAndNormalize(forbidden, manualTier, zoneSpec));
        assertEquals(ErrorCode.INVALID_PARAM, forbidEx.getErrorCode());

        // 布局版本过期 -> A0205
        SeckillOrderRequest staleVer = baseReq(List.of(3, 4));
        staleVer.setLayoutVersion("99");
        BizException verEx = assertThrows(BizException.class,
                () -> validator.validateAndNormalize(staleVer, manualTier, zoneSpec));
        assertEquals(ErrorCode.SHOW_CONFIG_VERSION_CHANGED, verEx.getErrorCode());

        // 自动配座禁止传座位
        TierSnapshotDTO autoTier = TierSnapshotDTO.builder()
                .showId(10001L).tierId(20001L).seatMode("AUTO_ALLOC").build();
        BizException autoEx = assertThrows(BizException.class,
                () -> validator.validateAndNormalize(baseReq(List.of(3, 4)), autoTier, zoneSpec));
        assertEquals(ErrorCode.INVALID_PARAM, autoEx.getErrorCode());
    }

    private SeckillOrderRequest baseReq(List<Integer> seats) {
        return SeckillOrderRequest.builder()
                .showId(10002L)
                .tierId(20004L)
                .qty(seats.size())
                .zoneId("HALL_A")
                .seatIndices(seats)
                .layoutVersion("1")
                .saleConfigVersion(1)
                .expectedUnitPriceCent(88000L)
                .seckillToken("tok")
                .clientRequestId("req-seat-0001")
                .build();
    }
}
