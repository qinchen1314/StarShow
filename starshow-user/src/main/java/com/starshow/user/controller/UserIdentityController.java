package com.starshow.user.controller;

import com.starshow.common.auth.UserContext;
import com.starshow.common.result.Result;
import com.starshow.user.dto.IdentityVerifyRequest;
import com.starshow.user.service.UserIdentityService;
import com.starshow.user.vo.IdentityVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 链路一（节点 2）：实名认证与脱敏查询接口
 */
@RestController
@RequestMapping("/api/v1/users/identity")
@RequiredArgsConstructor
public class UserIdentityController {

    private final UserIdentityService userIdentityService;

    @PostMapping
    public Result<IdentityVO> verifyIdentity(@Valid @RequestBody IdentityVerifyRequest request) {
        return Result.ok(userIdentityService.verifyIdentity(UserContext.requireUserId(), request));
    }

    @GetMapping
    public Result<IdentityVO> getIdentity() {
        return Result.ok(userIdentityService.getIdentity(UserContext.requireUserId()));
    }
}
