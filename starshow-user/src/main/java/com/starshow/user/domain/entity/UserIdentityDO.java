package com.starshow.user.domain.entity;

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
 * 实名认证实体（对应表 t_user_identity，严禁落明文姓名与身份证）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_user_identity")
public class UserIdentityDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String realNameCipher;
    private String idCardCipher;
    private String idCardMasked;
    private String idCardHmac;
    private LocalDateTime verifiedTime;

    @TableLogic
    private Integer deleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
