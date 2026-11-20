package com.starshow.show.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 票档与 DB 物理库存实体（对应表 t_ticket_tier）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_ticket_tier")
public class TicketTierDO {

    @TableId
    private Long id;
    private Long showId;
    private String tierName;
    private String zoneId;
    private Long priceCent;
    private Integer totalStock;
    private Integer availableStock;
    private Integer version;

    @TableLogic
    private Integer deleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
