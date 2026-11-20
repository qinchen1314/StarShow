package com.starshow.user.controller;

import com.starshow.common.auth.UserContext;
import com.starshow.common.result.Result;
import com.starshow.user.dto.ReservationCreateRequest;
import com.starshow.user.service.ReservationService;
import com.starshow.user.vo.MyReservationVO;
import com.starshow.user.vo.ReservationCountVO;
import com.starshow.user.vo.ReservationVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 链路一（节点 3 & 4）：场次预约蓄水、我的预约列表与实时预约人数接口
 */
@RestController
@RequestMapping("/api/v1/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    @PostMapping
    public Result<ReservationVO> reserve(@Valid @RequestBody ReservationCreateRequest request) {
        return Result.ok(reservationService.reserve(UserContext.requireUserId(), request.getShowId()));
    }

    @GetMapping("/me")
    public Result<List<MyReservationVO>> listMyReservations(
            @RequestParam(value = "limit", required = false) Integer limit) {
        return Result.ok(reservationService.listMyReservations(UserContext.requireUserId(), limit));
    }

    @GetMapping("/{showId}/me")
    public Result<MyReservationVO> getMyReservation(@PathVariable("showId") long showId) {
        return Result.ok(reservationService.getMyReservation(UserContext.requireUserId(), showId));
    }

    @GetMapping("/{showId}/count")
    public Result<ReservationCountVO> getCount(@PathVariable("showId") long showId) {
        return Result.ok(reservationService.getReservationCount(showId));
    }
}
