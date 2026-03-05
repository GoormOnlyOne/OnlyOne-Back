-- =============================================================
-- 전체 도메인 통합 시드 데이터 (MySQL) — 10x 스케일 (로컬용)
-- =============================================================
-- 실행: docker exec -i onlyone-mysql mysql -uroot -proot onlyone < k6-tests/seed/seed-all-domains-10x.sql
--
-- 규모 (10x):
--   user:             10,000명
--   interest:         8개
--   club:             5,000개
--   user_club:        ~42,000
--   feed:             500,000
--   feed_comment:     1,500,000
--   feed_like:        1,000,000
--   feed_image:       1,000,000
--   schedule:         10,000
--   user_schedule:    50,000
--   chat_room:        2,500
--   user_chat_room:   12,500
--   message:          500,000
--   wallet:           10,000
--   settlement:       2,500
--   user_settlement:  25,000
--   notification:     1,000,000
--   fcm_token:        10,000
--   총 SQL 행:        ~5,700,000
-- =============================================================

SET @START_TIME = NOW();

-- 동시 실행 방지
SELECT GET_LOCK('onlyone_seed_lock', 0) INTO @got_lock;
SELECT IF(@got_lock = 1, '시드 락 획득 — 진행', 'ERROR: 시드 이미 실행 중') AS '';
SELECT IF(@got_lock = 1, 'OK', 1/0) INTO @_guard;

SELECT '=== 10x 시드 데이터 생성 시작 ===' AS '';

SET FOREIGN_KEY_CHECKS = 0;
SET UNIQUE_CHECKS = 0;
SET autocommit = 0;
SET SESSION bulk_insert_buffer_size = 256 * 1024 * 1024;

-- ═══════════════════════════════════════════
-- 헬퍼 테이블
-- ═══════════════════════════════════════════
DROP TABLE IF EXISTS _digits;
CREATE TABLE _digits (d INT NOT NULL) ENGINE=MEMORY;
INSERT INTO _digits VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);

DROP TABLE IF EXISTS _seq100k;
CREATE TABLE _seq100k (n INT NOT NULL, PRIMARY KEY(n)) ENGINE=InnoDB;
INSERT INTO _seq100k
SELECT d5.d*10000 + d4.d*1000 + d3.d*100 + d2.d*10 + d1.d
FROM _digits d1, _digits d2, _digits d3, _digits d4, _digits d5;

SELECT CONCAT('  헬퍼: _seq100k = ', COUNT(*), ' rows') AS '' FROM _seq100k;

-- ═══════════════════════════════════════════
-- 1) 유저 10,000명
-- ═══════════════════════════════════════════
SELECT '--- [1/14] 유저 (10,000명) ---' AS '';

INSERT INTO `user` (kakao_id, nickname, birth, status, profile_image, gender, city, district, role, created_at, modified_at)
SELECT
    1000000 + s.n + 1,
    CONCAT('테스트유저', s.n + 1),
    DATE_SUB('2000-01-01', INTERVAL (s.n % 3650) DAY),
    'ACTIVE', NULL,
    IF(s.n % 2 = 0, 'MALE', 'FEMALE'),
    ELT((s.n % 5) + 1, '서울', '부산', '대구', '인천', '광주'),
    ELT((s.n % 10) + 1, '강남구', '서초구', '마포구', '중구', '해운대구', '사하구', '북구', '서구', '남구', '동구'),
    'ROLE_USER',
    NOW() - INTERVAL (10000 - s.n) MINUTE, NOW()
FROM (SELECT n FROM _seq100k WHERE n < 10000) s
ON DUPLICATE KEY UPDATE nickname = VALUES(nickname), modified_at = NOW();
COMMIT;
SELECT CONCAT('  유저: ', COUNT(*)) AS '' FROM `user`;

-- ═══════════════════════════════════════════
-- 2) 관심사
-- ═══════════════════════════════════════════
SELECT '--- [2/14] 관심사 ---' AS '';
INSERT IGNORE INTO interest (interest_id, category, created_at, modified_at)
VALUES (1,'CULTURE',NOW(),NOW()),(2,'EXERCISE',NOW(),NOW()),(3,'TRAVEL',NOW(),NOW()),
       (4,'MUSIC',NOW(),NOW()),(5,'CRAFT',NOW(),NOW()),(6,'SOCIAL',NOW(),NOW()),
       (7,'LANGUAGE',NOW(),NOW()),(8,'FINANCE',NOW(),NOW());
