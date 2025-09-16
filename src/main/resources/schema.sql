CREATE TABLE IF NOT EXISTS like_applied (
    req_id   VARCHAR(64) PRIMARY KEY,
    feed_id  BIGINT NOT NULL,
    user_id  BIGINT NOT NULL,
    delta    INT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);