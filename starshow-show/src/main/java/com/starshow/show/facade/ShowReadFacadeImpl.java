package com.starshow.show.facade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.starshow.api.dto.ShowBasicDTO;
import com.starshow.api.dto.TierSnapshotDTO;
import com.starshow.api.facade.ShowReadFacade;
import com.starshow.show.domain.entity.ShowDO;
import com.starshow.show.domain.entity.TicketTierDO;
import com.starshow.show.mapper.ShowMapper;
import com.starshow.show.mapper.TicketTierMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 演出只读契约本地实现（Phase 1 最小 DB 直查实现；Phase 2 无缝升级为 L1+L2 逻辑过期缓存）
 */
@Service
@RequiredArgsConstructor
public class ShowReadFacadeImpl implements ShowReadFacade {

    private final ShowMapper showMapper;
    private final TicketTierMapper ticketTierMapper;

    @Override
    public ShowBasicDTO getShowBasic(long showId) {
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
                .build();
    }

    @Override
    public TierSnapshotDTO getTierSnapshot(long showId, long tierId) {
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
                .build();
    }
}
