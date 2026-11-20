package com.starshow.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class IdentityVerifyRequest {

    @NotBlank(message = "真实姓名不能为空")
    @Pattern(regexp = "^[\\u4e00-\\u9fa5a-zA-Z·\\s]{2,32}$", message = "真实姓名格式不正确")
    private String realName;

    @NotBlank(message = "身份证号不能为空")
    private String idCard;
}