COMMIT;

INSERT IGNORE INTO user_interest (user_id, interest_id, created_at, modified_at)
SELECT user_id, ((user_id % 8) + 1), NOW(), NOW()
FROM `user` WHERE user_id BETWEEN 1 AND 10000;
INSERT IGNORE INTO user_interest (user_id, interest_id, created_at, modified_at)
SELECT user_id, (((user_id + 3) % 8) + 1), NOW(), NOW()
FROM `user` WHERE user_id BETWEEN 1 AND 10000;
COMMIT;

-- ═══════════════════════════════════════════
-- 3) 클럽 5,000개
-- ═══════════════════════════════════════════
SELECT '--- [3/14] 클럽 (5,000개) ---' AS '';

INSERT INTO club (name, user_limit, description, city, district, member_count, interest_id, created_at, modified_at)
SELECT
    CONCAT(ELT((s.n % 10) + 1, '독서모임','축구동호회','등산모임','기타동아리','뜨개질클럽',
         '보드게임','영어회화','주식스터디','영화감상','러닝크루'), ' ', s.n),
    50,
    CONCAT('테스트 클럽 ', s.n, '의 설명입니다.'),
    ELT((s.n % 5) + 1, '서울','서울','부산','대구','인천'),
    ELT((s.n % 5) + 1, '강남구','마포구','해운대구','서구','서구'),
    FLOOR(RAND() * 45) + 5,
    (s.n % 8) + 1,
    NOW() - INTERVAL FLOOR(RAND() * 365) DAY, NOW()
FROM (SELECT n FROM _seq100k WHERE n < 5000) s
ON DUPLICATE KEY UPDATE modified_at = NOW();
COMMIT;
SELECT CONCAT('  클럽: ', COUNT(*)) AS '' FROM club;

-- ═══════════════════════════════════════════
-- 4) 유저-클럽 가입 (유저당 ~4개)
-- ═══════════════════════════════════════════
SELECT '--- [4/14] 클럽 가입 ---' AS '';
SET @min_club = (SELECT MIN(club_id) FROM club);

INSERT IGNORE INTO user_club (user_id, club_id, role, created_at, modified_at)
SELECT u.user_id, @min_club + (u.user_id % 5000), 'MEMBER', NOW(), NOW()
FROM `user` u WHERE u.user_id BETWEEN 1 AND 10000;
COMMIT;

INSERT IGNORE INTO user_club (user_id, club_id, role, created_at, modified_at)
SELECT u.user_id, @min_club + ((u.user_id + 1666) % 5000), 'MEMBER', NOW(), NOW()
FROM `user` u WHERE u.user_id BETWEEN 1 AND 10000;
COMMIT;

INSERT IGNORE INTO user_club (user_id, club_id, role, created_at, modified_at)
SELECT u.user_id, @min_club + ((u.user_id + 3333) % 5000), 'MEMBER', NOW(), NOW()
FROM `user` u WHERE u.user_id BETWEEN 1 AND 10000;
COMMIT;

INSERT IGNORE INTO user_club (user_id, club_id, role, created_at, modified_at)
SELECT u.user_id, @min_club + ((u.user_id * 7) % 5000), 'MEMBER', NOW(), NOW()
FROM `user` u WHERE u.user_id BETWEEN 1 AND 5000;
COMMIT;

INSERT IGNORE INTO user_club (user_id, club_id, role, created_at, modified_at)
SELECT u.user_id, @min_club + ((u.user_id * 13) % 5000), 'MEMBER', NOW(), NOW()
FROM `user` u WHERE u.user_id BETWEEN 1 AND 2000;
COMMIT;

UPDATE user_club uc
JOIN (SELECT MIN(user_club_id) AS first_id FROM user_club GROUP BY club_id) t
ON uc.user_club_id = t.first_id
SET uc.role = 'LEADER';
COMMIT;
SELECT CONCAT('  유저-클럽: ', COUNT(*)) AS '' FROM user_club;

