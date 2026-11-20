package com.starshow.user.controller;

import com.starshow.common.auth.UserContext;
import com.starshow.common.result.Result;
import com.starshow.user.service.UserNotificationService;
import com.starshow.user.vo.UserNotificationVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 链路一（Phase 1.1 优化）：用户站内通知查询与已读标记接口
 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class UserNotificationController {

    private final UserNotificationService userNotificationService;

    @GetMapping("/me")
    public Result<List<UserNotificationVO>> listMine(
            @RequestParam(value = "limit", required = false) Integer limit) {
        return Result.ok(userNotificationService.listMyNotifications(UserContext.requireUserId(), limit));
    }

    @PostMapping("/{notificationId}/read")
    public Result<UserNotificationVO> markRead(@PathVariable("notificationId") long notificationId) {
        return Result.ok(userNotificationService.markAsRead(UserContext.requireUserId(), notificationId));
    }
}
