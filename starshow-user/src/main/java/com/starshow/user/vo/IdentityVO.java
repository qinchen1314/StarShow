package com.starshow.user.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdentityVO {

    private boolean verified;
    private String idCardMasked;
    private LocalDateTime verifiedTime;
}
