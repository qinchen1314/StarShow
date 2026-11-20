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
 * 演出场次实体（对应表 t_show）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_show")
public class ShowDO {

    @TableId
    private Long id;
    private String title;
    private String artist;
    private String city;
    private String venue;
    private LocalDateTime showTime;
    private LocalDateTime saleStartTime;
    private LocalDateTime saleEndTime;
    private String saleStatus;
    private Integer saleConfigVersion;
    private String seatMode;
    private String posterUrl;
    private String description;

    @TableLogic
    private Integer deleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
