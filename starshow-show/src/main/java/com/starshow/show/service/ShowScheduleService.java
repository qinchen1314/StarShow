package com.starshow.show.service;

import com.starshow.api.dto.ShowBasicDTO;
import com.starshow.api.facade.ShowReadFacade;
import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.show.domain.entity.ShowDO;
import com.starshow.show.mapper.ShowMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 演出开售排期变更服务（贯穿链路一预约提醒与链路二预热版本的权威配置源）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShowScheduleService {

    private static final Set<String> RESCHEDULABLE_STATUSES = Set.of("INIT", "PREHEATED");

    private final ShowMapper showMapper;
    private final ShowReadFacade showReadFacade;

    /**
     * 主办方调整未开售场次开售时间：
     * - 校验 saleEndTime > saleStartTime；
     * - 仅允许 INIT / PREHEATED 状态改期，已开售或已结束直接拒绝；
     * - 数据库乐观 CAS 原子推进 sale_config_version + 1（并回退 PREHEATED -> INIT）。
     */
    public ShowBasicDTO reschedule(long showId, LocalDateTime newSaleStartTime, LocalDateTime newSaleEndTime) {
        if (newSaleStartTime == null || newSaleEndTime == null || !newSaleEndTime.isAfter(newSaleStartTime)) {
            throw new BizException(ErrorCode.INVALID_PARAM, "停售时间必须晚于开售时间");
        }
        ShowDO current = showMapper.selectById(showId);
        if (current == null) {
            throw new BizException(ErrorCode.SHOW_NOT_FOUND);
        }
        String status = current.getSaleStatus() == null ? "" : current.getSaleStatus().toUpperCase();
        if (!RESCHEDULABLE_STATUSES.contains(status)) {
            throw new BizException(ErrorCode.SHOW_NOT_ON_SALE, "当前场次状态不允许改期: " + current.getSaleStatus());
        }

        int expectedVersion = current.getSaleConfigVersion() == null ? 1 : current.getSaleConfigVersion();
        int updated = showMapper.casUpdateSchedule(showId, expectedVersion, newSaleStartTime, newSaleEndTime);
        if (updated != 1) {
            throw new BizException(ErrorCode.SHOW_NOT_ON_SALE, "场次状态已变更或存在并发改期冲突，请重试");
        }

        ShowBasicDTO latest = showReadFacade.getShowBasic(showId);
        log.info("Show schedule updated: showId={}, version {} -> {}, saleStartTime={}, saleEndTime={}",
                showId, expectedVersion, latest == null ? expectedVersion + 1 : latest.effectiveSaleConfigVersion(),
                newSaleStartTime, newSaleEndTime);
        return latest;
    }
}
