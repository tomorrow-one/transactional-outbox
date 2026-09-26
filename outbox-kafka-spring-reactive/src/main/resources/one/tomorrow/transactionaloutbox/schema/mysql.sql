-- MySQL 8.4+, InnoDB. Store all times in UTC.
CREATE TABLE IF NOT EXISTS outbox_kafka (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    created DATETIME(6) NOT NULL,
    processed DATETIME(6) NULL,
    topic VARCHAR(128) NOT NULL,
    `key` VARCHAR(128) NULL,
    `value` LONGBLOB NOT NULL,
    headers JSON NULL,
    INDEX idx_outbox_kafka_processed_id (processed, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;

CREATE TABLE IF NOT EXISTS outbox_kafka_lock (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    owner_id VARCHAR(128) NOT NULL,
    valid_until DATETIME(6) NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;
