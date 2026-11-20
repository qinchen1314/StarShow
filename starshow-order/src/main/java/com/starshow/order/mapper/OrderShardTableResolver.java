package com.starshow.order.mapper;

import com.starshow.common.id.GeneSnowflakeIdGenerator;

import java.util.List;

/**
 * 订单基因分表精准路由器（严格白名单映射 t_order_0..3，零广播扫描，零 SQL 注入风险）
 */
public final class OrderShardTableResolver {

    public static final int SHARD_COUNT = 4;
    private static final List<String> ALL_SHARD_TABLES = List.of("t_order_0", "t_order_1", "t_order_2", "t_order_3");

    private OrderShardTableResolver() {
    }

    public static String tableForUserId(long userId) {
        int shard = GeneSnowflakeIdGenerator.resolveShardByUserId(userId, SHARD_COUNT);
        return toWhitelistedTableName(shard);
    }

    public static String tableForOrderNo(long orderNo) {
        int shard = GeneSnowflakeIdGenerator.resolveShardByOrderNo(orderNo, SHARD_COUNT);
        return toWhitelistedTableName(shard);
    }

    public static boolean geneMatchesUserId(long orderNo, long userId) {
        int expectedGene = (int) Math.floorMod(userId, 16L);
        return GeneSnowflakeIdGenerator.extractGene(orderNo) == expectedGene;
    }

    public static List<String> allShardTables() {
        return ALL_SHARD_TABLES;
    }

    private static String toWhitelistedTableName(int shard) {
        return switch (shard) {
            case 0 -> "t_order_0";
            case 1 -> "t_order_1";
            case 2 -> "t_order_2";
            case 3 -> "t_order_3";
            default -> throw new IllegalArgumentException("Unsupported shard index: " + shard);
        };
    }
}
