package com.starshow.show.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starshow.show.domain.entity.ShowDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface ShowMapper extends BaseMapper<ShowDO> {

    /**
     * 主办方改期开售时间乐观 CAS 更新：
     * - 仅允许在未开售状态（INIT / PREHEATED）下改期；
     * - 原子推进 sale_config_version + 1；
     * - 若原先已预热（PREHEATED），同步回退为 INIT，强制 Phase 2 按新版本重新预热。
     */
    @Update("UPDATE t_show SET sale_start_time = #{saleStartTime}, sale_end_time = #{saleEndTime}, " +
            "sale_config_version = sale_config_version + 1, " +
            "sale_status = CASE WHEN sale_status = 'PREHEATED' THEN 'INIT' ELSE sale_status END " +
            "WHERE id = #{showId} AND sale_config_version = #{expectedVersion} " +
            "AND sale_status IN ('INIT', 'PREHEATED') AND deleted = 0")
    int casUpdateSchedule(
            @Param("showId") long showId,
            @Param("expectedVersion") int expectedVersion,
            @Param("saleStartTime") LocalDateTime saleStartTime,
            @Param("saleEndTime") LocalDateTime saleEndTime
    );
}
