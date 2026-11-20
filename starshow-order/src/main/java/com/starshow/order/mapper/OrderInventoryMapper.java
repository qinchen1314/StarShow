package com.starshow.order.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 订单落库事务内的 DB 物理库存与 V1~V3 座位条件更新 Mapper（防超卖物理底线）
 */
public interface OrderInventoryMapper {

    /**
     * 读取票档当前乐观锁版本号与剩余库存（V1 乐观锁路径）
     */
    @Select("SELECT id, show_id AS showId, available_stock AS availableStock, version, price_cent AS priceCent, zone_id AS zoneId " +
            "FROM t_ticket_tier WHERE id = #{tierId} AND show_id = #{showId} AND deleted = 0 LIMIT 1")
    TierStockRow selectTierStock(@Param("showId") long showId, @Param("tierId") long tierId);

    /**
     * V1 乐观锁条件扣减：校验 version 且 available_stock >= qty
     */
    @Update("UPDATE t_ticket_tier SET available_stock = available_stock - #{qty}, version = version + 1 " +
            "WHERE id = #{tierId} AND show_id = #{showId} AND version = #{version} AND available_stock >= #{qty} AND deleted = 0")
    int deductStockOptimistic(@Param("showId") long showId,
                              @Param("tierId") long tierId,
                              @Param("qty") int qty,
                              @Param("version") int version);

    /**
     * V2 / V3 / V4 物理防超卖底线扣减：WHERE available_stock >= qty
     */
    @Update("UPDATE t_ticket_tier SET available_stock = available_stock - #{qty}, version = version + 1 " +
            "WHERE id = #{tierId} AND show_id = #{showId} AND available_stock >= #{qty} AND deleted = 0")
    int deductStockAtomic(@Param("showId") long showId,
                          @Param("tierId") long tierId,
                          @Param("qty") int qty);

    /**
     * V1~V3 自主选座：MySQL 事务内条件占座（仅当全部目标座位 status=0 时成功）
     */
    @Update("<script>" +
            "UPDATE t_seat SET status = 1 " +
            "WHERE show_id = #{showId} AND tier_id = #{tierId} AND zone_id = #{zoneId} AND status = 0 AND deleted = 0 " +
            "AND seat_index IN " +
            "<foreach collection='seatIndices' item='idx' open='(' separator=',' close=')'>#{idx}</foreach>" +
            "</script>")
    int occupySeatsInDb(@Param("showId") long showId,
                        @Param("tierId") long tierId,
                        @Param("zoneId") String zoneId,
                        @Param("seatIndices") List<Integer> seatIndices);

    /**
     * 查询当前已被占用的目标座位偏移（供 V1~V3 冲突反馈具体 conflictSeatIndices）
     */
    @Select("<script>" +
            "SELECT seat_index FROM t_seat " +
            "WHERE show_id = #{showId} AND zone_id = #{zoneId} AND status != 0 AND deleted = 0 " +
            "AND seat_index IN " +
            "<foreach collection='seatIndices' item='idx' open='(' separator=',' close=')'>#{idx}</foreach>" +
            "ORDER BY seat_index ASC" +
            "</script>")
    List<Integer> selectOccupiedSeatIndices(@Param("showId") long showId,
                                            @Param("zoneId") String zoneId,
                                            @Param("seatIndices") List<Integer> seatIndices);

    /**
     * 扫描处于售卖期或待恢复期的已知场次 ID（供悬挂巡检按已知场次遍历，严禁 KEYS/SCAN Redis）
     */
    @Select("SELECT id FROM t_show WHERE deleted = 0 AND sale_status IN ('PREHEATED', 'ON_SALE', 'SOLD_OUT') ORDER BY id ASC")
    List<Long> selectActiveShowIdsForReconcile();

    @lombok.Data
    class TierStockRow {
        private Long id;
        private Long showId;
        private Integer availableStock;
        private Integer version;
        private Long priceCent;
        private String zoneId;
    }
}
