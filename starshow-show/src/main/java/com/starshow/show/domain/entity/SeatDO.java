package com.starshow.show.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 分区座位物理映射实体（对应表 t_seat）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_seat")
public class SeatDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long showId;
    private Long tierId;
    private String zoneId;
    private Integer seatIndex;
    private Integer rowNo;
    private Integer colNo;
    private String segmentId;
    /**
     * 0=可选(初始可售) 1=维修/锁座/初始不可售
     */
    private Integer status;

    @TableLogic
    private Integer deleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
