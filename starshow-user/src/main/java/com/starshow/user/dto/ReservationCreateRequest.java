package com.starshow.user.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class ReservationCreateRequest {

    @NotNull(message = "演出场次ID不能为空")
    @Positive(message = "演出场次ID必须为正整数")
    private Long showId;
}
