package com.starshow.user.facade;

import com.starshow.api.dto.ShowRescheduleSyncDTO;
import com.starshow.api.facade.ReservationRescheduleFacade;
import com.starshow.user.mq.ShowRemindRecoveryScheduler;
import com.starshow.user.vo.ShowRescheduleResultVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 演出改期联动重排预约提醒本地实现（monolith 单进程聚合态由 starshow-show 直接注入调用，零 HTTP 自调用）
 */
@Service
@RequiredArgsConstructor
public class ReservationRescheduleFacadeImpl implements ReservationRescheduleFacade {

    private final ShowRemindRecoveryScheduler showRemindRecoveryScheduler;

    @Override
    public ShowRescheduleSyncDTO rescheduleShowReminders(long showId) {
        ShowRescheduleResultVO vo = showRemindRecoveryScheduler.rescheduleForShowVersion(showId);
        if (vo == null) {
            return null;
        }
        return ShowRescheduleSyncDTO.builder()
                .showId(vo.getShowId())
                .saleConfigVersion(vo.getSaleConfigVersion())
                .saleStartTime(vo.getSaleStartTime())
                .resetReservationCount(vo.getResetReservationCount())
                .progressedCount(vo.getProgressedCount())
                .scheduleNotifiedCount(vo.getScheduleNotifiedCount())
                .build();
    }
}