-- ═══════════════════════════════════════════
-- 5) 피드 500,000개
-- ═══════════════════════════════════════════
SELECT '--- [5/14] 피드 (500,000개) ---' AS '';

SET @min_feed = 0;
DELIMITER //
DROP PROCEDURE IF EXISTS seed_feeds_10x //
CREATE PROCEDURE seed_feeds_10x()
BEGIN
    DECLARE batch INT DEFAULT 0;
    WHILE batch < 5 DO
        INSERT INTO feed (content, club_id, user_id, type, parent_feed_id, root_feed_id,
                         like_count, comment_count, deleted, created_at, modified_at)
        SELECT
            CONCAT('테스트 피드 #', batch * 100000 + s.n),
            @min_club + ((batch * 100000 + s.n) % 5000),
            ((batch * 100000 + s.n) % 10000) + 1,
            IF(batch < 4, 'ORIGINAL', 'REFEED'),
            IF(batch < 4, NULL, @min_feed + ((batch * 100000 + s.n) % 400000)),
            IF(batch < 4, NULL, @min_feed + ((batch * 100000 + s.n) % 400000)),
            FLOOR(RAND() * 30), FLOOR(RAND() * 15), FALSE,
            NOW() - INTERVAL (500000 - (batch * 100000 + s.n)) SECOND, NOW()
        FROM _seq100k s;
        COMMIT;
        SELECT CONCAT('    피드: ', (batch + 1) * 100000, ' / 500,000') AS '';
        SET batch = batch + 1;
    END WHILE;
END //
DELIMITER ;
CALL seed_feeds_10x();
DROP PROCEDURE IF EXISTS seed_feeds_10x;

SET @min_feed = (SELECT MIN(feed_id) FROM feed);
UPDATE feed SET
    parent_feed_id = @min_feed + (feed_id % 400000),
    root_feed_id = @min_feed + (feed_id % 400000)
WHERE type = 'REFEED' AND parent_feed_id IS NOT NULL;
COMMIT;
SELECT CONCAT('  피드: ', COUNT(*)) AS '' FROM feed;

-- ═══════════════════════════════════════════
-- 6) 댓글 1,500,000개
-- ═══════════════════════════════════════════
SELECT '--- [6/14] 댓글 (1,500,000개) ---' AS '';

DELIMITER //
DROP PROCEDURE IF EXISTS seed_comments_10x //
CREATE PROCEDURE seed_comments_10x()
BEGIN
    DECLARE batch INT DEFAULT 0;
    WHILE batch < 15 DO
        INSERT INTO feed_comment (content, feed_id, user_id, created_at, modified_at)
        SELECT
            CONCAT('댓글 #', batch * 100000 + s.n, ' - 좋은 글이네요!'),
            @min_feed + ((batch * 100000 + s.n) % 500000),
            ((batch * 100000 + s.n) % 10000) + 1,
            NOW() - INTERVAL (1500000 - (batch * 100000 + s.n)) SECOND, NOW()
        FROM _seq100k s;
        COMMIT;
        IF (batch + 1) % 5 = 0 THEN
            SELECT CONCAT('    댓글: ', (batch + 1) * 100000, ' / 1,500,000') AS '';
        END IF;
        SET batch = batch + 1;
    END WHILE;
END //
DELIMITER ;
CALL seed_comments_10x();
DROP PROCEDURE IF EXISTS seed_comments_10x;
SELECT CONCAT('  댓글: ', COUNT(*)) AS '' FROM feed_comment;

-- ═══════════════════════════════════════════
-- 7) 좋아요 1,000,000개
-- ═══════════════════════════════════════════
SELECT '--- [7/14] 좋아요 (1,000,000개) ---' AS '';

DELIMITER //
DROP PROCEDURE IF EXISTS seed_likes_10x //
CREATE PROCEDURE seed_likes_10x()
BEGIN
    DECLARE batch INT DEFAULT 0;
    WHILE batch < 10 DO
        INSERT IGNORE INTO feed_like (feed_id, user_id, created_at, modified_at)
        SELECT
            @min_feed + ((batch * 100000 + s.n) % 500000),
            ((batch * 100000 + s.n) DIV 500000 * 5000 + (batch * 100000 + s.n) % 5000) % 10000 + 1,
            NOW() - INTERVAL (1000000 - (batch * 100000 + s.n)) SECOND, NOW()
        FROM _seq100k s;
        COMMIT;
        IF (batch + 1) % 5 = 0 THEN
            SELECT CONCAT('    좋아요: ', (batch + 1) * 100000, ' / 1,000,000') AS '';
        END IF;
        SET batch = batch + 1;
    END WHILE;
