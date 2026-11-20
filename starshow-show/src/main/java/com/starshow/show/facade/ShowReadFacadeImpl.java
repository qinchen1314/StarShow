package com.starshow.show.facade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.starshow.api.dto.ShowBasicDTO;
import com.starshow.api.dto.TierSnapshotDTO;
import com.starshow.api.facade.ShowReadFacade;
import com.starshow.show.cache.ShowDetailCacheService;
import com.starshow.show.cache.ShowGateEvaluator;
import com.starshow.show.config.ShowProperties;
import com.starshow.show.domain.entity.ShowDO;
import com.starshow.show.domain.entity.TicketTierDO;
import com.starshow.show.domain.model.ShowDetailCachePayload;
import com.starshow.show.mapper.ShowMapper;
import com.starshow.show.mapper.TicketTierMapper;
import com.starshow.show.util.ShowTimeUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 演出与票档只读契约本地实现（Phase 2 升级为 L1+L2 逻辑过期缓存 + 权威开售门禁 entryState 叠加）：
 * - getShowBasic 返回权威 saleConfigVersion、saleStartTime/saleEndTime、saleStatus、seatMode 及 entryState，
 *   让链路一 GET /api/v1/reservations/{showId}/me 的 entryOpen 与链路二详情页 100% 口径一致；
 * - getTierSnapshot 返回用于链路三下单校验的权威价格、限购张数、售卖窗口、配置版本与布局版本。
 */
@Slf4j
@Service
public class ShowReadFacadeImpl implements ShowReadFacade {

    private static final String SEAT_MODE_MANUAL = "MANUAL_SEAT";

    private final ShowMapper showMapper;
    private final TicketTierMapper ticketTierMapper;
    private final ShowDetailCacheService detailCacheService;
    private final ShowGateEvaluator gateEvaluator;
    private final ShowProperties properties;

    @Autowired
    public ShowReadFacadeImpl(ShowMapper showMapper,
                              TicketTierMapper ticketTierMapper,
                              ObjectProvider<ShowDetailCacheService> detailCacheServiceProvider,
                              ObjectProvider<ShowGateEvaluator> gateEvaluatorProvider,
                              ObjectProvider<ShowProperties> propertiesProvider) {
        this.showMapper = showMapper;
        this.ticketTierMapper = ticketTierMapper;
        this.detailCacheService = detailCacheServiceProvider == null ? null : detailCacheServiceProvider.getIfAvailable();
        this.gateEvaluator = gateEvaluatorProvider == null ? null : gateEvaluatorProvider.getIfAvailable();
        this.properties = propertiesProvider == null ? new ShowProperties() : propertiesProvider.getIfAvailable(ShowProperties::new);
    }

    /**
     * 兼容 Phase 1 既有双参单测构造
     */
    public ShowReadFacadeImpl(ShowMapper showMapper, TicketTierMapper ticketTierMapper) {
        this.showMapper = showMapper;
        this.ticketTierMapper = ticketTierMapper;
        this.detailCacheService = null;
        this.gateEvaluator = null;
        this.properties = new ShowProperties();
    }

    @Override
    public ShowBasicDTO getShowBasic(long showId) {
        if (detailCacheService != null && gateEvaluator != null) {
            ShowDetailCachePayload detail = detailCacheService.getDetail(showId, false);
            if (detail == null) {
                return null;
            }
            ShowGateEvaluator.GateEvaluation eval = gateEvaluator.evaluate(detail, System.currentTimeMillis(), true);
            return ShowBasicDTO.builder()
                    .showId(detail.getShowId())
                    .title(detail.getTitle())
                    .saleStartTime(ShowTimeUtil.toLocalDateTime(eval.getSaleStartEpochMs()))
                    .saleEndTime(ShowTimeUtil.toLocalDateTime(eval.getSaleEndEpochMs()))
                    .saleStatus(eval.getSaleStatus())
                    .saleConfigVersion(eval.getSaleConfigVersion())
                    .seatMode(detail.getSeatMode())
                    .entryState(eval.getEntryState().name())
                    .build();
        }

        ShowDO show = showMapper.selectById(showId);
        if (show == null) {
            return null;
        }
        return ShowBasicDTO.builder()
                .showId(show.getId())
                .title(show.getTitle())
                .saleStartTime(show.getSaleStartTime())
                .saleEndTime(show.getSaleEndTime())
                .saleStatus(show.getSaleStatus())
                .saleConfigVersion(show.getSaleConfigVersion() == null ? 1 : show.getSaleConfigVersion())
                .seatMode(show.getSeatMode())
                .build();
    }

    @Override
    public TierSnapshotDTO getTierSnapshot(long showId, long tierId) {
        if (detailCacheService != null && gateEvaluator != null) {
            ShowDetailCachePayload detail = detailCacheService.getDetail(showId, false);
            if (detail == null) {
                return null;
            }
            ShowDetailCachePayload.CachedTier matched = null;
            if (detail.getTiers() != null) {
                for (ShowDetailCachePayload.CachedTier t : detail.getTiers()) {
                    if (t.getTierId() != null && t.getTierId() == tierId) {
                        matched = t;
                        break;
                    }
                }
            }
            if (matched == null) {
                return null;
            }
            ShowGateEvaluator.GateEvaluation eval = gateEvaluator.evaluate(detail, System.currentTimeMillis(), false);
            int version = eval.getSaleConfigVersion();
            return TierSnapshotDTO.builder()
                    .showId(detail.getShowId())
                    .tierId(matched.getTierId())
                    .tierName(matched.getTierName())
                    .zoneId(matched.getZoneId())
                    .priceCent(matched.getPriceCent())
                    .saleStatus(eval.getSaleStatus())
                    .seatMode(detail.getSeatMode())
                    .saleStartTime(ShowTimeUtil.toLocalDateTime(eval.getSaleStartEpochMs()))
                    .saleEndTime(ShowTimeUtil.toLocalDateTime(eval.getSaleEndEpochMs()))
                    .saleConfigVersion(version)
                    .maxTicketsPerOrder(properties.getMaxTicketsPerOrder())
                    .layoutVersion(SEAT_MODE_MANUAL.equalsIgnoreCase(detail.getSeatMode()) ? String.valueOf(version) : null)
                    .build();
        }

        ShowDO show = showMapper.selectById(showId);
        if (show == null) {
            return null;
        }
        TicketTierDO tier = ticketTierMapper.selectOne(
                new LambdaQueryWrapper<TicketTierDO>()
                        .eq(TicketTierDO::getId, tierId)
                        .eq(TicketTierDO::getShowId, showId)
        );
        if (tier == null) {
            return null;
        }
        int version = show.getSaleConfigVersion() == null ? 1 : show.getSaleConfigVersion();
        return TierSnapshotDTO.builder()
                .showId(show.getId())
                .tierId(tier.getId())
                .tierName(tier.getTierName())
                .zoneId(tier.getZoneId())
                .priceCent(tier.getPriceCent())
                .saleStatus(show.getSaleStatus())
                .seatMode(show.getSeatMode())
                .saleStartTime(show.getSaleStartTime())
                .saleEndTime(show.getSaleEndTime())
                .saleConfigVersion(version)
                .maxTicketsPerOrder(properties.getMaxTicketsPerOrder())
                .layoutVersion(SEAT_MODE_MANUAL.equalsIgnoreCase(show.getSeatMode()) ? String.valueOf(version) : null)
                .build();
    }
}
