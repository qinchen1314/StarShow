-- ============================================================================
-- StarShow（星演票务）存量数据库平滑升级脚本：Phase 0 / Phase 1 -> Phase 2
-- 特点：
--   1. 严格保留全部存量用户、实名、预约、通知、演出、票档与座位数据（零 DROP / 零 TRUNCATE）；
--   2. 全程使用 DATABASE() 与 information_schema 幂等判断，可对同一存量库安全重复执行；
--   3. 兼容从 Phase 0（v0.1.0-infra）或 Phase 1 / 1.1（v0.2.0-chain1）直接就地升级至 Phase 2。
-- 用法示例：
--   mysql -uroot -p <target_database> < docker/mysql/migrations/V2__phase2_show_inventory.sql
-- ============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS `sp_starshow_upgrade_to_phase2`;
DELIMITER $$
CREATE PROCEDURE `sp_starshow_upgrade_to_phase2`()
BEGIN
    DECLARE v_schema VARCHAR(64);
    SET v_schema = DATABASE();

    -- ------------------------------------------------------------------------
    -- 1. 兼容 Phase 0 -> Phase 1.1 存量表增量列/表补齐（若已升级则自动跳过）
    -- ------------------------------------------------------------------------
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = v_schema AND table_name = 't_reservation' AND column_name = 'remind_config_version'
    ) THEN
        ALTER TABLE `t_reservation`
            ADD COLUMN `remind_config_version` INT NOT NULL DEFAULT 1 COMMENT '关联的开售配置版本号（改期后按新版本重排）' AFTER `remind_status`;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = v_schema AND table_name = 't_reservation' AND column_name = 'next_retry_time'
    ) THEN
        ALTER TABLE `t_reservation`
            ADD COLUMN `next_retry_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次可调度/入窗时间' AFTER `remind_config_version`;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = v_schema AND table_name = 't_reservation' AND index_name = 'idx_remind_retry'
    ) THEN
        ALTER TABLE `t_reservation`
            ADD KEY `idx_remind_retry` (`remind_status`, `next_retry_time`, `id`);
    END IF;

    CREATE TABLE IF NOT EXISTS `t_user_notification` (
        `id`                  BIGINT       NOT NULL COMMENT '通知ID(Snowflake)',
        `user_id`             BIGINT       NOT NULL COMMENT '用户ID',
        `show_id`             BIGINT       NOT NULL COMMENT '演出场次ID',
        `reservation_id`      BIGINT       NOT NULL COMMENT '关联预约ID',
        `sale_config_version` INT          NOT NULL DEFAULT 1 COMMENT '开售配置版本号',
        `notification_type`   VARCHAR(32)  NOT NULL DEFAULT 'REMIND_BEFORE_SALE' COMMENT '通知类型：REMIND_BEFORE_SALE/SCHEDULE_CHANGED',
        `biz_key`             VARCHAR(96)  NOT NULL COMMENT '幂等业务键',
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

    -- ------------------------------------------------------------------------
    -- 2. Phase 2：t_show 补齐 sale_config_version 与稳定游标索引
    -- ------------------------------------------------------------------------
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = v_schema AND table_name = 't_show' AND column_name = 'sale_config_version'
    ) THEN
        ALTER TABLE `t_show`
            ADD COLUMN `sale_config_version` INT NOT NULL DEFAULT 1 COMMENT '开售配置版本号（主办方改期+1，贯穿提醒与预热）' AFTER `sale_status`;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = v_schema AND table_name = 't_show' AND index_name = 'idx_city_show_time'
    ) THEN
        ALTER TABLE `t_show`
            ADD KEY `idx_city_show_time` (`city`, `deleted`, `show_time`, `id`);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = v_schema AND table_name = 't_show' AND index_name = 'idx_deleted_show_time'
    ) THEN
        ALTER TABLE `t_show`
            ADD KEY `idx_deleted_show_time` (`deleted`, `show_time`, `id`);
    END IF;

    -- ------------------------------------------------------------------------
    -- 3. Phase 2：t_seat 补齐走道连续段 segment_id 并无损回填 10002 演示布局
    -- ------------------------------------------------------------------------
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = v_schema AND table_name = 't_seat' AND column_name = 'segment_id'
    ) THEN
        ALTER TABLE `t_seat`
            ADD COLUMN `segment_id` VARCHAR(24) NOT NULL DEFAULT 'S1' COMMENT '同排未被走道隔开的连续段标识' AFTER `col_no`;
    END IF;

    -- 仅对仍为默认占位 'S1' 的 10002 HALL_A 存量座位回填真实走道分段（1~3=L, 4~7=M, 8~10=R）
    UPDATE `t_seat`
    SET `segment_id` = CASE
        WHEN `col_no` <= 3 THEN CONCAT('R', `row_no`, '-L')
        WHEN `col_no` <= 7 THEN CONCAT('R', `row_no`, '-M')
        ELSE CONCAT('R', `row_no`, '-R')
    END
    WHERE `show_id` = 10002
      AND `zone_id` = 'HALL_A'
      AND `deleted` = 0
      AND (`segment_id` = 'S1' OR `segment_id` = '');

    -- ------------------------------------------------------------------------
    -- 4. Phase 2：新建演出预热与状态推进任务恢复表 t_show_task
    -- ------------------------------------------------------------------------
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
END$$
DELIMITER ;

CALL `sp_starshow_upgrade_to_phase2`();
DROP PROCEDURE IF EXISTS `sp_starshow_upgrade_to_phase2`;