END //
DELIMITER ;
CALL seed_likes_10x();
DROP PROCEDURE IF EXISTS seed_likes_10x;
SELECT CONCAT('  좋아요: ', COUNT(*)) AS '' FROM feed_like;

-- ═══════════════════════════════════════════
-- 8) 이미지 1,000,000개
-- ═══════════════════════════════════════════
SELECT '--- [8/14] 이미지 (1,000,000개) ---' AS '';

DELIMITER //
DROP PROCEDURE IF EXISTS seed_images_10x //
CREATE PROCEDURE seed_images_10x()
BEGIN
    DECLARE batch INT DEFAULT 0;
    WHILE batch < 10 DO
        INSERT INTO feed_image (feed_image, feed_id, created_at, modified_at)
        SELECT
            CONCAT('https://d1c3fg3ti7m8cn.cloudfront.net/feed/', @min_feed + ((batch * 100000 + s.n) DIV 2), '/img', ((batch * 100000 + s.n) % 2) + 1, '.jpg'),
            @min_feed + ((batch * 100000 + s.n) DIV 2),
            NOW(), NOW()
        FROM _seq100k s;
        COMMIT;
        IF (batch + 1) % 5 = 0 THEN
            SELECT CONCAT('    이미지: ', (batch + 1) * 100000, ' / 1,000,000') AS '';
        END IF;
        SET batch = batch + 1;
    END WHILE;
END //
DELIMITER ;
CALL seed_images_10x();
DROP PROCEDURE IF EXISTS seed_images_10x;
SELECT CONCAT('  이미지: ', COUNT(*)) AS '' FROM feed_image;

-- ═══════════════════════════════════════════
-- 9) 스케줄 10,000개 + 유저스케줄 50,000개
-- ═══════════════════════════════════════════
SELECT '--- [9/14] 스케줄 (10,000개) ---' AS '';

INSERT INTO schedule (schedule_time, name, location, cost, user_limit, status, club_id, created_at, modified_at)
SELECT
    NOW() + INTERVAL (s.n - 5000) HOUR,
    CONCAT('모임일정 ', s.n),
    CONCAT('장소 ', (s.n % 10) + 1),
    (FLOOR(RAND() * 10) + 1) * 1000, 20,
    ELT((s.n % 4) + 1, 'READY', 'ENDED', 'SETTLING', 'CLOSED'),
    @min_club + (s.n % 5000),
    NOW() - INTERVAL (10000 - s.n) MINUTE, NOW()
FROM (SELECT n FROM _seq100k WHERE n < 10000) s;
COMMIT;

SET @min_schedule = (SELECT MIN(schedule_id) FROM schedule);

DELIMITER //
DROP PROCEDURE IF EXISTS seed_user_schedules_10x //
CREATE PROCEDURE seed_user_schedules_10x()
BEGIN
    DECLARE p INT DEFAULT 0;
    WHILE p < 5 DO
        INSERT IGNORE INTO user_schedule (user_id, schedule_id, role, created_at, modified_at)
        SELECT
            ((s.n * 5 + p) % 10000) + 1,
            @min_schedule + s.n,
            IF(p = 0, 'LEADER', 'MEMBER'),
            NOW(), NOW()
        FROM (SELECT n FROM _seq100k WHERE n < 10000) s;
        COMMIT;
        SET p = p + 1;
    END WHILE;
END //
DELIMITER ;
CALL seed_user_schedules_10x();
DROP PROCEDURE IF EXISTS seed_user_schedules_10x;
SELECT CONCAT('  스케줄: ', COUNT(*)) AS '' FROM schedule;
SELECT CONCAT('  유저스케줄: ', COUNT(*)) AS '' FROM user_schedule;

