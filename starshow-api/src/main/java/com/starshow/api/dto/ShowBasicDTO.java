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
    private String seatMode;
    /**
     * 权威购票入口状态：PREPARING / OPEN / SOLD_OUT / STOCK_UNAVAILABLE / ENDED
     */
    private String entryState;

    public int effectiveSaleConfigVersion() {
        return (saleConfigVersion == null || saleConfigVersion < 1) ? 1 : saleConfigVersion;
    }
}
