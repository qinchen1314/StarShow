package com.starshow.user.controller;

import com.starshow.common.auth.UserContext;
import com.starshow.common.result.Result;
import com.starshow.user.dto.LoginRequest;
import com.starshow.user.dto.RegisterRequest;
import com.starshow.user.service.AuthService;
import com.starshow.user.vo.LoginVO;
import com.starshow.user.vo.RegisterVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 链路一（节点 1）：注册、登录、登出接口（纯路由与参数校验，零业务逻辑）
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public Result<RegisterVO> register(@Valid @RequestBody RegisterRequest request) {
        return Result.ok(authService.register(request));
    }

    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginRequest request) {
        return Result.ok(authService.login(request));
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout(UserContext.requireLoginUser());
        return Result.ok();
    }
}
