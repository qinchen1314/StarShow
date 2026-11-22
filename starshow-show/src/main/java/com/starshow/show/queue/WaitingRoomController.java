package com.starshow.show.queue;

import com.starshow.common.auth.AuthHeaders;
import com.starshow.common.auth.UserContext;
import com.starshow.common.result.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/shows/{showId}/queue")
@ConditionalOnProperty(prefix = "starshow.waiting-room", name = "enabled", havingValue = "true")
public class WaitingRoomController {
    private final WaitingRoomService service;

    @PostMapping
    public Result<WaitingRoomService.View> join(@PathVariable @Positive long showId,
                                                @RequestBody(required = false) @Valid JoinRequest request) {
        return Result.ok(service.join(showId, UserContext.requireUserId(), request == null ? null : request.saleConfigVersion()));
    }

    @GetMapping("/me")
    public Result<WaitingRoomService.View> read(@PathVariable @Positive long showId,
             @RequestHeader(value = AuthHeaders.X_QUEUE_READ_TOKEN, required = false) String readToken) {
        return Result.ok(service.read(showId, UserContext.getUserId(), readToken));
    }

    public record JoinRequest(@Positive Integer saleConfigVersion) {}
}
