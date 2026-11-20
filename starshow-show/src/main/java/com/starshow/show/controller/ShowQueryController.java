package com.starshow.show.controller;

import com.starshow.common.result.Result;
import com.starshow.show.service.SeatMapQueryService;
import com.starshow.show.service.ShowQueryService;
import com.starshow.show.service.ShowStockQueryService;
import com.starshow.show.vo.SeatMapVO;
import com.starshow.show.vo.ShowDetailVO;
import com.starshow.show.vo.ShowListVO;
import com.starshow.show.vo.ShowStockVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 链路二公开读接口控制器（网关仅放行 GET /api/v1/shows/**，零写路由）：
 * 1. GET /api/v1/shows                                - 稳定游标分页演出列表
 * 2. GET /api/v1/shows/{showId}                       - 多级缓存演出详情 + 权威开售状态
 * 3. GET /api/v1/shows/{showId}/stock                 - 票档实时余量与三态状态
 * 4. GET /api/v1/shows/{showId}/zones/{zoneId}/seats  - 分区 Bitmap 实时座位图
 */
@RestController
@RequestMapping("/api/v1/shows")
@RequiredArgsConstructor
public class ShowQueryController {

    private final ShowQueryService showQueryService;
    private final ShowStockQueryService showStockQueryService;
    private final SeatMapQueryService seatMapQueryService;

    @GetMapping
    public Result<ShowListVO> listShows(
            @RequestParam(value = "city", required = false) String city,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return Result.ok(showQueryService.listShows(city, cursor, limit));
    }

    @GetMapping("/{showId}")
    public Result<ShowDetailVO> getShowDetail(@PathVariable("showId") long showId) {
        return Result.ok(showQueryService.getShowDetail(showId));
    }

    @GetMapping("/{showId}/stock")
    public Result<ShowStockVO> getShowStock(@PathVariable("showId") long showId) {
        return Result.ok(showStockQueryService.getShowStock(showId));
    }

    @GetMapping("/{showId}/zones/{zoneId}/seats")
    public Result<SeatMapVO> getZoneSeatMap(
            @PathVariable("showId") long showId,
            @PathVariable("zoneId") String zoneId) {
        return Result.ok(seatMapQueryService.getZoneSeatMap(showId, zoneId));
    }
}
