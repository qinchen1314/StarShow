-- Applies to a Phase 2 schema selected by the caller. Existing nullable request IDs stay unchanged.
DROP PROCEDURE IF EXISTS sp_starshow_upgrade_to_phase3;
DELIMITER $$
CREATE PROCEDURE sp_starshow_upgrade_to_phase3()
BEGIN
    DECLARE shard INT DEFAULT 0;
    DECLARE shard_table VARCHAR(16);
    WHILE shard < 4 DO
        SET shard_table = CONCAT('t_order_', shard);
        IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = shard_table AND column_name = 'request_id') THEN
            SET @phase3_ddl = CONCAT('ALTER TABLE ', shard_table, ' ADD COLUMN request_id CHAR(36) NULL');
            PREPARE phase3_stmt FROM @phase3_ddl;
            EXECUTE phase3_stmt;
            DEALLOCATE PREPARE phase3_stmt;
        END IF;

        IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = shard_table AND column_name = 'request_fingerprint') THEN
            SET @phase3_ddl = CONCAT('ALTER TABLE ', shard_table, ' ADD COLUMN request_fingerprint CHAR(64) NULL');
            PREPARE phase3_stmt FROM @phase3_ddl;
            EXECUTE phase3_stmt;
            DEALLOCATE PREPARE phase3_stmt;
        END IF;

        IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = shard_table AND column_name = 'sale_config_version') THEN
            SET @phase3_ddl = CONCAT('ALTER TABLE ', shard_table, ' ADD COLUMN sale_config_version INT NOT NULL DEFAULT 1');
            PREPARE phase3_stmt FROM @phase3_ddl;
            EXECUTE phase3_stmt;
            DEALLOCATE PREPARE phase3_stmt;
        END IF;

        IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = shard_table AND column_name = 'layout_version') THEN
            SET @phase3_ddl = CONCAT('ALTER TABLE ', shard_table, ' ADD COLUMN layout_version VARCHAR(32) NULL');
            PREPARE phase3_stmt FROM @phase3_ddl;
            EXECUTE phase3_stmt;
            DEALLOCATE PREPARE phase3_stmt;
        END IF;

        IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = shard_table AND column_name = 'seckill_strategy') THEN
            SET @phase3_ddl = CONCAT('ALTER TABLE ', shard_table, ' ADD COLUMN seckill_strategy VARCHAR(16) NOT NULL DEFAULT ''V4''');
            PREPARE phase3_stmt FROM @phase3_ddl;
            EXECUTE phase3_stmt;
            DEALLOCATE PREPARE phase3_stmt;
        END IF;

        IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = shard_table AND index_name = 'uk_user_show_request') THEN
            SET @phase3_ddl = CONCAT('ALTER TABLE ', shard_table, ' ADD UNIQUE KEY uk_user_show_request (user_id, show_id, request_id)');
            PREPARE phase3_stmt FROM @phase3_ddl;
            EXECUTE phase3_stmt;
            DEALLOCATE PREPARE phase3_stmt;
        END IF;

        IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = shard_table AND index_name IN ('idx_user_create_seek', 'idx_user_create') AND seq_in_index = 3) THEN
            SET @phase3_ddl = CONCAT('ALTER TABLE ', shard_table, ' ADD KEY idx_user_create_seek (user_id, create_time, order_no)');
            PREPARE phase3_stmt FROM @phase3_ddl;
            EXECUTE phase3_stmt;
            DEALLOCATE PREPARE phase3_stmt;
        END IF;

        SET shard = shard + 1;
    END WHILE;
END$$
DELIMITER ;

CALL sp_starshow_upgrade_to_phase3();
DROP PROCEDURE sp_starshow_upgrade_to_phase3;

CREATE TABLE IF NOT EXISTS `t_order_action` (
    `action_id`       BIGINT        NOT NULL COMMENT '动作ID(Snowflake)',
    `order_no`        BIGINT        NOT NULL COMMENT '关联订单号',
    `user_id`         BIGINT        NOT NULL COMMENT '用户ID（分库演进时随user_id路由）',
    `show_id`         BIGINT        NOT NULL COMMENT '演出场次ID',
    `action_type`     VARCHAR(32)   NOT NULL COMMENT 'CREATE_DECISION/CLOSE_CHECK/RELEASE_STOCK',
    `status`          VARCHAR(24)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPLIED/REJECTED/SENT/DONE/FAILED',
    `payload_json`    VARCHAR(1024) NULL COMMENT '动作上下文JSON',
    `next_retry_at`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次可调度时间',
    `lease_until`     DATETIME      NULL COMMENT '当前执行租约截止时间',
    `attempt`         INT           NOT NULL DEFAULT 0 COMMENT '已尝试次数',
    `last_error_code` VARCHAR(32)   NULL COMMENT '最近错误码',
    `deleted`         TINYINT       NOT NULL DEFAULT 0,
    `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`action_id`),
    UNIQUE KEY `uk_order_action_type` (`order_no`, `action_type`),
    KEY `idx_action_schedule` (`status`, `next_retry_at`, `action_id`),
    KEY `idx_show_type_status` (`show_id`, `action_type`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单异步动作、超时关单与创单裁决恢复表';
