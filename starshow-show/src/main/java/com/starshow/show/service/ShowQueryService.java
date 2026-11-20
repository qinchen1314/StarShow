package com.starshow.show.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.show.cache.ShowDetailCacheService;
import com.starshow.show.cache.ShowGateEvaluator;
import com.starshow.show.domain.entity.ShowDO;
import com.starshow.show.domain.model.ShowDetailCachePayload;
import com.starshow.show.mapper.ShowMapper;
import com.starshow.show.util.ShowTimeUtil;
import com.starshow.show.vo.ShowDetailVO;
import com.starshow.show.vo.ShowListVO;
import com.starshow.show.vo.ShowSummaryVO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 链路二（节点 0）：演出目录稳定游标列表与多级缓存详情服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShowQueryService {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;

    private final ShowMapper showMapper;
    private final ShowDetailCacheService detailCacheService;
    private final ShowGateEvaluator gateEvaluator;
    private final ObjectMapper objectMapper;

    /**
     * 稳定游标分页查询演出摘要列表（按 show_time ASC, id ASC 排序，游标强绑定 city 筛选条件）
     */
    public ShowListVO listShows(String city, String cursor, Integer limit) {
        int pageSize = (limit == null) ? DEFAULT_LIMIT : limit;
        if (pageSize < 1 || pageSize > MAX_LIMIT) {
            throw new BizException(ErrorCode.INVALID_PARAM, "limit 必须在 1 到 50 之间");
        }
        String normalizedCity = normalizeCity(city);

        LocalDateTime cursorShowTime = null;
        Long cursorId = null;
        if (cursor != null && !cursor.isBlank()) {
            CursorPayload decoded = decodeCursor(cursor);
            if (!Objects.equals(normalizedCity, normalizeCity(decoded.getC()))) {
                throw new BizException(ErrorCode.INVALID_PARAM, "游标与当前筛选条件不匹配，请重新拉取第一页");
            }
            if (decoded.getT() == null || decoded.getId() == null) {
                throw new BizException(ErrorCode.INVALID_PARAM, "游标格式无效");
            }
            cursorShowTime = ShowTimeUtil.toLocalDateTime(decoded.getT());
            cursorId = decoded.getId();
        }

        List<ShowDO> rows = showMapper.selectPageByCursor(normalizedCity, cursorShowTime, cursorId, pageSize + 1);
        if (rows == null || rows.isEmpty()) {
            return ShowListVO.builder()
                    .items(Collections.emptyList())
                    .nextCursor(null)
                    .build();
        }

        boolean hasMore = rows.size() > pageSize;
        List<ShowDO> pageRows = hasMore ? rows.subList(0, pageSize) : rows;
        List<ShowSummaryVO> items = new ArrayList<>(pageRows.size());
        for (ShowDO row : pageRows) {
            items.add(ShowSummaryVO.builder()
                    .showId(row.getId())
                    .title(row.getTitle())
                    .artist(row.getArtist())
                    .city(row.getCity())
                    .venue(row.getVenue())
                    .showTime(ShowTimeUtil.toOffsetDateTime(row.getShowTime()))
                    .saleStartTime(ShowTimeUtil.toOffsetDateTime(row.getSaleStartTime()))
                    .saleEndTime(ShowTimeUtil.toOffsetDateTime(row.getSaleEndTime()))
                    .seatMode(row.getSeatMode())
                    .posterUrl(row.getPosterUrl())
                    .build());
        }

        String nextCursor = null;
        if (hasMore) {
            ShowDO last = pageRows.get(pageRows.size() - 1);
            nextCursor = encodeCursor(new CursorPayload(
                    normalizedCity,
                    ShowTimeUtil.toEpochMs(last.getShowTime()),
                    last.getId()
            ));
        }

        return ShowListVO.builder()
                .items(items)
                .nextCursor(nextCursor)
                .build();
    }

    /**
     * 查询演出详情（静态元数据走 L1+L2 逻辑过期，动态开售时间/版本/入口状态与各票档参考状态走权威热态叠加）
     */
    public ShowDetailVO getShowDetail(long showId) {
        ShowDetailCachePayload detail = detailCacheService.getDetail(showId);
        if (detail == null) {
            throw new BizException(ErrorCode.SHOW_NOT_FOUND);
        }

        long nowMs = System.currentTimeMillis();
        ShowGateEvaluator.GateEvaluation eval = gateEvaluator.evaluate(detail, nowMs, true);

        List<ShowDetailCachePayload.CachedTier> cachedTiers = detail.getTiers() == null
                ? Collections.emptyList()
                : detail.getTiers();
        List<ShowDetailVO.TierItem> tierItems = new ArrayList<>(cachedTiers.size());
        for (ShowDetailCachePayload.CachedTier tier : cachedTiers) {
            ShowGateEvaluator.TierStockEval stockEval = eval.findTier(tier.getTierId());
            tierItems.add(ShowDetailVO.TierItem.builder()
                    .tierId(tier.getTierId())
                    .tierName(tier.getTierName())
                    .priceCent(tier.getPriceCent())
                    .zoneId(tier.getZoneId())
                    .available(stockEval == null ? null : stockEval.getAvailable())
                    .stockState(stockEval == null ? eval.getEntryState().name() : stockEval.getStockState().name())
                    .build());
        }

        return ShowDetailVO.builder()
                .showId(detail.getShowId())
                .saleConfigVersion(eval.getSaleConfigVersion())
                .title(detail.getTitle())
                .artist(detail.getArtist())
                .city(detail.getCity())
                .venue(detail.getVenue())
                .showTime(ShowTimeUtil.toOffsetDateTime(detail.getShowTimeEpochMs()))
                .saleStartTime(ShowTimeUtil.toOffsetDateTime(eval.getSaleStartEpochMs()))
                .saleEndTime(ShowTimeUtil.toOffsetDateTime(eval.getSaleEndEpochMs()))
                .serverTime(ShowTimeUtil.toOffsetDateTime(nowMs))
                .saleStatus(eval.getSaleStatus())
                .entryState(eval.getEntryState().name())
                .entryHint(ShowGateEvaluator.entryHintFor(eval.getEntryState()))
                .seatMode(detail.getSeatMode())
                .posterUrl(detail.getPosterUrl())
                .description(detail.getDescription())
                .tiers(tierItems)
                .build();
    }

    private String normalizeCity(String city) {
        if (city == null) {
            return null;
        }
        String trimmed = city.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String encodeCursor(CursorPayload payload) {
        try {
            byte[] jsonBytes = objectMapper.writeValueAsBytes(payload);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(jsonBytes);
        } catch (Exception ex) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "游标编码失败", ex);
        }
    }

    private CursorPayload decodeCursor(String cursor) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            return objectMapper.readValue(new String(decoded, StandardCharsets.UTF_8), CursorPayload.class);
        } catch (Exception ex) {
            throw new BizException(ErrorCode.INVALID_PARAM, "游标参数格式错误");
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class CursorPayload {
        private String c;
        private Long t;
        private Long id;
    }
}
