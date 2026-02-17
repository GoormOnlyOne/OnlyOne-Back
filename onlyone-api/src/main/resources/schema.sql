CREATE TABLE IF NOT EXISTS like_applied (
    req_id   VARCHAR(64) PRIMARY KEY,
    feed_id  BIGINT NOT NULL,
    user_id  BIGINT NOT NULL,
    delta    INT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Feed 비정규화 카운트 컬럼: comment_count 추가 + 초기값 동기화
DROP PROCEDURE IF EXISTS sync_feed_counts;
DELIMITER //
CREATE PROCEDURE sync_feed_counts()
BEGIN
    -- comment_count 컬럼이 없으면 추가
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE() AND table_name = 'feed' AND column_name = 'comment_count') THEN
        ALTER TABLE feed ADD COLUMN comment_count BIGINT NOT NULL DEFAULT 0;
    END IF;

    -- like_count 동기화 (feed_like 테이블 기준)
    UPDATE feed f SET f.like_count = (
        SELECT COUNT(*) FROM feed_like fl WHERE fl.feed_id = f.feed_id
    );

    -- comment_count 동기화 (feed_comment 테이블 기준)
    UPDATE feed f SET f.comment_count = (
        SELECT COUNT(*) FROM feed_comment fc WHERE fc.feed_id = f.feed_id
    );
END //
DELIMITER ;
CALL sync_feed_counts();
DROP PROCEDURE IF EXISTS sync_feed_counts;

-- Payment 성능 최적화 인덱스 (MySQL 8.0: 프로시저로 중복 방지)
DROP PROCEDURE IF EXISTS add_index_if_not_exists;
DELIMITER //
CREATE PROCEDURE add_index_if_not_exists()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'payment' AND index_name = 'idx_payment_status') THEN
        CREATE INDEX idx_payment_status ON payment(status);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'payment' AND index_name = 'idx_payment_orderId_status') THEN
        CREATE INDEX idx_payment_orderId_status ON payment(toss_order_id, status);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'wallet_transaction' AND index_name = 'idx_wallet_tx_wallet_status') THEN
        CREATE INDEX idx_wallet_tx_wallet_status ON wallet_transaction(wallet_id, status);
    END IF;

    -- Schedule 성능 최적화 인덱스
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'schedule' AND index_name = 'idx_schedule_club_time') THEN
        CREATE INDEX idx_schedule_club_time ON schedule(club_id, schedule_time DESC);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'schedule' AND index_name = 'idx_schedule_status') THEN
        CREATE INDEX idx_schedule_status ON schedule(status);
    END IF;

    -- Feed 성능 최적화 인덱스
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'feed_like' AND index_name = 'idx_feed_like_feed_id') THEN
        CREATE INDEX idx_feed_like_feed_id ON feed_like(feed_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'feed_comment' AND index_name = 'idx_feed_comment_feed_id') THEN
        CREATE INDEX idx_feed_comment_feed_id ON feed_comment(feed_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'feed' AND index_name = 'idx_feed_club_deleted') THEN
        CREATE INDEX idx_feed_club_deleted ON feed(club_id, deleted, created_at DESC);
    END IF;
    -- 커버링 인덱스: popular/personal 피드 쿼리의 테이블 랜덤 I/O 제거
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'feed' AND index_name = 'idx_feed_popular_cover') THEN
        CREATE INDEX idx_feed_popular_cover ON feed(deleted, club_id, created_at, like_count, comment_count, parent_feed_id, feed_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'feed_image' AND index_name = 'idx_feed_image_feed_id') THEN
        CREATE INDEX idx_feed_image_feed_id ON feed_image(feed_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'user_club' AND index_name = 'idx_user_club_user_id') THEN
        CREATE INDEX idx_user_club_user_id ON user_club(user_id);
    END IF;
    -- 커버링 인덱스: teammates 조인 체인 (club_id → user_id 조회) 최적화
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'user_club' AND index_name = 'idx_user_club_club_user') THEN
        CREATE INDEX idx_user_club_club_user ON user_club(club_id, user_id);
    END IF;
    -- 기존 단일 컬럼 idx_user_club_club 제거 (idx_user_club_club_user가 대체)
    IF EXISTS (SELECT 1 FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'user_club' AND index_name = 'idx_user_club_club') THEN
        DROP INDEX idx_user_club_club ON user_club;
    END IF;
    -- 중복 인덱스 idx_user_club_user_club 제거 (uk_user_club unique constraint가 동일)
    IF EXISTS (SELECT 1 FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'user_club' AND index_name = 'idx_user_club_user_club') THEN
        DROP INDEX idx_user_club_user_club ON user_club;
    END IF;
    -- idx_user_club_club_id도 idx_user_club_club_user의 prefix로 대체됨 → 제거
    IF EXISTS (SELECT 1 FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'user_club' AND index_name = 'idx_user_club_club_id') THEN
        DROP INDEX idx_user_club_club_id ON user_club;
    END IF;
END //
DELIMITER ;
CALL add_index_if_not_exists();
DROP PROCEDURE IF EXISTS add_index_if_not_exists;