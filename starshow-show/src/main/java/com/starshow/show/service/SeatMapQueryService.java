package com.starshow.show.service;

import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.show.cache.ShowDetailCacheService;
import com.starshow.show.cache.ShowGateEvaluator;
import com.starshow.show.config.ShowMetrics;
import com.starshow.show.config.ShowProperties;
import com.starshow.show.domain.entity.SeatDO;
import com.starshow.show.domain.model.EntryState;
import com.starshow.show.domain.model.ShowDetailCachePayload;
import com.starshow.show.lua.ShowRedisScripts;
import com.starshow.show.mapper.SeatMapper;
import com.starshow.show.util.ShowTimeUtil;
import com.starshow.show.vo.SeatMapVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 链路二（节点 1）：自主选座分区 Bitmap 实时座位图服务
 * - 物理布局（rowNo/colNo/seatIndex/segmentId）来自 MySQL t_seat，只画存在的物理座位；
 * - 售卖期实时占用来自 Redis Bitmap（starshow:seat:bitmap:{showId}:<zoneId>），严格按 MSB 最高位优先解码；
 * - 售卖期位图缺失、长度不足或 Redis 不可读时，直接返回 HTTP 503 + B0201，严禁返回虚假可选快照或误报售罄。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatMapQueryService {

    private final ShowDetailCacheService detailCacheService;
    private final ShowGateEvaluator gateEvaluator;
    private final SeatMapper seatMapper;
    private final ObjectProvider<ShowRedisScripts> redisScriptsProvider;
    private final ShowProperties properties;
    private final ShowMetrics metrics;

    public SeatMapVO getZoneSeatMap(long showId, String zoneId) {
        if (zoneId == null || zoneId.isBlank()) {
            throw new BizException(ErrorCode.INVALID_PARAM, "zoneId 不能为空");
        }
        ShowDetailCachePayload detail = detailCacheService.getDetail(showId);
        if (detail == null) {
            throw new BizException(ErrorCode.SHOW_NOT_FOUND);
        }

        List<SeatDO> seats = seatMapper.selectByShowAndZone(showId, zoneId.trim());
        if (seats == null || seats.isEmpty()) {
            throw new BizException(ErrorCode.TIER_NOT_FOUND, "该场次不存在指定座位分区: " + zoneId);
        }

        long nowMs = System.currentTimeMillis();
        ShowGateEvaluator.GateEvaluation eval = gateEvaluator.evaluate(detail, nowMs, false);
        long tierId = seats.get(0).getTierId();
        int maxSeatIndex = seats.stream().mapToInt(SeatDO::getSeatIndex).max().orElse(0);
        int minBytesRequired = ShowRedisScripts.requiredBitmapBytes(maxSeatIndex);

        // 未到开售 / 预热中 / 已停售：返回结构化布局，但全部座位不可选
        if (eval.getEntryState() == EntryState.PREPARING || eval.getEntryState() == EntryState.ENDED) {
            List<SeatMapVO.SeatItem> nonSelectableSeats = buildNonSelectableSeats(seats);
            return SeatMapVO.builder()
                    .showId(showId)
                    .zoneId(zoneId.trim())
                    .tierId(tierId)
                    .layoutVersion(eval.getSaleConfigVersion())
                    .asOf(ShowTimeUtil.toOffsetDateTime(nowMs))
                    .available(false)
                    .stockState(eval.getEntryState().name())
                    .seats(nonSelectableSeats)
                    .build();
        }

        // 在售窗口：必须确认该票档与分区位图均有效可读
        ShowGateEvaluator.TierStockEval tierEval = eval.findTier(tierId);
        if (!eval.isRedisHealthy()
                || (tierEval != null && tierEval.getStockState() == EntryState.STOCK_UNAVAILABLE)
                || (tierEval == null && eval.getEntryState() == EntryState.STOCK_UNAVAILABLE)) {
            throw BizException.serviceUnavailable(
                    ErrorCode.STOCK_UNAVAILABLE,
                    properties.getRetryAfterMs(),
                    "分区座位状态暂不可用，请稍后刷新"
            );
        }

        ShowRedisScripts scripts = redisScriptsProvider.getIfAvailable();
        if (scripts == null) {
            metrics.recordStockMissingKey();
            throw BizException.serviceUnavailable(
                    ErrorCode.STOCK_UNAVAILABLE,
                    properties.getRetryAfterMs(),
                    "分区座位位图暂不可用，请稍后刷新"
            );
        }

        byte[] bitmapBytes;
        try {
            bitmapBytes = scripts.readRawSeatBitmap(showId, zoneId.trim());
        } catch (Exception ex) {
            metrics.recordStockMissingKey();
            log.warn("Failed to read seat bitmap for showId={}, zoneId={}: {}", showId, zoneId, ex.getMessage());
            throw BizException.serviceUnavailable(
                    ErrorCode.STOCK_UNAVAILABLE,
                    properties.getRetryAfterMs(),
                    "分区座位位图读取异常，请稍后刷新"
            );
        }

        if (bitmapBytes == null || bitmapBytes.length < minBytesRequired) {
            metrics.recordStockMissingKey();
            log.warn("Seat bitmap missing or truncated: showId={}, zoneId={}, actualBytes={}, requiredBytes={}",
                    showId, zoneId, bitmapBytes == null ? -1 : bitmapBytes.length, minBytesRequired);
            throw BizException.serviceUnavailable(
                    ErrorCode.STOCK_UNAVAILABLE,
                    properties.getRetryAfterMs(),
                    "分区座位位图缺失或异常，请稍后刷新"
            );
        }

        boolean tierSoldOut = (tierEval != null && tierEval.getStockState() == EntryState.SOLD_OUT)
                || eval.getEntryState() == EntryState.SOLD_OUT;
        EntryState effectiveZoneState = tierSoldOut ? EntryState.SOLD_OUT : EntryState.OPEN;

        int selectableCount = 0;
        List<SeatMapVO.SeatItem> seatItems = new ArrayList<>(seats.size());
        for (SeatDO seat : seats) {
            boolean occupiedInBitmap = ShowRedisScripts.isBitSetMsb(bitmapBytes, seat.getSeatIndex());
            boolean physicallyEnabled = (seat.getStatus() == null || seat.getStatus() == 0);
            boolean selectable = !tierSoldOut && physicallyEnabled && !occupiedInBitmap;
            if (selectable) {
                selectableCount++;
            }
            seatItems.add(SeatMapVO.SeatItem.builder()
                    .seatIndex(seat.getSeatIndex())
                    .rowNo(seat.getRowNo())
                    .colNo(seat.getColNo())
                    .segmentId(seat.getSegmentId() == null ? "S1" : seat.getSegmentId())
                    .selectable(selectable)
                    .build());
        }

        return SeatMapVO.builder()
                .showId(showId)
                .zoneId(zoneId.trim())
                .tierId(tierId)
                .layoutVersion(eval.getSaleConfigVersion())
                .asOf(ShowTimeUtil.toOffsetDateTime(nowMs))
                .available(selectableCount > 0)
                .stockState(effectiveZoneState.name())
                .seats(seatItems)
                .build();
    }

    private List<SeatMapVO.SeatItem> buildNonSelectableSeats(List<SeatDO> seats) {
        List<SeatMapVO.SeatItem> items = new ArrayList<>(seats.size());
        for (SeatDO seat : seats) {
            items.add(SeatMapVO.SeatItem.builder()
                    .seatIndex(seat.getSeatIndex())
                    .rowNo(seat.getRowNo())
                    .colNo(seat.getColNo())
                    .segmentId(seat.getSegmentId() == null ? "S1" : seat.getSegmentId())
                    .selectable(false)
                    .build());
        }
        return items;
    }
}
