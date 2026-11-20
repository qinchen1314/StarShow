-- ============================================================================
-- StarShow（星演票务）本地开发与压测演示种子数据 (02_seed.sql)
-- ============================================================================
SET NAMES utf8mb4;
USE `starshow`;

-- 演示演出场次：1 场自动配座万人压测场 + 1 场自主选座演示场
INSERT IGNORE INTO `t_show`
    (`id`, `title`, `artist`, `city`, `venue`, `show_time`, `sale_start_time`, `sale_end_time`, `sale_status`, `seat_mode`, `poster_url`, `description`)
VALUES
    (10001, '星演嘉年华 2026 上海梅赛德斯巡回演唱会', 'StarShow All-Stars', '上海', '梅赛德斯-奔驰文化中心',
     '2026-12-20 19:30:00', '2026-11-20 10:00:00', '2026-12-20 18:00:00', 'ON_SALE', 'AUTO_ALLOC',
     '/assets/poster-10001.jpg', 'V1->V4 高并发秒杀基准压测演示场次（总票池 10,000 张）'),
    (10002, '星演交响音乐会 · 选座专场', 'StarShow Philharmonic', '北京', '国家大剧院音乐厅',
     '2026-12-28 19:30:00', '2026-11-20 10:00:00', '2026-12-28 18:00:00', 'ON_SALE', 'MANUAL_SEAT',
     '/assets/poster-10002.jpg', 'Redis Bitmap 分区选座 + 原子连座锁座演示场次'),
    (10003, '星演跨年盛典 · 预约蓄水专场', 'StarShow Special', '深圳', '深圳湾体育中心春茧体育馆',
     DATE_ADD(NOW(), INTERVAL 31 DAY), DATE_ADD(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 30 DAY),
     'INIT', 'AUTO_ALLOC', '/assets/poster-10003.jpg', '链路一开票前预约蓄水与 RocketMQ 5.x 延迟提醒演示场次');

-- 票档库存（10001 合计 10,000 张用于压测；10002 共 100 座用于 Bitmap 可视化演示；10003 用于未开票预约演示）
INSERT IGNORE INTO `t_ticket_tier`
    (`id`, `show_id`, `tier_name`, `zone_id`, `price_cent`, `total_stock`, `available_stock`, `version`)
VALUES
    (20001, 10001, '内场 VIP 前排', 'VIP_A', 168000, 2000, 2000, 0),
    (20002, 10001, '看台一等区',   'STAND_B', 98000, 3000, 3000, 0),
    (20003, 10001, '看台二等区',   'STAND_C', 58000, 5000, 5000, 0),
    (20004, 10002, '音乐厅一楼A区', 'HALL_A',  88000,  100,  100, 0),
    (20005, 10003, '跨年盛典内场票', 'NYE_A', 128000, 5000, 5000, 0);

-- 为 10002 场次生成 10排 x 10座 = 100 个物理座位映射（对应 Bitmap offset 0..99）
INSERT IGNORE INTO `t_seat` (`show_id`, `tier_id`, `zone_id`, `seat_index`, `row_no`, `col_no`, `status`)
SELECT
    10002,
    20004,
    'HALL_A',
    (r.n * 10 + c.n) AS seat_index,
    (r.n + 1)        AS row_no,
    (c.n + 1)        AS col_no,
    0
FROM
    (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
     UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) r
CROSS JOIN
    (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
     UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) c;
