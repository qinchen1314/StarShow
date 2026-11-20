package com.starshow.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starshow.user.domain.entity.UserNotificationDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface UserNotificationMapper extends BaseMapper<UserNotificationDO> {

    @Select("SELECT id, user_id, show_id, reservation_id, sale_config_version, notification_type, biz_key, title, content, " +
            "action_url, read_flag, read_time, deleted, create_time, update_time " +
            "FROM t_user_notification " +
            "WHERE user_id = #{userId} AND deleted = 0 " +
            "ORDER BY create_time DESC, id DESC LIMIT #{limit}")
    List<UserNotificationDO> selectRecentByUserId(@Param("userId") long userId, @Param("limit") int limit);

    @Select("SELECT id, user_id, show_id, reservation_id, sale_config_version, notification_type, biz_key, title, content, " +
            "action_url, read_flag, read_time, deleted, create_time, update_time " +
            "FROM t_user_notification " +
            "WHERE id = #{id} AND user_id = #{userId} AND deleted = 0 LIMIT 1")
    UserNotificationDO selectByIdAndUserId(@Param("id") long id, @Param("userId") long userId);

    @Update("UPDATE t_user_notification SET read_flag = 1, read_time = #{readTime} " +
            "WHERE id = #{id} AND user_id = #{userId} AND read_flag = 0 AND deleted = 0")
    int casMarkRead(@Param("id") long id, @Param("userId") long userId, @Param("readTime") LocalDateTime readTime);
}
