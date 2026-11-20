package com.starshow.user.service;

import com.starshow.api.dto.ShowBasicDTO;
import com.starshow.api.event.ShowRemindEvent;
import com.starshow.common.exception.BizException;
import com.starshow.common.id.GeneSnowflakeIdGenerator;
import com.starshow.common.result.ErrorCode;
import com.starshow.user.domain.entity.UserNotificationDO;
import com.starshow.user.mapper.UserNotificationMapper;
import com.starshow.user.vo.UserNotificationVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 用户站内通知服务（开票提醒与消费终态同事务落账，支持换设备查看与已读指标统计）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserNotificationService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final UserNotificationMapper userNotificationMapper;
    private final GeneSnowflakeIdGenerator idGenerator;

    public static String buildReminderBizKey(long reservationId, int saleConfigVersion) {
        return "rsv:" + reservationId + ":v" + Math.max(1, saleConfigVersion);
    }

    public static String buildScheduleChangedBizKey(long reservationId, int saleConfigVersion) {
        return "resched:" + reservationId + ":v" + Math.max(1, saleConfigVersion);
    }

    /**
     * 幂等写入开票前提醒站内通知（依赖 uk_biz_key 物理防重，重放消息只保留一条）
     */
    public boolean recordReminderNotification(ShowRemindEvent event) {
        int version = event.effectiveSaleConfigVersion();
        String bizKey = buildReminderBizKey(event.getReservationId(), version);
        String saleTimeText = event.getSaleStartTime() == null ? "即将开售" : event.getSaleStartTime().format(TIME_FORMATTER);
        String showTitle = event.getShowTitle() == null ? ("场次 " + event.getShowId()) : event.getShowTitle();

        UserNotificationDO record = UserNotificationDO.builder()
                .id(idGenerator.nextId())
                .userId(event.getUserId())
                .showId(event.getShowId())
                .reservationId(event.getReservationId())
                .saleConfigVersion(version)
                .notificationType(UserNotificationDO.TYPE_REMIND_BEFORE_SALE)
                .bizKey(bizKey)
                .title("开票提醒 · " + showTitle)
                .content("您预约的《" + showTitle + "》将于 " + saleTimeText + " 开售（排期版本 v" + version + "），请提前进入购票页准备。")
                .actionUrl("/shows/" + event.getShowId())
                .readFlag(0)
                .deleted(0)
                .build();
        try {
            userNotificationMapper.insert(record);
            return true;
        } catch (DuplicateKeyException ex) {
            log.info("Duplicate reminder notification ignored idempotently: bizKey={}", bizKey);
            return false;
        }
    }

    /**
     * 主办方改期后立即落一条站内变更通知（依赖 uk_biz_key 物理防重，让粉丝改期当下即获知新开售时间）
     */
    public boolean recordScheduleChangedNotification(long reservationId, long userId, ShowBasicDTO show) {
        if (show == null) {
            return false;
        }
        int version = show.effectiveSaleConfigVersion();
        String bizKey = buildScheduleChangedBizKey(reservationId, version);
        String saleTimeText = show.getSaleStartTime() == null ? "待定" : show.getSaleStartTime().format(TIME_FORMATTER);
        String showTitle = show.getTitle() == null ? ("场次 " + show.getShowId()) : show.getTitle();

        UserNotificationDO record = UserNotificationDO.builder()
                .id(idGenerator.nextId())
                .userId(userId)
                .showId(show.getShowId())
                .reservationId(reservationId)
                .saleConfigVersion(version)
                .notificationType(UserNotificationDO.TYPE_SCHEDULE_CHANGED)
                .bizKey(bizKey)
                .title("开售时间变更 · " + showTitle)
                .content("您预约的《" + showTitle + "》开售时间已调整为 " + saleTimeText + "（排期版本 v" + version + "），系统已自动为您更新开票提醒。")
                .actionUrl("/shows/" + show.getShowId())
                .readFlag(0)
                .deleted(0)
                .build();
        try {
            userNotificationMapper.insert(record);
            return true;
        } catch (DuplicateKeyException ex) {
            log.info("Duplicate schedule-changed notification ignored idempotently: bizKey={}", bizKey);
            return false;
        }
    }

    public List<UserNotificationVO> listMyNotifications(long userId, Integer limit) {
        int safeLimit = (limit == null || limit <= 0) ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        List<UserNotificationDO> rows = userNotificationMapper.selectRecentByUserId(userId, safeLimit);
        return rows.stream().map(this::toVO).toList();
    }

    public UserNotificationVO markAsRead(long userId, long notificationId) {
        UserNotificationDO existing = userNotificationMapper.selectByIdAndUserId(notificationId, userId);
        if (existing == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "站内通知不存在");
        }
        if (existing.getReadFlag() == null || existing.getReadFlag() == 0) {
            userNotificationMapper.casMarkRead(notificationId, userId, LocalDateTime.now());
            existing = userNotificationMapper.selectByIdAndUserId(notificationId, userId);
        }
        return toVO(existing);
    }

    private UserNotificationVO toVO(UserNotificationDO row) {
        return UserNotificationVO.builder()
                .notificationId(row.getId())
                .showId(row.getShowId())
                .reservationId(row.getReservationId())
                .saleConfigVersion(row.getSaleConfigVersion())
                .notificationType(row.getNotificationType())
                .bizKey(row.getBizKey())
                .title(row.getTitle())
                .content(row.getContent())
                .actionUrl(row.getActionUrl())
                .read(row.getReadFlag() != null && row.getReadFlag() == 1)
                .readTime(row.getReadTime())
                .createTime(row.getCreateTime())
                .build();
    }
}
