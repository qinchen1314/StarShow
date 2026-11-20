package com.starshow.order.mapper;

import com.starshow.order.domain.entity.OrderDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单基因分表（t_order_0..3）单分片精准操作 Mapper
 * 所有 tableName 参数必须经 OrderShardTableResolver 严格白名单解析。
 */
public interface OrderShardMapper {

    @Insert("INSERT INTO ${tableName} (" +
            "order_no, user_id, show_id, tier_id, zone_id, seat_indices, qty, amount_cent, status, active_flag, " +
            "request_id, request_fingerprint, sale_config_version, layout_version, seckill_strategy, " +
            "expire_time, deleted, create_time, update_time" +
            ") VALUES (" +
            "#{order.orderNo}, #{order.userId}, #{order.showId}, #{order.tierId}, #{order.zoneId}, #{order.seatIndices}, " +
            "#{order.qty}, #{order.amountCent}, #{order.status}, #{order.activeFlag}, " +
            "#{order.requestId}, #{order.requestFingerprint}, #{order.saleConfigVersion}, #{order.layoutVersion}, #{order.seckillStrategy}, " +
            "#{order.expireTime}, 0, #{order.createTime}, #{order.updateTime}" +
            ")")
    int insertOrder(@Param("tableName") String tableName, @Param("order") OrderDO order);

    @Select("SELECT order_no, user_id, show_id, tier_id, zone_id, seat_indices, qty, amount_cent, status, active_flag, " +
            "request_id, request_fingerprint, sale_config_version, layout_version, seckill_strategy, " +
            "pay_trade_no, close_reason, expire_time, paid_time, deleted, create_time, update_time " +
            "FROM ${tableName} WHERE order_no = #{orderNo} AND deleted = 0 LIMIT 1")
    OrderDO selectByOrderNo(@Param("tableName") String tableName, @Param("orderNo") long orderNo);

    @Select("SELECT order_no, user_id, show_id, tier_id, zone_id, seat_indices, qty, amount_cent, status, active_flag, " +
            "request_id, request_fingerprint, sale_config_version, layout_version, seckill_strategy, " +
            "pay_trade_no, close_reason, expire_time, paid_time, deleted, create_time, update_time " +
            "FROM ${tableName} WHERE user_id = #{userId} AND show_id = #{showId} AND request_id = #{requestId} AND deleted = 0 LIMIT 1")
    OrderDO selectByUserShowRequest(@Param("tableName") String tableName,
                                    @Param("userId") long userId,
                                    @Param("showId") long showId,
                                    @Param("requestId") String requestId);

    @Select("SELECT order_no, user_id, show_id, tier_id, zone_id, seat_indices, qty, amount_cent, status, active_flag, " +
            "request_id, request_fingerprint, sale_config_version, layout_version, seckill_strategy, " +
            "pay_trade_no, close_reason, expire_time, paid_time, deleted, create_time, update_time " +
            "FROM ${tableName} WHERE user_id = #{userId} AND show_id = #{showId} AND active_flag = 1 AND deleted = 0 LIMIT 1")
    OrderDO selectActiveByUserShow(@Param("tableName") String tableName,
                                   @Param("userId") long userId,
                                   @Param("showId") long showId);

    @Select("<script>" +
            "SELECT order_no, user_id, show_id, tier_id, zone_id, seat_indices, qty, amount_cent, status, active_flag, " +
            "request_id, request_fingerprint, sale_config_version, layout_version, seckill_strategy, " +
            "pay_trade_no, close_reason, expire_time, paid_time, deleted, create_time, update_time " +
            "FROM ${tableName} WHERE user_id = #{userId} AND deleted = 0 " +
            "<if test='status != null and status != \"\"'> AND status = #{status} </if>" +
            "<if test='cursorCreateTime != null and cursorOrderNo != null'>" +
            " AND (create_time &lt; #{cursorCreateTime} OR (create_time = #{cursorCreateTime} AND order_no &lt; #{cursorOrderNo})) " +
            "</if>" +
            "ORDER BY create_time DESC, order_no DESC LIMIT #{limit}" +
            "</script>")
    List<OrderDO> selectUserOrdersCursor(@Param("tableName") String tableName,
                                         @Param("userId") long userId,
                                         @Param("status") String status,
                                         @Param("cursorCreateTime") LocalDateTime cursorCreateTime,
                                         @Param("cursorOrderNo") Long cursorOrderNo,
                                         @Param("limit") int limit);

    @Select("SELECT COALESCE(SUM(qty), 0) FROM ${tableName} WHERE show_id = #{showId} AND tier_id = #{tierId} AND active_flag = 1 AND deleted = 0")
    int sumActiveQtyByShowAndTier(@Param("tableName") String tableName,
                                  @Param("showId") long showId,
                                  @Param("tierId") long tierId);

    @Select("SELECT COUNT(1) FROM ${tableName} WHERE show_id = #{showId} AND active_flag = 1 AND deleted = 0")
    int countActiveOrdersByShow(@Param("tableName") String tableName, @Param("showId") long showId);
}