-- ═══════════════════════════════════════════
-- 10) 채팅방 2,500개 + 참여자 12,500 + 메시지 500,000
-- ═══════════════════════════════════════════
SELECT '--- [10/14] 채팅 (2,500방, 500K메시지) ---' AS '';

INSERT INTO chat_room (club_id, schedule_id, type, created_at, modified_at)
SELECT
    @min_club + (s.n % 5000),
    IF(s.n % 3 = 0, @min_schedule + (s.n % 10000), NULL),
    IF(s.n % 3 = 0, 'SCHEDULE', 'CLUB'),
    NOW() - INTERVAL (2500 - s.n) HOUR, NOW()
FROM (SELECT n FROM _seq100k WHERE n < 2500) s;
COMMIT;

SET @min_chatroom = (SELECT MIN(chat_room_id) FROM chat_room);

DELIMITER //
DROP PROCEDURE IF EXISTS seed_user_chatrooms_10x //
CREATE PROCEDURE seed_user_chatrooms_10x()
BEGIN
    DECLARE p INT DEFAULT 0;
    WHILE p < 5 DO
        INSERT IGNORE INTO user_chat_room (chat_room_id, user_id, role, created_at, modified_at)
        SELECT @min_chatroom + s.n, ((s.n * 5 + p) % 10000) + 1,
            IF(p = 0, 'LEADER', 'MEMBER'), NOW(), NOW()
        FROM (SELECT n FROM _seq100k WHERE n < 2500) s;
        COMMIT;
        SET p = p + 1;
    END WHILE;
END //
DELIMITER ;
CALL seed_user_chatrooms_10x();
DROP PROCEDURE IF EXISTS seed_user_chatrooms_10x;

-- 메시지 500,000개
DELIMITER //
DROP PROCEDURE IF EXISTS seed_messages_10x //
CREATE PROCEDURE seed_messages_10x()
BEGIN
    DECLARE batch INT DEFAULT 0;
    WHILE batch < 5 DO
        INSERT INTO message (chat_room_id, user_id, text, sent_at, deleted, created_at, modified_at)
        SELECT
            @min_chatroom + ((batch * 100000 + s.n) % 2500),
            ((batch * 100000 + s.n) % 10000) + 1,
            CONCAT('채팅 메시지 #', batch * 100000 + s.n),
            NOW() - INTERVAL (500000 - (batch * 100000 + s.n)) SECOND, FALSE,
            NOW() - INTERVAL (500000 - (batch * 100000 + s.n)) SECOND, NOW()
        FROM _seq100k s;
        COMMIT;
        SELECT CONCAT('    메시지: ', (batch + 1) * 100000, ' / 500,000') AS '';
        SET batch = batch + 1;
    END WHILE;
END //
DELIMITER ;
CALL seed_messages_10x();
DROP PROCEDURE IF EXISTS seed_messages_10x;

SELECT CONCAT('  채팅방: ', COUNT(*)) AS '' FROM chat_room;
SELECT CONCAT('  메시지: ', COUNT(*)) AS '' FROM message;

-- ═══════════════════════════════════════════
-- 11) 지갑 10,000개
-- ═══════════════════════════════════════════
SELECT '--- [11/14] 지갑 (10,000개) ---' AS '';

-- 일반 유저 (userId 1, 12~10000): 기본 잔액
INSERT INTO wallet (user_id, posted_balance, pending_out, created_at, modified_at)
SELECT u.user_id, 100000, 0, NOW(), NOW()
FROM `user` u WHERE u.user_id BETWEEN 1 AND 10000
  AND u.user_id NOT BETWEEN 2 AND 11
AND NOT EXISTS (SELECT 1 FROM wallet w WHERE w.user_id = u.user_id)
ON DUPLICATE KEY UPDATE posted_balance = 100000, pending_out = 0, modified_at = NOW();

-- 정산 참여자 (userId 2~11): 2,500 정산 x costPerUser 100 = pending_out 250,000
INSERT INTO wallet (user_id, posted_balance, pending_out, created_at, modified_at)
SELECT u.user_id, 250000, 250000, NOW(), NOW()
FROM `user` u WHERE u.user_id BETWEEN 2 AND 11
ON DUPLICATE KEY UPDATE posted_balance = 250000, pending_out = 250000, modified_at = NOW();
COMMIT;
SELECT CONCAT('  지갑: ', COUNT(*)) AS '' FROM wallet;

