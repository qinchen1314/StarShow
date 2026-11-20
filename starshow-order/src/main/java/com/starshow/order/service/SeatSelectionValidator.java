package com.starshow.order.service;

import com.starshow.api.dto.TierSnapshotDTO;
import com.starshow.api.dto.ZoneSeatSpecDTO;
import com.starshow.common.exception.BizException;
import com.starshow.common.result.ErrorCode;
import com.starshow.order.dto.SeckillOrderRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 自主选座与连座走道校验器：
 * 1. AUTO_ALLOC 禁止提交 seatIndices；
 * 2. MANUAL_SEAT 必传 zoneId、layoutVersion 且 seatIndices 互不重复、数量 == qty；
 * 3. 每个 seatIndex 必须在 t_seat 真实存在、归属目标 (showId, tierId, zoneId)、未禁售；
 * 4. 多座选座（qty > 1）必须满足连座规则：同排（rowNo 相同）、同一走道连续段（segmentId 相同）、座号严格相邻（colNo 连续 +1）；
 *    跨走道（如 R1-L 跨到 R1-M）即使 seatIndex 或 colNo 相邻也严厉拒绝。
 */
@Component
public class SeatSelectionValidator {

    public static final String SEAT_MODE_MANUAL = "MANUAL_SEAT";

    /**
     * 校验并返回按 seatIndex 升序规范化后的座位列表（AUTO_ALLOC 返回空列表）
     */
    public List<Integer> validateAndNormalize(SeckillOrderRequest req,
                                              TierSnapshotDTO tier,
                                              ZoneSeatSpecDTO zoneSpec) {
        boolean isManual = SEAT_MODE_MANUAL.equalsIgnoreCase(tier.getSeatMode());
        List<Integer> rawSeats = req.getSeatIndices();

        if (!isManual) {
            if (rawSeats != null && !rawSeats.isEmpty()) {
                throw new BizException(ErrorCode.INVALID_PARAM, "自动配座场次不允许指定座位");
            }
            return List.of();
        }

        // 自主选座必填校验
        if (req.getZoneId() == null || req.getZoneId().isBlank()) {
            throw new BizException(ErrorCode.INVALID_PARAM, "自主选座必须指定分区 zoneId");
        }
        String zoneId = req.getZoneId().trim();
        if (tier.getZoneId() != null && !tier.getZoneId().equals(zoneId)) {
            throw new BizException(ErrorCode.INVALID_PARAM, "所选分区不属于目标票档");
        }
        if (req.getLayoutVersion() == null || req.getLayoutVersion().isBlank()) {
            throw new BizException(ErrorCode.INVALID_PARAM, "自主选座必须传递 layoutVersion");
        }
        if (rawSeats == null || rawSeats.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_PARAM, "自主选座必须指定至少一个座位");
        }
        if (rawSeats.size() != req.getQty()) {
            throw new BizException(ErrorCode.INVALID_PARAM, "购票数量 qty 必须等于所选座位数");
        }

        // 去重与非负校验
        Set<Integer> unique = new HashSet<>();
        List<Integer> sorted = new ArrayList<>(rawSeats.size());
        for (Integer idx : rawSeats) {
            if (idx == null || idx < 0) {
                throw new BizException(ErrorCode.INVALID_PARAM, "座位偏移量非法");
            }
            if (!unique.add(idx)) {
                throw new BizException(ErrorCode.INVALID_PARAM, "所选座位包含重复偏移量");
            }
            sorted.add(idx);
        }
        sorted.sort(Integer::compareTo);

        // 布局版本一致性校验
        String authoritativeLayoutVer = tier.getLayoutVersion();
        if (zoneSpec != null && zoneSpec.getLayoutVersion() != null && !zoneSpec.getLayoutVersion().isBlank()) {
            authoritativeLayoutVer = zoneSpec.getLayoutVersion();
        }
        if (authoritativeLayoutVer != null && !authoritativeLayoutVer.equals(req.getLayoutVersion().trim())) {
            throw new BizException(ErrorCode.SHOW_CONFIG_VERSION_CHANGED,
                    "座位布局版本已变更，请刷新分区座位图后重选");
        }

        if (zoneSpec == null || zoneSpec.getSeats() == null || zoneSpec.getSeats().isEmpty()) {
            throw new BizException(ErrorCode.INVALID_PARAM, "目标分区座位配置不存在");
        }

        Map<Integer, ZoneSeatSpecDTO.SeatItem> seatMap = new HashMap<>(zoneSpec.getSeats().size() * 2);
        for (ZoneSeatSpecDTO.SeatItem item : zoneSpec.getSeats()) {
            if (item != null && item.getSeatIndex() != null) {
                seatMap.put(item.getSeatIndex(), item);
            }
        }

        List<ZoneSeatSpecDTO.SeatItem> selectedItems = new ArrayList<>(sorted.size());
        for (Integer idx : sorted) {
            ZoneSeatSpecDTO.SeatItem seat = seatMap.get(idx);
            if (seat == null) {
                throw new BizException(ErrorCode.INVALID_PARAM, "座位偏移越界或不存在: " + idx);
            }
            if (!Objects.equals(seat.getTierId(), tier.getTierId())) {
                throw new BizException(ErrorCode.INVALID_PARAM, "座位 " + idx + " 不属于所选票档");
            }
            // status == 2 表示物理禁售座位（status == 1 为已售占用，由后续 Bitmap/DB 冲突检测返回 A0204）
            if (seat.getStatus() != null && seat.getStatus() == 2) {
                throw new BizException(ErrorCode.INVALID_PARAM, "座位 " + idx + " 当前禁售");
            }
            selectedItems.add(seat);
        }

        // 多座连座规则校验：必须同排、同走道连续段 segmentId、且座号严格连续
        if (selectedItems.size() > 1) {
            selectedItems.sort(Comparator.comparingInt(ZoneSeatSpecDTO.SeatItem::getRowNo)
                    .thenComparingInt(ZoneSeatSpecDTO.SeatItem::getColNo));
            ZoneSeatSpecDTO.SeatItem first = selectedItems.get(0);
            for (int i = 1; i < selectedItems.size(); i++) {
                ZoneSeatSpecDTO.SeatItem prev = selectedItems.get(i - 1);
                ZoneSeatSpecDTO.SeatItem curr = selectedItems.get(i);
                if (!Objects.equals(first.getRowNo(), curr.getRowNo())) {
                    throw new BizException(ErrorCode.INVALID_PARAM, "多座选座必须位于同一排");
                }
                if (first.getSegmentId() == null || !first.getSegmentId().equals(curr.getSegmentId())) {
                    throw new BizException(ErrorCode.INVALID_PARAM, "所选座位被走道隔开，不满足连座规则");
                }
                if (curr.getColNo() == null || prev.getColNo() == null || curr.getColNo() - prev.getColNo() != 1) {
                    throw new BizException(ErrorCode.INVALID_PARAM, "所选座位号不相邻，不满足连座规则");
                }
            }
        }

        return sorted;
    }
}
