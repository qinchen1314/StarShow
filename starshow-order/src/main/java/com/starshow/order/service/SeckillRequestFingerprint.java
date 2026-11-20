package com.starshow.order.service;

import com.starshow.order.dto.SeckillOrderRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 抢票请求参数规范化 SHA-256 指纹生成器：
 * 纳入 userId、showId、tierId、qty、zoneId、排序后的 seatIndices、layoutVersion、saleConfigVersion、expectedUnitPriceCent，
 * 用于同一 clientRequestId 重放时防参数篡改校验（A0307）。
 */
public final class SeckillRequestFingerprint {

    private SeckillRequestFingerprint() {
    }

    public static String compute(long userId, SeckillOrderRequest req) {
        String zone = req.getZoneId() == null ? "" : req.getZoneId().trim();
        String layoutVer = req.getLayoutVersion() == null ? "" : req.getLayoutVersion().trim();
        String seats = normalizeSeats(req.getSeatIndices());
        String canonical = userId + "|"
                + req.getShowId() + "|"
                + req.getTierId() + "|"
                + req.getQty() + "|"
                + zone + "|"
                + seats + "|"
                + layoutVer + "|"
                + req.getSaleConfigVersion() + "|"
                + req.getExpectedUnitPriceCent();
        return sha256Hex(canonical);
    }

    public static String normalizeSeats(List<Integer> seatIndices) {
        if (seatIndices == null || seatIndices.isEmpty()) {
            return "";
        }
        List<Integer> sorted = new ArrayList<>(seatIndices);
        Collections.sort(sorted);
        return sorted.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
