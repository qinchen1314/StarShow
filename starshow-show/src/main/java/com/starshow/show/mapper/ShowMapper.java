package com.starshow.show.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starshow.show.domain.entity.ShowDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface ShowMapper extends BaseMapper<ShowDO> {

    /**
     * 主办方改期开售时间乐观 CAS 更新：
     * - 仅允许在未开售状态（INIT / PREHEATING / PREHEATED）下改期；
     * - 原子推进 sale_config_version + 1；
     * - 若原先正在预热或已预热（PREHEATING / PREHEATED），同步回退为 INIT，强制按新版本重新预热。
     */
    @Update("UPDATE t_show SET sale_start_time = #{saleStartTime}, sale_end_time = #{saleEndTime}, " +
            "sale_config_version = sale_config_version + 1, " +
            "sale_status = CASE WHEN sale_status IN ('PREHEATING', 'PREHEATED') THEN 'INIT' ELSE sale_status END " +
            "WHERE id = #{showId} AND sale_config_version = #{expectedVersion} " +
            "AND sale_status IN ('INIT', 'PREHEATING', 'PREHEATED') AND deleted = 0")
    int casUpdateSchedule(
            @Param("showId") long showId,
            @Param("expectedVersion") int expectedVersion,
            @Param("saleStartTime") LocalDateTime saleStartTime,
            @Param("saleEndTime") LocalDateTime saleEndTime
    );

    /**
     * 预热启动 CAS：INIT / PREHEATING -> PREHEATING（校验 sale_config_version）
     */
    @Update("UPDATE t_show SET sale_status = 'PREHEATING' " +
            "WHERE id = #{showId} AND sale_config_version = #{expectedVersion} " +
            "AND sale_status IN ('INIT', 'PREHEATING') AND deleted = 0")
    int casMarkPreheating(@Param("showId") long showId, @Param("expectedVersion") int expectedVersion);

    /**
     * 预热完成 CAS：PREHEATING -> PREHEATED（校验 sale_config_version）
     */
    @Update("UPDATE t_show SET sale_status = 'PREHEATED' " +
            "WHERE id = #{showId} AND sale_config_version = #{expectedVersion} " +
            "AND sale_status = 'PREHEATING' AND deleted = 0")
    int casMarkPreheated(@Param("showId") long showId, @Param("expectedVersion") int expectedVersion);

    /**
     * 到点开售异步状态推进 CAS：PREHEATED -> ON_SALE（校验 sale_config_version）
     */
    @Update("UPDATE t_show SET sale_status = 'ON_SALE' " +
            "WHERE id = #{showId} AND sale_config_version = #{expectedVersion} " +
            "AND sale_status = 'PREHEATED' AND deleted = 0")
    int casMarkOnSale(@Param("showId") long showId, @Param("expectedVersion") int expectedVersion);

    /**
     * 到点停售状态推进 CAS：任意非 ENDED -> ENDED（单向不可逆）
     */
    @Update("UPDATE t_show SET sale_status = 'ENDED' " +
            "WHERE id = #{showId} AND sale_config_version = #{expectedVersion} " +
            "AND sale_status IN ('INIT', 'PREHEATING', 'PREHEATED', 'ON_SALE', 'SOLD_OUT') AND deleted = 0")
    int casMarkEnded(@Param("showId") long showId, @Param("expectedVersion") int expectedVersion);

    /**
     * 稳定游标分页查询有效演出列表（按 show_time ASC, id ASC 排序）
     */
    @Select("<script>" +
            "SELECT id, title, artist, city, venue, show_time, sale_start_time, sale_end_time, " +
            "sale_status, sale_config_version, seat_mode, poster_url " +
            "FROM t_show WHERE deleted = 0 " +
            "<if test='city != null and city != \"\"'> AND city = #{city} </if>" +
            "<if test='cursorShowTime != null and cursorId != null'>" +
            " AND (show_time &gt; #{cursorShowTime} OR (show_time = #{cursorShowTime} AND id &gt; #{cursorId})) " +
            "</if>" +
            "ORDER BY show_time ASC, id ASC LIMIT #{limit}" +
            "</script>")
    List<ShowDO> selectPageByCursor(
            @Param("city") String city,
            @Param("cursorShowTime") LocalDateTime cursorShowTime,
            @Param("cursorId") Long cursorId,
            @Param("limit") int limit
    );

    /**
     * 查询所有未删除有效场次 ID（供目录布隆加载与同步）
     */
    @Select("SELECT id FROM t_show WHERE deleted = 0 ORDER BY id ASC")
    List<Long> selectAllActiveIds();

    /**
     * 扫描已预热且已到开售时间、待推进为 ON_SALE 的场次
     */
    @Select("SELECT * FROM t_show WHERE deleted = 0 AND sale_status = 'PREHEATED' " +
            "AND sale_start_time <= #{now} AND sale_end_time > #{now} " +
            "ORDER BY sale_start_time ASC, id ASC LIMIT #{limit}")
    List<ShowDO> selectDueForOnSale(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * 扫描已到停售时间、待推进为 ENDED 的场次
     */
    @Select("SELECT * FROM t_show WHERE deleted = 0 " +
            "AND sale_status IN ('PREHEATED', 'ON_SALE', 'SOLD_OUT') " +
            "AND sale_end_time <= #{now} " +
            "ORDER BY sale_end_time ASC, id ASC LIMIT #{limit}")
    List<ShowDO> selectDueForEnded(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * 扫描已处于 ON_SALE 且仍在售卖窗口内的场次（用于兜底补齐 Redis stage=OPEN）
     */
    @Select("SELECT * FROM t_show WHERE deleted = 0 AND sale_status = 'ON_SALE' " +
            "AND sale_start_time <= #{now} AND sale_end_time > #{now} " +
            "ORDER BY id ASC LIMIT #{limit}")
    List<ShowDO> selectActiveOnSaleShows(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