-- ═══════════════════════════════════════════
-- 12) 정산 2,500건 + 유저정산 25,000건
-- ═══════════════════════════════════════════
SELECT '--- [12/14] 정산 (2,500건) ---' AS '';

DELETE FROM user_settlement WHERE settlement_id IN (
    SELECT settlement_id FROM settlement WHERE schedule_id BETWEEN 5000000 AND 5002499
);
DELETE FROM settlement WHERE schedule_id BETWEEN 5000000 AND 5002499;
DELETE FROM schedule WHERE schedule_id BETWEEN 5000000 AND 5002499;
COMMIT;

INSERT INTO schedule (schedule_id, created_at, modified_at, cost, location, name, status, schedule_time, user_limit, club_id)
SELECT
    5000000 + (u.user_id - 1), NOW(), NOW(), 1000, 'LoadTest Location',
    CONCAT('정산테스트 ', u.user_id), 'ENDED',
    DATE_SUB(NOW(), INTERVAL 1 DAY), 20, @min_club
FROM `user` u WHERE u.user_id BETWEEN 1 AND 2500
ON DUPLICATE KEY UPDATE status = 'ENDED', modified_at = NOW();
COMMIT;

INSERT INTO settlement (created_at, modified_at, completed_time, schedule_id, sum, total_status, user_id)
SELECT NOW(), NOW(), NULL, 5000000 + (u.user_id - 1), 0, 'HOLDING', 1
FROM `user` u WHERE u.user_id BETWEEN 1 AND 2500;
COMMIT;

INSERT INTO user_settlement (created_at, modified_at, completed_time, status, settlement_id, user_id)
SELECT NOW(), NOW(), NULL, 'HOLD_ACTIVE', s.settlement_id, p.user_id
FROM settlement s
CROSS JOIN (SELECT user_id FROM `user` WHERE user_id BETWEEN 2 AND 11) p
WHERE s.schedule_id BETWEEN 5000000 AND 5002499 AND s.total_status = 'HOLDING';
COMMIT;
SELECT CONCAT('  정산: ', COUNT(*)) AS '' FROM settlement WHERE schedule_id BETWEEN 5000000 AND 5002499;

-- ═══════════════════════════════════════════
-- 13) 알림 1,000,000건
-- ═══════════════════════════════════════════
SELECT '--- [13/14] 알림 (1,000,000건) ---' AS '';

DELIMITER //
DROP PROCEDURE IF EXISTS seed_notifications_10x //
CREATE PROCEDURE seed_notifications_10x()
BEGIN
    DECLARE batch INT DEFAULT 0;
    WHILE batch < 10 DO
        INSERT INTO notification (content, is_read, type, user_id, sse_sent, created_at, modified_at)
        SELECT
            CONCAT('알림 #', batch * 100000 + s.n, ' - ', ELT(((batch * 100000 + s.n) % 5) + 1, 'CHAT','COMMENT','LIKE','REFEED','SETTLEMENT')),
            IF(RAND() < 0.3, TRUE, FALSE),
            ELT(((batch * 100000 + s.n) % 5) + 1, 'CHAT','COMMENT','LIKE','REFEED','SETTLEMENT'),
            ((batch * 100000 + s.n) % 10000) + 1,
            IF(RAND() < 0.7, TRUE, FALSE),
            NOW() - INTERVAL (1000000 - (batch * 100000 + s.n)) SECOND, NOW()
        FROM _seq100k s;
        COMMIT;
        IF (batch + 1) % 5 = 0 THEN
            SELECT CONCAT('    알림: ', (batch + 1) * 100000, ' / 1,000,000') AS '';
        END IF;
        SET batch = batch + 1;
    END WHILE;
END //
DELIMITER ;
CALL seed_notifications_10x();
DROP PROCEDURE IF EXISTS seed_notifications_10x;
SELECT CONCAT('  알림: ', COUNT(*)) AS '' FROM notification;

-- ═══════════════════════════════════════════
-- 14) FCM 토큰 10,000개
-- ═══════════════════════════════════════════
SELECT '--- [14/14] FCM 토큰 ---' AS '';

