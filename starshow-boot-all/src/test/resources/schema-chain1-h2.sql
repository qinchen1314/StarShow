CREATE TABLE IF NOT EXISTS t_user (
    id            BIGINT       NOT NULL,
    phone         VARCHAR(20)  NOT NULL,
    password_hash VARCHAR(128) NOT NULL,
    nickname      VARCHAR(64)  NOT NULL DEFAULT '',
    status        TINYINT      NOT NULL DEFAULT 1,
    deleted       TINYINT      NOT NULL DEFAULT 0,
    create_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_phone UNIQUE (phone)
);

CREATE TABLE IF NOT EXISTS t_user_identity (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    user_id          BIGINT       NOT NULL,
    real_name_cipher VARCHAR(256) NOT NULL,
    id_card_cipher   VARCHAR(256) NOT NULL,
    id_card_masked   VARCHAR(32)  NOT NULL,
    id_card_hmac     CHAR(64)     NOT NULL,
    verified_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted          TINYINT      NOT NULL DEFAULT 0,
    create_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_id UNIQUE (user_id),
    CONSTRAINT uk_id_card_hmac UNIQUE (id_card_hmac)
);

CREATE TABLE IF NOT EXISTS t_reservation (
    id                    BIGINT    NOT NULL,
    user_id               BIGINT    NOT NULL,
    show_id               BIGINT    NOT NULL,
    remind_status         TINYINT   NOT NULL DEFAULT 0,
    remind_config_version INT       NOT NULL DEFAULT 1,
    next_retry_time       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted               TINYINT   NOT NULL DEFAULT 0,
    create_time           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_show UNIQUE (user_id, show_id)
);

CREATE TABLE IF NOT EXISTS t_user_notification (
    id                  BIGINT       NOT NULL,
    user_id             BIGINT       NOT NULL,
    show_id             BIGINT       NOT NULL,
    reservation_id      BIGINT       NOT NULL,
    sale_config_version INT          NOT NULL DEFAULT 1,
    notification_type   VARCHAR(32)  NOT NULL DEFAULT 'REMIND_BEFORE_SALE',
    biz_key             VARCHAR(96)  NOT NULL,
    title               VARCHAR(128) NOT NULL,
    content             VARCHAR(512) NOT NULL,
    action_url          VARCHAR(128) NOT NULL DEFAULT '',
    read_flag           TINYINT      NOT NULL DEFAULT 0,
    read_time           TIMESTAMP    NULL,
    deleted             TINYINT      NOT NULL DEFAULT 0,
    create_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_biz_key UNIQUE (biz_key)
);

CREATE TABLE IF NOT EXISTS t_show (
    id                  BIGINT        NOT NULL,
    title               VARCHAR(128)  NOT NULL,
    artist              VARCHAR(64)   NOT NULL DEFAULT '',
    city                VARCHAR(32)   NOT NULL,
    venue               VARCHAR(128)  NOT NULL,
    show_time           TIMESTAMP     NOT NULL,
    sale_start_time     TIMESTAMP     NOT NULL,
    sale_end_time       TIMESTAMP     NOT NULL,
    sale_status         VARCHAR(20)   NOT NULL DEFAULT 'INIT',
    sale_config_version INT           NOT NULL DEFAULT 1,
    seat_mode           VARCHAR(20)   NOT NULL DEFAULT 'AUTO_ALLOC',
    poster_url          VARCHAR(256)  NOT NULL DEFAULT '',
    description         CLOB          NULL,
    deleted             TINYINT       NOT NULL DEFAULT 0,
    create_time         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS t_ticket_tier (
    id              BIGINT      NOT NULL,
    show_id         BIGINT      NOT NULL,
    tier_name       VARCHAR(64) NOT NULL,
    zone_id         VARCHAR(32) NOT NULL DEFAULT 'Z1',
    price_cent      BIGINT      NOT NULL,
    total_stock     INT         NOT NULL,
    available_stock INT         NOT NULL,
    version         INT         NOT NULL DEFAULT 0,
    deleted         TINYINT     NOT NULL DEFAULT 0,
    create_time     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

INSERT INTO t_show (id, title, artist, city, venue, show_time, sale_start_time, sale_end_time, sale_status, seat_mode, poster_url, description)
VALUES (10003, '星演跨年盛典 · 预约蓄水专场', 'StarShow Special', '深圳', '深圳湾体育中心',
        TIMESTAMPADD(DAY, 31, CURRENT_TIMESTAMP),
        TIMESTAMPADD(HOUR, 12, CURRENT_TIMESTAMP),
        TIMESTAMPADD(DAY, 30, CURRENT_TIMESTAMP),
        'INIT', 'AUTO_ALLOC', '/assets/poster-10003.jpg', '集成测试预约专场');
