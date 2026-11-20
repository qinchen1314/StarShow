package com.starshow.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 演出场次基础只读元数据（链路一预约校验、链路二缓存复用）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShowBasicDTO implements Serializable {

    private Long showId;
    private String title;
    private LocalDateTime saleStartTime;
    private LocalDateTime saleEndTime;
    private String saleStatus;
    private Integer saleConfigVersion;

    public int effectiveSaleConfigVersion() {
        return (saleConfigVersion == null || saleConfigVersion < 1) ? 1 : saleConfigVersion;
    }
}
