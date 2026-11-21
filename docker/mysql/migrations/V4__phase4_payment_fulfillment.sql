-- Run on the selected StarShow schema after Phase 3. Existing rows are preserved.
DROP PROCEDURE IF EXISTS sp_starshow_phase4_add;
DELIMITER $$
CREATE PROCEDURE sp_starshow_phase4_add(IN tbl VARCHAR(64), IN field VARCHAR(64), IN is_index BOOLEAN, IN clause_text VARCHAR(255))
BEGIN
    IF (is_index AND NOT EXISTS (SELECT 1 FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = tbl AND index_name = field))
       OR (NOT is_index AND NOT EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = tbl AND column_name = field)) THEN
        SET @starshow_phase4_ddl = CONCAT('ALTER TABLE ', tbl, ' ADD ', clause_text);
        PREPARE phase4_stmt FROM @starshow_phase4_ddl;
        EXECUTE phase4_stmt;
        DEALLOCATE PREPARE phase4_stmt;
    END IF;
END$$
DELIMITER ;

CALL sp_starshow_phase4_add('t_ticket_0', 'ticket_item_no', 0, 'COLUMN ticket_item_no SMALLINT NULL');
CALL sp_starshow_phase4_add('t_ticket_1', 'ticket_item_no', 0, 'COLUMN ticket_item_no SMALLINT NULL');
CALL sp_starshow_phase4_add('t_ticket_2', 'ticket_item_no', 0, 'COLUMN ticket_item_no SMALLINT NULL');
CALL sp_starshow_phase4_add('t_ticket_3', 'ticket_item_no', 0, 'COLUMN ticket_item_no SMALLINT NULL');
CALL sp_starshow_phase4_add('t_order_0', 'idx_order_bill', 1, 'KEY idx_order_bill (update_time, order_no)');
CALL sp_starshow_phase4_add('t_order_1', 'idx_order_bill', 1, 'KEY idx_order_bill (update_time, order_no)');
CALL sp_starshow_phase4_add('t_order_2', 'idx_order_bill', 1, 'KEY idx_order_bill (update_time, order_no)');
CALL sp_starshow_phase4_add('t_order_3', 'idx_order_bill', 1, 'KEY idx_order_bill (update_time, order_no)');
CALL sp_starshow_phase4_add('t_order_action', 'db_released_at', 0, 'COLUMN db_released_at DATETIME NULL');
CALL sp_starshow_phase4_add('t_order_action', 'attempted_at', 0, 'COLUMN attempted_at DATETIME NULL');
CALL sp_starshow_phase4_add('t_payment_order', 'order_expire_time', 0, 'COLUMN order_expire_time DATETIME NULL');
CALL sp_starshow_phase4_add('t_payment_order', 'sync_pending', 0, 'COLUMN sync_pending TINYINT NOT NULL DEFAULT 0');
CALL sp_starshow_phase4_add('t_payment_order', 'next_retry_at', 0, 'COLUMN next_retry_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP');
CALL sp_starshow_phase4_add('t_payment_order', 'idx_payment_sync', 1, 'KEY idx_payment_sync (sync_pending, next_retry_at, pay_no)');
CALL sp_starshow_phase4_add('t_refund_order', 'refund_type', 0, 'COLUMN refund_type VARCHAR(24) NOT NULL DEFAULT ''NORMAL''');
CALL sp_starshow_phase4_add('t_refund_order', 'pay_refund_status', 0, 'COLUMN pay_refund_status VARCHAR(24) NOT NULL DEFAULT ''PENDING''');
CALL sp_starshow_phase4_add('t_refund_order', 'channel_refund_no', 0, 'COLUMN channel_refund_no VARCHAR(64) NULL');
CALL sp_starshow_phase4_add('t_refund_order', 'next_retry_at', 0, 'COLUMN next_retry_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP');
CALL sp_starshow_phase4_add('t_refund_order', 'last_error_code', 0, 'COLUMN last_error_code VARCHAR(32) NULL');
CALL sp_starshow_phase4_add('t_refund_order', 'idx_refund_retry', 1, 'KEY idx_refund_retry (status, next_retry_at, refund_no)');
CALL sp_starshow_phase4_add('t_refund_order', 'idx_refund_bill', 1, 'KEY idx_refund_bill (update_time, order_no)');
CALL sp_starshow_phase4_add('t_reconciliation_diff', 'owner_module', 0, 'COLUMN owner_module VARCHAR(24) NOT NULL DEFAULT ''starshow-payment''');
CALL sp_starshow_phase4_add('t_reconciliation_diff', 'suggested_action', 0, 'COLUMN suggested_action VARCHAR(64) NOT NULL DEFAULT ''VERIFY''');
CALL sp_starshow_phase4_add('t_reconciliation_diff', 'uk_bill_order_diff', 1, 'UNIQUE KEY uk_bill_order_diff (bill_date, order_no, diff_type)');
CALL sp_starshow_phase4_add('t_ticket_0', 'uk_order_item', 1, 'UNIQUE KEY uk_order_item (order_no, ticket_item_no)');
CALL sp_starshow_phase4_add('t_ticket_1', 'uk_order_item', 1, 'UNIQUE KEY uk_order_item (order_no, ticket_item_no)');
CALL sp_starshow_phase4_add('t_ticket_2', 'uk_order_item', 1, 'UNIQUE KEY uk_order_item (order_no, ticket_item_no)');
CALL sp_starshow_phase4_add('t_ticket_3', 'uk_order_item', 1, 'UNIQUE KEY uk_order_item (order_no, ticket_item_no)');
DROP PROCEDURE sp_starshow_phase4_add;

CREATE TABLE IF NOT EXISTS t_mock_channel_txn (
    kind VARCHAR(8) NOT NULL,
    merchant_biz_no BIGINT NOT NULL,
    order_no BIGINT NOT NULL,
    amount_cent BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    channel_trade_no VARCHAR(64) NULL,
    expire_time DATETIME NULL,
    confirmed_at DATETIME NULL,
    delivery_at DATETIME NULL,
    delivery_fault VARCHAR(24) NULL,
    PRIMARY KEY (kind, merchant_biz_no),
    UNIQUE KEY uk_channel_trade (channel_trade_no),
    KEY idx_delivery (delivery_at, merchant_biz_no),
    KEY idx_channel_order (order_no),
    KEY idx_channel_bill (confirmed_at, order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
