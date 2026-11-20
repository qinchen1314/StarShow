package com.starshow.show.service;

import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.show.cache.ShowDetailCacheService;
import com.starshow.show.cache.ShowGateEvaluator;
import com.starshow.show.config.ShowProperties;
import com.starshow.show.domain.model.EntryState;
import com.starshow.show.domain.model.ShowDetailCachePayload;
import com.starshow.show.util.ShowTimeUtil;
import com.starshow.show.vo.ShowStockVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 链路二（节点 1）：实时票档余量查询服务
 * - 严格区分 PREPARING / OPEN / SOLD_OUT / STOCK_UNAVAILABLE / ENDED；
 * - 售卖期部分票档缺失返回 200 并对该票档标 available=null & STOCK_UNAVAILABLE；
 * - 售卖期整体库存不可用（或全部键缺失/Redis 故障）直接返回 HTTP 503 + B0201，绝不误报 SOLD_OUT！
 */
@Service
@RequiredArgsConstructor
public class ShowStockQueryService {

    private final ShowDetailCacheService detailCacheService;
    private final ShowGateEvaluator gateEvaluator;
    private final ShowProperties properties;

    public ShowStockVO getShowStock(long showId) {
        ShowDetailCachePayload detail = detailCacheService.getDetail(showId);
        if (detail == null) {
            throw new BizException(ErrorCode.SHOW_NOT_FOUND);
        }

        long nowMs = System.currentTimeMillis();
        ShowGateEvaluator.GateEvaluation eval = gateEvaluator.evaluate(detail, nowMs, true);

        // 在售期如果整体库存不可用（无任何有效票档或 Redis 故障/门禁丢失），直接返回 503 / B0201
        if (eval.getEntryState() == EntryState.STOCK_UNAVAILABLE && !eval.isAnyTierValid()) {
            throw BizException.serviceUnavailable(
                    ErrorCode.STOCK_UNAVAILABLE,
                    properties.getRetryAfterMs(),
                    "当前场次库存暂不可用，请稍后刷新"
            );
        }

        List<ShowStockVO.TierStockItem> items = new ArrayList<>();
        if (eval.getTiers() != null) {
            for (ShowGateEvaluator.TierStockEval t : eval.getTiers()) {
                items.add(ShowStockVO.TierStockItem.builder()
                        .tierId(t.getTierId())
                        .available(t.getAvailable())
                        .stockState(t.getStockState().name())
                        .hint(ShowGateEvaluator.entryHintFor(t.getStockState()))
                        .build());
            }
        }

        return ShowStockVO.builder()
                .showId(showId)
                .saleConfigVersion(eval.getSaleConfigVersion())
                .asOf(ShowTimeUtil.toOffsetDateTime(eval.getAsOfEpochMs()))
                .stale(eval.isStale())
                .entryState(eval.getEntryState().name())
                .tiers(items)
                .build();
    }
}
