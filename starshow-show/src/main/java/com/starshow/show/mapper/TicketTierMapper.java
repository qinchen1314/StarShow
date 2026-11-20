package com.starshow.show.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starshow.show.domain.entity.TicketTierDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface TicketTierMapper extends BaseMapper<TicketTierDO> {

    @Select("SELECT * FROM t_ticket_tier WHERE show_id = #{showId} AND deleted = 0 ORDER BY id ASC")
    List<TicketTierDO> selectByShowId(@Param("showId") long showId);
}