INSERT INTO fcm_token (user_id, token, device_type, created_at, modified_at)
SELECT u.user_id, CONCAT('fcm-token-', u.user_id, '-', UUID()),
    IF(u.user_id % 2 = 0, 'ANDROID', 'IOS'), NOW(), NOW()
FROM `user` u WHERE u.user_id BETWEEN 1 AND 10000
ON DUPLICATE KEY UPDATE token = VALUES(token), modified_at = NOW();
COMMIT;

-- ═══════════════════════════════════════════
-- 카운트 동기화
-- ═══════════════════════════════════════════
SELECT '--- 카운트 동기화 ---' AS '';

DELIMITER //
DROP PROCEDURE IF EXISTS sync_feed_counts_10x //
CREATE PROCEDURE sync_feed_counts_10x()
BEGIN
    DECLARE batch_start BIGINT;
    SET batch_start = @min_feed;
    WHILE batch_start <= @min_feed + 499999 DO
        UPDATE feed f SET
            like_count = (SELECT COUNT(*) FROM feed_like fl WHERE fl.feed_id = f.feed_id),
            comment_count = (SELECT COUNT(*) FROM feed_comment fc WHERE fc.feed_id = f.feed_id)
        WHERE f.feed_id BETWEEN batch_start AND batch_start + 49999;
        COMMIT;
        SET batch_start = batch_start + 50000;
    END WHILE;
END //
DELIMITER ;
CALL sync_feed_counts_10x();
DROP PROCEDURE IF EXISTS sync_feed_counts_10x;

UPDATE club c SET
    member_count = (SELECT COUNT(*) FROM user_club uc WHERE uc.club_id = c.club_id);
COMMIT;

-- ═══════════════════════════════════════════
-- 정리
-- ═══════════════════════════════════════════
DROP TABLE IF EXISTS _seq100k;
DROP TABLE IF EXISTS _digits;
SET FOREIGN_KEY_CHECKS = 1;
SET UNIQUE_CHECKS = 1;
SET autocommit = 1;

-- 최종 결과
SELECT '========================================' AS '';
SELECT '=== 10x 시드 최종 결과 ===' AS '';
SELECT '========================================' AS '';
SELECT CONCAT('user:            ', (SELECT COUNT(*) FROM `user`)) AS r;
SELECT CONCAT('club:            ', (SELECT COUNT(*) FROM club)) AS r;
SELECT CONCAT('user_club:       ', (SELECT COUNT(*) FROM user_club)) AS r;
SELECT CONCAT('feed:            ', (SELECT COUNT(*) FROM feed)) AS r;
SELECT CONCAT('feed_comment:    ', (SELECT COUNT(*) FROM feed_comment)) AS r;
SELECT CONCAT('feed_like:       ', (SELECT COUNT(*) FROM feed_like)) AS r;
SELECT CONCAT('feed_image:      ', (SELECT COUNT(*) FROM feed_image)) AS r;
SELECT CONCAT('schedule:        ', (SELECT COUNT(*) FROM schedule)) AS r;
SELECT CONCAT('user_schedule:   ', (SELECT COUNT(*) FROM user_schedule)) AS r;
SELECT CONCAT('chat_room:       ', (SELECT COUNT(*) FROM chat_room)) AS r;
SELECT CONCAT('user_chat_room:  ', (SELECT COUNT(*) FROM user_chat_room)) AS r;
SELECT CONCAT('message:         ', (SELECT COUNT(*) FROM message)) AS r;
SELECT CONCAT('wallet:          ', (SELECT COUNT(*) FROM wallet)) AS r;
SELECT CONCAT('settlement:      ', (SELECT COUNT(*) FROM settlement)) AS r;
SELECT CONCAT('user_settlement: ', (SELECT COUNT(*) FROM user_settlement)) AS r;
SELECT CONCAT('notification:    ', (SELECT COUNT(*) FROM notification)) AS r;
SELECT CONCAT('fcm_token:       ', (SELECT COUNT(*) FROM fcm_token)) AS r;
SELECT CONCAT('소요시간: ', TIMEDIFF(NOW(), @START_TIME)) AS r;
SELECT '=== 완료 ===' AS '';

DO RELEASE_LOCK('onlyone_seed_lock');
