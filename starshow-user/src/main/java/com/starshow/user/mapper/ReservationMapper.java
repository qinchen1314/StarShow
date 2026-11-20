package com.starshow.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starshow.user.domain.entity.ReservationDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface ReservationMapper extends BaseMapper<ReservationDO> {

    /**
     * 消费端/终态收敛数据库级 CAS（ISSUE-004 兼容入口）：
     * 接受 0(待投递/超窗)、2(失败/超时晚到)、3(正常排期) -> 1(终态已送达)。
     */
    @Update("UPDATE t_reservation SET remind_status = 1 WHERE id = #{id} AND remind_status IN (0, 2, 3) AND deleted = 0")
    int casMarkReminded(@Param("id") long id);

    /**
     * 版本守卫消费端终态 CAS（Phase 1.1）：仅当 remind_config_version == expectedVersion 且未终态才推进为 1(已送达)，
     * 防止主办方改期后旧版本延迟消息晚到把新版本的提醒状态误标为已送达。
     */
    @Update("UPDATE t_reservation SET remind_status = 1 " +
            "WHERE id = #{id} AND remind_status IN (0, 2, 3) AND remind_config_version = #{expectedVersion} AND deleted = 0")
    int casMarkDeliveredForVersion(@Param("id") long id, @Param("expectedVersion") int expectedVersion);

    /**
     * 无需发送提醒终态 CAS（Phase 1.1）：预约或改期时场次已开售 / 已结束 / 场次不存在 -> 4(SKIPPED)，与真送达 1 明确区分。
     */
    @Update("UPDATE t_reservation SET remind_status = 4 " +
            "WHERE id = #{id} AND remind_status IN (0, 2, 3) AND deleted = 0")
    int casMarkSkipped(@Param("id") long id);

    /**
     * 生产端 Broker 确认受理 CAS（兼容入口）
     */
    @Update("UPDATE t_reservation SET remind_status = 3 WHERE id = #{id} AND remind_status IN (0, 2) AND deleted = 0")
    int casMarkScheduled(@Param("id") long id);

    /**
     * 版本守卫 Broker 确认受理 CAS：仅允许当前版本 0/2 -> 3，绝不覆盖终态 1/4 或更高版本记录
     */
    @Update("UPDATE t_reservation SET remind_status = 3 " +
            "WHERE id = #{id} AND remind_status IN (0, 2) AND remind_config_version = #{expectedVersion} AND deleted = 0")
    int casMarkScheduledForVersion(@Param("id") long id, @Param("expectedVersion") int expectedVersion);

    /**
     * 生产端发送失败或超时 CAS（ISSUE-004 & ISSUE-009 兼容入口）
     */
    @Update("UPDATE t_reservation SET remind_status = 2, next_retry_time = #{nextRetryTime} " +
            "WHERE id = #{id} AND remind_status IN (0, 2) AND deleted = 0")
    int casMarkFailedWithRetry(@Param("id") long id, @Param("nextRetryTime") LocalDateTime nextRetryTime);

    /**
     * 版本守卫生产端发送失败/超时 CAS
     */
    @Update("UPDATE t_reservation SET remind_status = 2, next_retry_time = #{nextRetryTime} " +
            "WHERE id = #{id} AND remind_status IN (0, 2) AND remind_config_version = #{expectedVersion} AND deleted = 0")
    int casMarkFailedWithRetryForVersion(
            @Param("id") long id,
            @Param("expectedVersion") int expectedVersion,
            @Param("nextRetryTime") LocalDateTime nextRetryTime
    );

    /**
     * 超窗或单场次元数据暂不可用时推迟下次可调度时间（兼容入口）
     */
    @Update("UPDATE t_reservation SET next_retry_time = #{nextRetryTime} " +
            "WHERE id = #{id} AND remind_status IN (0, 2) AND deleted = 0")
    int casDeferNextRetryTime(@Param("id") long id, @Param("nextRetryTime") LocalDateTime nextRetryTime);

    /**
     * 版本守卫超窗/元数据退避推迟下次调度时间
     */
    @Update("UPDATE t_reservation SET next_retry_time = #{nextRetryTime} " +
            "WHERE id = #{id} AND remind_status IN (0, 2) AND remind_config_version = #{expectedVersion} AND deleted = 0")
    int casDeferNextRetryTimeForVersion(
            @Param("id") long id,
            @Param("expectedVersion") int expectedVersion,
            @Param("nextRetryTime") LocalDateTime nextRetryTime
    );

    /**
     * 主办方改期联动（Phase 1.1）：将该场次低于新版本的有效预约批量重置为待重排（0）并更新版本号，
     * 令已排期/已失败/甚至此前提前送达旧版本的预约全部按新开售时间重新排期。
     */
    @Update("UPDATE t_reservation SET remind_status = 0, remind_config_version = #{newVersion}, next_retry_time = #{nextRetryTime} " +
            "WHERE show_id = #{showId} AND remind_config_version < #{newVersion} AND deleted = 0")
    int resetReservationsForScheduleVersion(
            @Param("showId") long showId,
            @Param("newVersion") int newVersion,
            @Param("nextRetryTime") LocalDateTime nextRetryTime
    );

    /**
     * 复用 uk_user_show(user_id, show_id) 精准查询本人某场次预约记录（支持超时/换设备恢复确认）
     */
    @Select("SELECT id, user_id, show_id, remind_status, remind_config_version, next_retry_time, deleted, create_time, update_time " +
            "FROM t_reservation WHERE user_id = #{userId} AND show_id = #{showId} AND deleted = 0 LIMIT 1")
    ReservationDO selectByUserAndShow(@Param("userId") long userId, @Param("showId") long showId);

    /**
     * 复用 uk_user_show 最左前缀索引查询本人预约列表（我的预约统一入口）
     */
    @Select("SELECT id, user_id, show_id, remind_status, remind_config_version, next_retry_time, deleted, create_time, update_time " +
            "FROM t_reservation WHERE user_id = #{userId} AND deleted = 0 ORDER BY create_time DESC, id DESC LIMIT #{limit}")
    List<ReservationDO> selectByUserId(@Param("userId") long userId, @Param("limit") int limit);

    /**
     * 游标分页查询指定场次下所有有效预约（供改期即时变更通知批量落账使用）
     */
    @Select("SELECT id, user_id, show_id, remind_status, remind_config_version, next_retry_time, deleted, create_time, update_time " +
            "FROM t_reservation WHERE show_id = #{showId} AND id > #{afterId} AND deleted = 0 ORDER BY id ASC LIMIT #{limit}")
    List<ReservationDO> selectByShowIdCursor(
            @Param("showId") long showId,
            @Param("afterId") long afterId,
            @Param("limit") int limit
    );

    /**
     * 按可调度时间 next_retry_time <= now 与主键游标 id > afterId 批量查询到期候选记录（走联合索引 idx_remind_retry）
     */
    @Select("SELECT id, user_id, show_id, remind_status, remind_config_version, next_retry_time, deleted, create_time, update_time " +
            "FROM t_reservation " +
            "WHERE remind_status IN (0, 2) AND next_retry_time <= #{now} AND id > #{afterId} AND deleted = 0 " +
            "ORDER BY id ASC LIMIT #{limit}")
    List<ReservationDO> selectDueRecoverableReminders(
            @Param("now") LocalDateTime now,
            @Param("afterId") long afterId,
            @Param("limit") int limit
    );
}
