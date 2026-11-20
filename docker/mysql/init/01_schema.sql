-- ============================================================================
-- StarShow（星演票务）MySQL 8.0 核心表结构初始化脚本 (01_schema.sql)
-- 规范：
--   1. 所有金额字段统一 BIGINT 存分（cent），严禁 FLOAT / DOUBLE
--   2. 订单表按 user_id 基因分 4 表（t_order_0..3），UK(user_id, show_id, active_flag)
--      有效单 active_flag = 1，关单/退款后改为 order_no，兼顾防重与合法重抢
--   3. t_user_identity 使用 AES-GCM 密文 + HMAC-SHA256(pepper) 唯一哈希防彩虹表
--   4. t_mq_consume_log 携带 user_id，单库同事务；分库时与 t_order 配置为 Binding Table
-- ============================================================================

CREATE DATABASE IF NOT EXISTS `starshow` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE `starshow`;

-- ----------------------------------------------------------------------------
-- 1. 用户与实名防黄牛域 (starshow-user)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_user` (
    `id`            BIGINT       NOT NULL COMMENT '用户ID',
    `phone`         VARCHAR(20)  NOT NULL COMMENT '手机号',
    `password_hash` VARCHAR(128) NOT NULL COMMENT 'BCrypt 密码哈希',
    `nickname`      VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '昵称',
    `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1正常 0冻结',
    `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户基础表';

CREATE TABLE IF NOT EXISTS `t_user_identity` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`          BIGINT       NOT NULL COMMENT '用户ID',
    `real_name_cipher` VARCHAR(256) NOT NULL COMMENT '真实姓名密文(AES-GCM)',
    `id_card_cipher`   VARCHAR(256) NOT NULL COMMENT '身份证号密文(AES-GCM)',
    `id_card_masked`   VARCHAR(32)  NOT NULL COMMENT '脱敏展示(如 3101**********1234)',
    `id_card_hmac`     CHAR(64)     NOT NULL COMMENT 'HMAC-SHA256(idCard, serverPepper) 唯一防黄牛',
    `verified_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '认证时间',
    `deleted`          TINYINT      NOT NULL DEFAULT 0,
    `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_id` (`user_id`),
    UNIQUE KEY `uk_id_card_hmac` (`id_card_hmac`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实名认证表（一证一账号）';

CREATE TABLE IF NOT EXISTS `t_reservation` (
    `id`                    BIGINT   NOT NULL COMMENT '预约ID(Snowflake)',
    `user_id`               BIGINT   NOT NULL COMMENT '用户ID',
    `show_id`               BIGINT   NOT NULL COMMENT '演出场次ID',
    `remind_status`         TINYINT  NOT NULL DEFAULT 0 COMMENT '提醒状态：0待投递/超窗 1已送达(终态) 2失败/超时待重试 3已排期 4已开售/结束无需提醒',
    `remind_config_version` INT      NOT NULL DEFAULT 1 COMMENT '关联的开售配置版本号（改期后按新版本重排）',
    `next_retry_time`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次可调度/入窗时间（防超窗占满补偿批次）',
    `deleted`               TINYINT  NOT NULL DEFAULT 0,
    `create_time`           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_show` (`user_id`, `show_id`),
    KEY `idx_show_id` (`show_id`),
    KEY `idx_remind_retry` (`remind_status`, `next_retry_time`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='开票预约蓄水表';

CREATE TABLE IF NOT EXISTS `t_user_notification` (
    `id`                  BIGINT       NOT NULL COMMENT '通知ID(Snowflake)',
    `user_id`             BIGINT       NOT NULL COMMENT '用户ID',
    `show_id`             BIGINT       NOT NULL COMMENT '演出场次ID',
    `reservation_id`      BIGINT       NOT NULL COMMENT '关联预约ID',
    `sale_config_version` INT          NOT NULL DEFAULT 1 COMMENT '开售配置版本号',
    `notification_type`   VARCHAR(32)  NOT NULL DEFAULT 'REMIND_BEFORE_SALE' COMMENT '通知类型：REMIND_BEFORE_SALE(开票提醒)/SCHEDULE_CHANGED(改期变更)',
    `biz_key`             VARCHAR(96)  NOT NULL COMMENT '幂等业务键(rsv:<id>:v<ver> / resched:<id>:v<ver>)',
    `title`               VARCHAR(128) NOT NULL COMMENT '站内通知标题',
    `content`             VARCHAR(512) NOT NULL COMMENT '站内通知正文',
    `action_url`          VARCHAR(128) NOT NULL DEFAULT '' COMMENT '购票入口跳转路径',
    `read_flag`           TINYINT      NOT NULL DEFAULT 0 COMMENT '0未读 1已读',
    `read_time`           DATETIME     NULL COMMENT '用户打开/已读时间',
    `deleted`             TINYINT      NOT NULL DEFAULT 0,
    `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_biz_key` (`biz_key`),
    KEY `idx_user_create` (`user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户站内通知表（开票提醒与改期变更落账）';

-- ----------------------------------------------------------------------------
-- 2. 演出、票档与座位域 (starshow-show)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_show` (
    `id`                  BIGINT        NOT NULL COMMENT '演出场次ID',
    `title`               VARCHAR(128)  NOT NULL COMMENT '演出标题',
    `artist`              VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '艺人/主办方',
    `city`                VARCHAR(32)   NOT NULL COMMENT '城市',
    `venue`               VARCHAR(128)  NOT NULL COMMENT '场馆名称',
    `show_time`           DATETIME      NOT NULL COMMENT '演出开演时间',
    `sale_start_time`     DATETIME      NOT NULL COMMENT '开票时间',
    `sale_end_time`       DATETIME      NOT NULL COMMENT '停售时间',
    `sale_status`         VARCHAR(20)   NOT NULL DEFAULT 'INIT' COMMENT 'INIT/PREHEATING/PREHEATED/ON_SALE/SOLD_OUT/ENDED',
    `sale_config_version` INT           NOT NULL DEFAULT 1 COMMENT '开售配置版本号（主办方改期+1，贯穿提醒与预热）',
    `seat_mode`           VARCHAR(20)   NOT NULL DEFAULT 'AUTO_ALLOC' COMMENT 'AUTO_ALLOC(自动配座)/MANUAL_SEAT(自主选座)',
    `poster_url`          VARCHAR(256)  NOT NULL DEFAULT '' COMMENT '海报地址',
    `description`         TEXT          NULL COMMENT '演出详情',
    `deleted`             TINYINT       NOT NULL DEFAULT 0,
    `create_time`         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_sale_start` (`sale_start_time`, `sale_status`),
    KEY `idx_city_show_time` (`city`, `deleted`, `show_time`, `id`),
    KEY `idx_deleted_show_time` (`deleted`, `show_time`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='演出场次表';

CREATE TABLE IF NOT EXISTS `t_ticket_tier` (
    `id`              BIGINT      NOT NULL COMMENT '票档ID',
    `show_id`         BIGINT      NOT NULL COMMENT '演出场次ID',
    `tier_name`       VARCHAR(64) NOT NULL COMMENT '票档名称（如 内场VIP / 看台A）',
    `zone_id`         VARCHAR(32) NOT NULL DEFAULT 'Z1' COMMENT '所属座位区域ID',
    `price_cent`      BIGINT      NOT NULL COMMENT '票价（分）',
    `total_stock`     INT         NOT NULL COMMENT '总库存',
    `available_stock` INT         NOT NULL COMMENT 'DB物理剩余库存（防超卖底线）',
    `version`         INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `deleted`         TINYINT     NOT NULL DEFAULT 0,
    `create_time`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_show_id` (`show_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='票档与DB物理库存表';

CREATE TABLE IF NOT EXISTS `t_seat` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `show_id`     BIGINT      NOT NULL COMMENT '演出场次ID',
    `tier_id`     BIGINT      NOT NULL COMMENT '票档ID',
    `zone_id`     VARCHAR(32) NOT NULL COMMENT '区域ID（对应Redis Bitmap分片）',
    `seat_index`  INT         NOT NULL COMMENT '区域内Bitmap偏移量(0-based)',
    `row_no`      INT         NOT NULL COMMENT '排号',
    `col_no`      INT         NOT NULL COMMENT '座号',
    `segment_id`  VARCHAR(24) NOT NULL DEFAULT 'S1' COMMENT '同排未被走道隔开的连续段标识',
    `status`      TINYINT     NOT NULL DEFAULT 0 COMMENT '0可选 1已售/锁定',
    `deleted`     TINYINT     NOT NULL DEFAULT 0,
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_show_zone_index` (`show_id`, `zone_id`, `seat_index`),
    KEY `idx_show_tier` (`show_id`, `tier_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分区座位物理映射表';

CREATE TABLE IF NOT EXISTS `t_show_task` (
    `task_id`         BIGINT       NOT NULL COMMENT '任务ID(Snowflake)',
    `show_id`         BIGINT       NOT NULL COMMENT '演出场次ID',
    `task_type`       VARCHAR(32)  NOT NULL COMMENT '任务类型：PREHEAT/RESCHEDULE_SYNC/OPEN_SALE',
    `version`         INT          NOT NULL COMMENT '关联的sale_config_version',
    `execution_id`    VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '当前执行租约Fencing Token',
    `status`          VARCHAR(24)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED',
    `failed_stage`    VARCHAR(64)  NULL COMMENT '失败阶段标识',
    `retryable`       TINYINT      NOT NULL DEFAULT 1 COMMENT '1可重试 0不可重试',
    `attempt`         INT          NOT NULL DEFAULT 0 COMMENT '已尝试次数',
    `next_retry_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次可重试时间',
    `lease_until`     DATETIME     NULL COMMENT '当前执行租约截止时间',
    `last_error_code` VARCHAR(32)  NULL COMMENT '最近错误码',
    `last_error_msg`  VARCHAR(256) NULL COMMENT '最近错误摘要',
    `deleted`         TINYINT      NOT NULL DEFAULT 0,
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`task_id`),
    UNIQUE KEY `uk_show_task_version` (`show_id`, `task_type`, `version`),
    KEY `idx_task_schedule` (`status`, `next_retry_at`, `task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='演出预热与状态推进任务恢复表';

-- ----------------------------------------------------------------------------
-- 3. 订单与履约域 (starshow-order) - 按 user_id 基因分 4 表
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_order_0` (
    `order_no`      BIGINT       NOT NULL COMMENT '基因法Snowflake订单号（尾4bit=user_id%16）',
    `user_id`       BIGINT       NOT NULL COMMENT '用户ID（分片键）',
    `show_id`       BIGINT       NOT NULL COMMENT '演出场次ID',
    `tier_id`       BIGINT       NOT NULL COMMENT '票档ID',
    `zone_id`       VARCHAR(32)  NULL COMMENT '选座区域ID（自动配座可空）',
    `seat_indices`  VARCHAR(128) NULL COMMENT '选座Bitmap偏移逗号分隔（如 12,13）',
    `qty`           INT          NOT NULL DEFAULT 1 COMMENT '购买张数',
    `amount_cent`   BIGINT       NOT NULL COMMENT '实付金额（分）',
    `status`        VARCHAR(24)  NOT NULL COMMENT 'WAIT_PAY/PAID/TICKETED/CLOSED/REFUNDING/REFUNDED',
    `active_flag`   BIGINT       NOT NULL DEFAULT 1 COMMENT '有效单=1；关单/退款后置为order_no以允许合法重抢',
    `pay_trade_no`  VARCHAR(64)  NULL COMMENT '支付渠道交易号',
    `close_reason`  VARCHAR(64)  NULL COMMENT '关单原因',
    `expire_time`   DATETIME     NOT NULL COMMENT '订单超时时间(创建+15min)',
    `paid_time`     DATETIME     NULL COMMENT '支付完成时间',
    `deleted`       TINYINT      NOT NULL DEFAULT 0,
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`order_no`),
    UNIQUE KEY `uk_user_show_active` (`user_id`, `show_id`, `active_flag`),
    KEY `idx_status_expire` (`status`, `expire_time`),
    KEY `idx_user_create` (`user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单分表 0';

CREATE TABLE IF NOT EXISTS `t_order_1` LIKE `t_order_0`;
ALTER TABLE `t_order_1` COMMENT='订单分表 1';
CREATE TABLE IF NOT EXISTS `t_order_2` LIKE `t_order_0`;
ALTER TABLE `t_order_2` COMMENT='订单分表 2';
CREATE TABLE IF NOT EXISTS `t_order_3` LIKE `t_order_0`;
ALTER TABLE `t_order_3` COMMENT='订单分表 3';

CREATE TABLE IF NOT EXISTS `t_ticket_0` (
    `ticket_no`     BIGINT       NOT NULL COMMENT '电子票号',
    `order_no`      BIGINT       NOT NULL COMMENT '关联订单号',
    `user_id`       BIGINT       NOT NULL COMMENT '用户ID（分片键）',
    `show_id`       BIGINT       NOT NULL COMMENT '演出场次ID',
    `tier_id`       BIGINT       NOT NULL COMMENT '票档ID',
    `seat_info`     VARCHAR(64)  NOT NULL DEFAULT '自动配座' COMMENT '座位展示信息',
    `qr_code_token` VARCHAR(128) NOT NULL COMMENT 'HMAC防伪核销码',
    `status`        VARCHAR(20)  NOT NULL DEFAULT 'ISSUED' COMMENT 'ISSUED/USED/LOCKED/REFUNDED',
    `deleted`       TINYINT      NOT NULL DEFAULT 0,
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`ticket_no`),
    KEY `idx_order_no` (`order_no`),
    KEY `idx_user_show` (`user_id`, `show_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='电子票分表 0';

CREATE TABLE IF NOT EXISTS `t_ticket_1` LIKE `t_ticket_0`;
ALTER TABLE `t_ticket_1` COMMENT='电子票分表 1';
CREATE TABLE IF NOT EXISTS `t_ticket_2` LIKE `t_ticket_0`;
ALTER TABLE `t_ticket_2` COMMENT='电子票分表 2';
CREATE TABLE IF NOT EXISTS `t_ticket_3` LIKE `t_ticket_0`;
ALTER TABLE `t_ticket_3` COMMENT='电子票分表 3';

CREATE TABLE IF NOT EXISTS `t_mq_consume_log` (
    `msg_key`        VARCHAR(96) NOT NULL COMMENT '消息业务幂等键',
    `topic`          VARCHAR(64) NOT NULL COMMENT 'RocketMQ Topic',
    `user_id`        BIGINT      NOT NULL DEFAULT 0 COMMENT '关联用户ID（支持未来Binding Table分库同事务）',
    `biz_no`         VARCHAR(64) NOT NULL COMMENT '业务单号（如orderNo）',
    `consume_status` TINYINT     NOT NULL DEFAULT 1 COMMENT '1消费成功',
    `create_time`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`msg_key`),
    KEY `idx_biz_no` (`biz_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MQ消费幂等表（与业务落单同事务）';

-- ----------------------------------------------------------------------------
-- 4. 支付、退款 Saga 与四方对账域 (starshow-payment)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_payment_order` (
    `pay_no`           BIGINT      NOT NULL COMMENT '支付单号',
    `order_no`         BIGINT      NOT NULL COMMENT '关联订单号',
    `user_id`          BIGINT      NOT NULL COMMENT '用户ID',
    `channel`          VARCHAR(24) NOT NULL DEFAULT 'MOCK_PAY' COMMENT '支付渠道',
    `channel_trade_no` VARCHAR(64) NULL COMMENT '渠道流水号（防重复回调唯一约束）',
    `amount_cent`      BIGINT      NOT NULL COMMENT '支付金额（分）',
    `status`           VARCHAR(24) NOT NULL DEFAULT 'INIT' COMMENT 'INIT/SUCCESS/CLOSED/ABNORMAL_PAID',
    `expire_time`      DATETIME    NOT NULL COMMENT '支付通道过期时间(默认14m30s错峰)',
    `paid_time`        DATETIME    NULL COMMENT '支付成功时间',
    `deleted`          TINYINT     NOT NULL DEFAULT 0,
    `create_time`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`pay_no`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    UNIQUE KEY `uk_channel_trade_no` (`channel_trade_no`),
    KEY `idx_paid_time` (`paid_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付流水表';

CREATE TABLE IF NOT EXISTS `t_refund_order` (
    `refund_no`   BIGINT       NOT NULL COMMENT '退款单号',
    `order_no`    BIGINT       NOT NULL COMMENT '关联订单号',
    `user_id`     BIGINT       NOT NULL COMMENT '用户ID',
    `amount_cent` BIGINT       NOT NULL COMMENT '退款金额（分）',
    `reason`      VARCHAR(128) NOT NULL DEFAULT '' COMMENT '退款原因（用户申请/关单滞后支付自动退）',
    `saga_step`   VARCHAR(32)  NOT NULL DEFAULT 'INIT' COMMENT 'INIT/LOCK_TICKET/REFUND_PAY/ROLLBACK_STOCK/DONE',
    `status`      VARCHAR(24)  NOT NULL DEFAULT 'PROCESSING' COMMENT 'PROCESSING/SUCCESS/FAILED',
    `retry_count` INT          NOT NULL DEFAULT 0 COMMENT 'Saga重试次数',
    `deleted`     TINYINT      NOT NULL DEFAULT 0,
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`refund_no`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_status_step` (`status`, `saga_step`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退款Saga状态机表';

CREATE TABLE IF NOT EXISTS `t_reconciliation_diff` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `bill_date`     DATE         NOT NULL COMMENT '对账日期',
    `order_no`      BIGINT       NOT NULL COMMENT '差异订单号',
    `diff_type`     VARCHAR(32)  NOT NULL COMMENT 'SHORT(短款)/LONG(长款)/STATE_MISMATCH/TICKET_MISSING/STOCK_DRIFT',
    `handle_status` VARCHAR(20)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/AUTO_FIXED/MANUAL_REVIEW/RESOLVED',
    `detail_json`   TEXT         NOT NULL COMMENT '四方比对快照JSON',
    `deleted`       TINYINT      NOT NULL DEFAULT 0,
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_bill_date_status` (`bill_date`, `handle_status`),
    KEY `idx_order_no` (`order_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每日四方对账差异审计表';
