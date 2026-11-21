package com.starshow.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starshow.order.domain.entity.OrderActionDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单异步动作与创单裁决（CREATE_DECISION / CLOSE_CHECK / RELEASE_STOCK）Mapper
 */
public interface OrderActionMapper extends BaseMapper<OrderActionDO> {

    /**
     * 先 INSERT IGNORE 确保唯一裁决行存在，再执行唯一索引等值 FOR UPDATE，
     * 避免先对不存在记录 SELECT ... FOR UPDATE 触发 InnoDB supremum Gap Lock 死锁。
     */
    @Insert("INSERT IGNORE INTO t_order_action (" +
            "action_id, order_no, user_id, show_id, action_type, status, payload_json, " +
            "next_retry_at, lease_until, attempt, last_error_code, deleted, create_time, update_time" +
            ") VALUES (" +
            "#{action.actionId}, #{action.orderNo}, #{action.userId}, #{action.showId}, #{action.actionType}, " +
            "#{action.status}, #{action.payloadJson}, #{action.nextRetryAt}, #{action.leaseUntil}, " +
            "#{action.attempt}, #{action.lastErrorCode}, 0, #{action.createTime}, #{action.updateTime}" +
            ")")
    int insertIgnore(@Param("action") OrderActionDO action);

    @Select("SELECT * FROM t_order_action WHERE order_no = #{orderNo} AND action_type = #{actionType} AND deleted = 0 LIMIT 1")
    OrderActionDO selectByOrderAndType(@Param("orderNo") long orderNo, @Param("actionType") String actionType);

    /**
     * 事务内悲观锁查询指定订单动作行（用于消费端与悬挂巡检对 CREATE_DECISION 的互斥裁决）
     */
    @Select("SELECT * FROM t_order_action WHERE order_no = #{orderNo} AND action_type = #{actionType} AND deleted = 0 FOR UPDATE")
    OrderActionDO selectByOrderAndTypeForUpdate(@Param("orderNo") long orderNo, @Param("actionType") String actionType);

    @Update("UPDATE t_order_action SET status = #{targetStatus}, payload_json = COALESCE(#{payloadJson}, payload_json), " +
            "last_error_code = #{lastErrorCode}, update_time = #{now} " +
            "WHERE order_no = #{orderNo} AND action_type = #{actionType} AND status = #{expectedStatus} AND deleted = 0")
    int casUpdateStatus(@Param("orderNo") long orderNo,
                        @Param("actionType") String actionType,
                        @Param("expectedStatus") String expectedStatus,
                        @Param("targetStatus") String targetStatus,
                        @Param("payloadJson") String payloadJson,
                        @Param("lastErrorCode") String lastErrorCode,
                        @Param("now") LocalDateTime now);

    @Select("SELECT * FROM t_order_action WHERE action_type = #{actionType} AND status = 'PENDING' " +
            "AND next_retry_at <= #{now} AND deleted = 0 ORDER BY next_retry_at ASC, action_id ASC LIMIT #{limit}")
    List<OrderActionDO> selectDuePendingActions(@Param("actionType") String actionType,
                                                @Param("now") LocalDateTime now,
                                                @Param("limit") int limit);

    @Select("""
            SELECT * FROM t_order_action WHERE action_type = 'RELEASE_STOCK' AND payload_json IS NULL
            AND status = 'PENDING' AND next_retry_at <= #{now} AND deleted = 0
            ORDER BY next_retry_at, action_id LIMIT #{limit}
            """)
    List<OrderActionDO> selectDueFulfillmentReleases(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("UPDATE t_order_action SET status = 'SENT', attempt = attempt + 1, last_error_code = NULL, update_time = #{now} " +
            "WHERE action_id = #{actionId} AND status = 'PENDING' AND deleted = 0")
    int casMarkSent(@Param("actionId") long actionId, @Param("now") LocalDateTime now);

    @Update("UPDATE t_order_action SET status = 'DONE', attempt = attempt + 1, last_error_code = NULL, update_time = #{now} " +
            "WHERE action_id = #{actionId} AND status = 'PENDING' AND deleted = 0")
    int casMarkDone(@Param("actionId") long actionId, @Param("now") LocalDateTime now);

    @Update("UPDATE t_order_action SET attempt = attempt + 1, next_retry_at = #{nextRetryAt}, " +
            "last_error_code = #{errorCode}, update_time = #{now} " +
            "WHERE action_id = #{actionId} AND status = 'PENDING' AND deleted = 0")
    int recordRetryFailure(@Param("actionId") long actionId,
                           @Param("nextRetryAt") LocalDateTime nextRetryAt,
                           @Param("errorCode") String errorCode,
                           @Param("now") LocalDateTime now);
}
