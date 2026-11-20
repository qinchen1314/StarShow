package com.starshow.user.mq;

import com.starshow.api.event.ShowRemindEvent;
import com.starshow.user.mapper.ReservationMapper;
import com.starshow.user.service.UserNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 提醒送达事务编排服务（将 t_reservation 终态 CAS=1 与 t_user_notification 站内通知写入绑定在同一本地事务中提交）
 */
@Service
@RequiredArgsConstructor
public class ShowRemindDeliveryService {

    private final ReservationMapper reservationMapper;
    private final UserNotificationService userNotificationService;

    /**
     * 仅当预约记录仍处于预期开售配置版本且尚未送达时，在同一本地事务内完成：
     * 1. CAS 将 remind_status 置为 1 (DELIVERED)；
     * 2. 幂等写入 t_user_notification（uk_biz_key 防重）。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean deliverIfVersionMatches(ShowRemindEvent event, int expectedVersion) {
        int updated = reservationMapper.casMarkDeliveredForVersion(event.getReservationId(), expectedVersion);
        if (updated != 1) {
            return false;
        }
        userNotificationService.recordReminderNotification(event);
        return true;
    }
}
