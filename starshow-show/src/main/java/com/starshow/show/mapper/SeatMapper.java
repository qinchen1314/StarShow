package com.starshow.show.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starshow.show.domain.entity.SeatDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface SeatMapper extends BaseMapper<SeatDO> {

    @Select("SELECT * FROM t_seat WHERE show_id = #{showId} AND zone_id = #{zoneId} AND deleted = 0 " +
            "ORDER BY row_no ASC, col_no ASC, seat_index ASC")
    List<SeatDO> selectByShowAndZone(@Param("showId") long showId, @Param("zoneId") String zoneId);

    @Select("SELECT * FROM t_seat WHERE show_id = #{showId} AND deleted = 0 " +
            "ORDER BY zone_id ASC, seat_index ASC")
    List<SeatDO> selectByShowId(@Param("showId") long showId);
}
