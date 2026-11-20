package com.starshow.api.facade;

import com.starshow.api.dto.ShowRescheduleSyncDTO;

/**
 * 演出改期后联动链路一（starshow-user）重排预约提醒的内部调用契约：
 * - monolith 单进程聚合模式：直接注入 starshow-user 本地实现，零 HTTP 自调用；
 * - distributed 分布式模式：由 starshow-show 通过 OpenFeign 调用 POST /internal/reservations/shows/{showId}/reschedule。
 */
public interface ReservationRescheduleFacade {

    /**
     * 按场次最新 saleConfigVersion 重排该场次已有预约提醒并落盘 SCHEDULE_CHANGED 通知
     */
    ShowRescheduleSyncDTO rescheduleShowReminders(long showId);
}
