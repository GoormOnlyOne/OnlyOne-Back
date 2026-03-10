-- =============================================================
-- 전체 도메인 통합 시드 데이터 (MySQL) — 500x 스케일
-- =============================================================
-- 실행: mysql -uroot -proot onlyone < k6-tests/seed/seed-all-domains-500x.sql
--
-- 대상 테이블 (18개):
--   user, interest, user_interest, club, user_club,
--   feed, feed_comment, feed_like, feed_image,
--   schedule, user_schedule,
--   chat_room, user_chat_room, message,
--   wallet, payment, settlement, user_settlement, outbox_event
--   notification, fcm_token
--
-- 규모 (500x, 초고부하):
--   user:             500,000명
--   interest:         8개
--   user_interest:    1,000,000
--   club:             200,000개
--   user_club:        ~2,700,000
--   feed:             50,000,000
--   feed_comment:     100,000,000
--   feed_like:        50,000,000
--   feed_image:       50,000,000
--   schedule:         10,000,000
--   user_schedule:    50,000,000
--   chat_room:        200,000
--   user_chat_room:   1,000,000
--   message:          50,000,000
--   wallet:           500,000
--   settlement:       500,000
--   user_settlement:  5,000,000
--   notification:     100,000,000
--   fcm_token:        500,000
--   총 SQL 행:        ~466,000,000
--
-- 예상 소요시간: 3~5시간 (EC2 r6g.xlarge 기준)
-- 예상 디스크: ~200GB (데이터+인덱스)
-- =============================================================

SET @START_TIME = NOW();

-- ═══════════════════════════════════════════
-- 동시 실행 방지 (advisory lock)
-- ═══════════════════════════════════════════
SELECT GET_LOCK('onlyone_seed_lock', 0) INTO @got_lock;
SET @lock_msg = IF(@got_lock = 1,
    '시드 락 획득 완료 — 진행합니다.',
    'ERROR: 다른 세션에서 시드가 이미 실행 중입니다. 완료 후 재시도하세요.');
SELECT @lock_msg AS '';
SELECT IF(@got_lock = 1, 'OK', 1/0) INTO @_guard;

SELECT '========================================' AS '';
SELECT '=== 전체 도메인 시드 데이터 생성 시작 (500x) ===' AS '';
SELECT '========================================' AS '';

-- ═══════════════════════════════════════════
-- 스케일 상수 (k6 common.js와 동기화 필수)
-- ═══════════════════════════════════════════
SET @total_users      = 500000;
SET @total_clubs      = 200000;
SET @total_feeds      = 50000000;
SET @total_comments   = 100000000;
SET @total_likes      = 50000000;
SET @total_images     = 50000000;
SET @total_schedules  = 10000000;
SET @total_chatrooms  = 200000;
SET @total_messages   = 50000000;
SET @total_settlements = 500000;
SET @total_notifications = 100000000;

SET FOREIGN_KEY_CHECKS = 0;
SET UNIQUE_CHECKS = 0;
SET autocommit = 0;
SET SESSION cte_max_recursion_depth = 200000000;
SET SESSION bulk_insert_buffer_size = 256 * 1024 * 1024;
SET SESSION innodb_autoinc_lock_mode = 2;

-- ═══════════════════════════════════════════
-- 헬퍼 테이블: digits (0~9), seq100k (0~99999)
-- ═══════════════════════════════════════════
DROP TABLE IF EXISTS _digits;
CREATE TABLE _digits (d INT NOT NULL) ENGINE=MEMORY;
INSERT INTO _digits VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);

DROP TABLE IF EXISTS _seq100k;
CREATE TABLE _seq100k (n INT NOT NULL, PRIMARY KEY(n)) ENGINE=InnoDB;
INSERT INTO _seq100k
SELECT d5.d*10000 + d4.d*1000 + d3.d*100 + d2.d*10 + d1.d
FROM _digits d1, _digits d2, _digits d3, _digits d4, _digits d5;

SELECT CONCAT('  헬퍼 테이블 생성 완료: _seq100k = ', COUNT(*), ' rows') AS '' FROM _seq100k;

-- ═══════════════════════════════════════════
-- 1) 유저 500,000명
-- ═══════════════════════════════════════════
SELECT '--- [1/14] 유저 생성 (500,000명) ---' AS '';

DELIMITER //
DROP PROCEDURE IF EXISTS seed_users //
CREATE PROCEDURE seed_users()
BEGIN
    DECLARE batch INT DEFAULT 0;
    WHILE batch < 5 DO
        INSERT INTO `user` (kakao_id, nickname, birth, status, profile_image, gender, city, district, role, created_at, modified_at)
        SELECT
            1000000 + batch * 100000 + s.n + 1,
            CONCAT('테스트유저', batch * 100000 + s.n + 1),
            DATE_SUB('2000-01-01', INTERVAL ((batch * 100000 + s.n) % 3650) DAY),
            'ACTIVE',
            NULL,
            IF((batch * 100000 + s.n) % 2 = 0, 'MALE', 'FEMALE'),
            ELT(((batch * 100000 + s.n) % 5) + 1, '서울', '부산', '대구', '인천', '광주'),
            ELT(((batch * 100000 + s.n) % 10) + 1, '강남구', '서초구', '마포구', '중구', '해운대구', '사하구', '북구', '서구', '남구', '동구'),
            'ROLE_USER',
            NOW() - INTERVAL (500000 - (batch * 100000 + s.n)) MINUTE,
            NOW()
        FROM _seq100k s
        ON DUPLICATE KEY UPDATE nickname = VALUES(nickname), modified_at = NOW();
        COMMIT;
        SELECT CONCAT('    유저 진행: ', (batch + 1) * 100000, ' / 500,000') AS '';
        SET batch = batch + 1;
    END WHILE;
END //
DELIMITER ;
CALL seed_users();
DROP PROCEDURE IF EXISTS seed_users;

SELECT CONCAT('  유저: ', COUNT(*)) AS msg FROM `user`;

-- ═══════════════════════════════════════════
-- 2) 관심사 8개 + 유저 관심사 (유저당 2개)
-- ═══════════════════════════════════════════
SELECT '--- [2/14] 관심사 생성 ---' AS '';

INSERT IGNORE INTO interest (interest_id, category, created_at, modified_at)
VALUES
    (1, 'CULTURE', NOW(), NOW()),
    (2, 'EXERCISE', NOW(), NOW()),
    (3, 'TRAVEL', NOW(), NOW()),
    (4, 'MUSIC', NOW(), NOW()),
    (5, 'CRAFT', NOW(), NOW()),
    (6, 'SOCIAL', NOW(), NOW()),
    (7, 'LANGUAGE', NOW(), NOW()),
    (8, 'FINANCE', NOW(), NOW());
COMMIT;

DELIMITER //
DROP PROCEDURE IF EXISTS seed_user_interests //
CREATE PROCEDURE seed_user_interests()
BEGIN
    DECLARE batch INT DEFAULT 0;
    WHILE batch < 5 DO
        -- 관심사 1
        INSERT IGNORE INTO user_interest (user_id, interest_id, created_at, modified_at)
        SELECT batch * 100000 + s.n + 1, (((batch * 100000 + s.n) % 8) + 1), NOW(), NOW()
        FROM _seq100k s;
        -- 관심사 2
        INSERT IGNORE INTO user_interest (user_id, interest_id, created_at, modified_at)
        SELECT batch * 100000 + s.n + 1, ((((batch * 100000 + s.n) + 3) % 8) + 1), NOW(), NOW()
        FROM _seq100k s;
        COMMIT;
        SET batch = batch + 1;
    END WHILE;
END //
DELIMITER ;
CALL seed_user_interests();
DROP PROCEDURE IF EXISTS seed_user_interests;

SELECT CONCAT('  유저관심사: ', COUNT(*)) AS msg FROM user_interest;

-- ═══════════════════════════════════════════
-- 3) 클럽 200,000개
-- ═══════════════════════════════════════════
SELECT '--- [3/14] 클럽 생성 (200,000개) ---' AS '';

DELIMITER //
DROP PROCEDURE IF EXISTS seed_clubs_batch //
CREATE PROCEDURE seed_clubs_batch()
BEGIN
    DECLARE batch INT DEFAULT 0;
    WHILE batch < 20 DO
        INSERT INTO club (name, user_limit, description, city, district, member_count, interest_id, created_at, modified_at)
        SELECT
            CONCAT(
                ELT(((batch * 10000 + s.n) % 10) + 1, '독서모임', '축구동호회', '등산모임', '기타동아리', '뜨개질클럽',
                     '보드게임', '영어회화', '주식스터디', '영화감상', '러닝크루'),
                ' ', batch * 10000 + s.n
            ),
            50,
            CONCAT('테스트 클럽 ', batch * 10000 + s.n, '의 설명입니다. 함께 활동하며 즐거운 시간을 보내세요.'),
            ELT(((batch * 10000 + s.n) % 5) + 1, '서울', '서울', '부산', '대구', '인천'),
            ELT(((batch * 10000 + s.n) % 5) + 1, '강남구', '마포구', '해운대구', '서구', '서구'),
            FLOOR(RAND() * 45) + 5,
            ((batch * 10000 + s.n) % 8) + 1,
            NOW() - INTERVAL FLOOR(RAND() * 365) DAY,
            NOW()
        FROM (SELECT n FROM _seq100k WHERE n < 10000) s
        ON DUPLICATE KEY UPDATE modified_at = NOW();

        COMMIT;
        IF (batch + 1) % 5 = 0 THEN
            SELECT CONCAT('    클럽 진행: ', (batch + 1) * 10000, ' / 200,000') AS '';
        END IF;
        SET batch = batch + 1;
    END WHILE;
END //
DELIMITER ;
CALL seed_clubs_batch();
DROP PROCEDURE IF EXISTS seed_clubs_batch;

SELECT CONCAT('  클럽: ', COUNT(*)) AS msg FROM club;

-- ═══════════════════════════════════════════
-- 4) 유저-클럽 가입 (유저당 ~5개, ~2,700,000건)
-- common.js getUserClubs() 공식과 동기화:
--   CLUB_OFFSET_1 = floor(200000/3) = 66666
--   CLUB_OFFSET_2 = floor(400000/3) = 133333
-- ═══════════════════════════════════════════
SELECT '--- [4/14] 클럽 가입 (~2,700,000건) ---' AS '';

SET @min_club = (SELECT MIN(club_id) FROM club);

DELIMITER //
DROP PROCEDURE IF EXISTS seed_user_clubs //
CREATE PROCEDURE seed_user_clubs()
BEGIN
    DECLARE batch INT DEFAULT 0;
    WHILE batch < 5 DO
        -- 가입 1 (전원): userId % 200000
        INSERT IGNORE INTO user_club (user_id, club_id, role, created_at, modified_at)
        SELECT batch * 100000 + s.n + 1, @min_club + ((batch * 100000 + s.n + 1) % 200000), 'MEMBER', NOW(), NOW()
        FROM _seq100k s;
        COMMIT;

        -- 가입 2 (전원): (userId + 66666) % 200000
        INSERT IGNORE INTO user_club (user_id, club_id, role, created_at, modified_at)
        SELECT batch * 100000 + s.n + 1, @min_club + (((batch * 100000 + s.n + 1) + 66666) % 200000), 'MEMBER', NOW(), NOW()
        FROM _seq100k s;
        COMMIT;

        -- 가입 3 (전원): (userId + 133333) % 200000
        INSERT IGNORE INTO user_club (user_id, club_id, role, created_at, modified_at)
        SELECT batch * 100000 + s.n + 1, @min_club + (((batch * 100000 + s.n + 1) + 133333) % 200000), 'MEMBER', NOW(), NOW()
        FROM _seq100k s;
        COMMIT;

        SELECT CONCAT('    클럽가입 batch ', batch + 1, ' / 5 기본 완료') AS '';
        SET batch = batch + 1;
    END WHILE;

    -- 가입 4 (절반: userId <= 250000): (userId * 7) % 200000
    SET batch = 0;
    WHILE batch < 3 DO
        INSERT IGNORE INTO user_club (user_id, club_id, role, created_at, modified_at)
        SELECT batch * 100000 + s.n + 1, @min_club + (((batch * 100000 + s.n + 1) * 7) % 200000), 'MEMBER', NOW(), NOW()
        FROM _seq100k s
        WHERE (batch < 2) OR (batch = 2 AND s.n < 50000);
        COMMIT;
        SET batch = batch + 1;
    END WHILE;

    -- 가입 5 (20%: userId <= 100000): (userId * 13) % 200000
    INSERT IGNORE INTO user_club (user_id, club_id, role, created_at, modified_at)
    SELECT s.n + 1, @min_club + (((s.n + 1) * 13) % 200000), 'MEMBER', NOW(), NOW()
    FROM _seq100k s;
    COMMIT;

END //
DELIMITER ;
CALL seed_user_clubs();
DROP PROCEDURE IF EXISTS seed_user_clubs;

-- 각 클럽 첫 가입자 LEADER
UPDATE user_club uc
JOIN (SELECT MIN(user_club_id) AS first_id FROM user_club GROUP BY club_id) t
ON uc.user_club_id = t.first_id
SET uc.role = 'LEADER';
COMMIT;

SELECT CONCAT('  유저-클럽: ', COUNT(*)) AS msg FROM user_club;

-- ═══════════════════════════════════════════
-- 5) 피드 50,000,000개
-- ═══════════════════════════════════════════
SELECT '--- [5/14] 피드 생성 (50,000,000개) ---' AS '';

SET @min_feed = 0;

DELIMITER //
DROP PROCEDURE IF EXISTS seed_feeds_batch //
CREATE PROCEDURE seed_feeds_batch()
BEGIN
    DECLARE batch INT DEFAULT 0;
    WHILE batch < 500 DO
        INSERT INTO feed (content, club_id, user_id, type, parent_feed_id, root_feed_id,
                         like_count, comment_count, deleted, created_at, modified_at)
        SELECT
            CONCAT('테스트 피드 #', batch * 100000 + s.n),
            @min_club + ((batch * 100000 + s.n) % 200000),
            ((batch * 100000 + s.n) % 500000) + 1,
            IF(batch < 400, 'ORIGINAL', 'REFEED'),
            IF(batch < 400, NULL, @min_feed + ((batch * 100000 + s.n) % 40000000)),
            IF(batch < 400, NULL, @min_feed + ((batch * 100000 + s.n) % 40000000)),
            FLOOR(RAND() * 30),
            FLOOR(RAND() * 15),
            FALSE,
            NOW() - INTERVAL (50000000 - (batch * 100000 + s.n)) SECOND,
            NOW()
        FROM _seq100k s;

        COMMIT;
        IF (batch + 1) % 50 = 0 THEN
            SELECT CONCAT('    피드 진행: ', (batch + 1) * 100000, ' / 50,000,000  (', NOW(), ')') AS '';
        END IF;
        SET batch = batch + 1;
    END WHILE;
END //
DELIMITER ;
CALL seed_feeds_batch();
DROP PROCEDURE IF EXISTS seed_feeds_batch;

SET @min_feed = (SELECT MIN(feed_id) FROM feed);

-- REFEED parent_feed_id 보정 (ORIGINAL만 참조)
SELECT '    REFEED parent 보정 중...' AS '';
UPDATE feed SET
    parent_feed_id = @min_feed + (feed_id % 40000000),
    root_feed_id = @min_feed + (feed_id % 40000000)
WHERE type = 'REFEED' AND parent_feed_id IS NOT NULL;
COMMIT;

SELECT CONCAT('  피드: ', COUNT(*)) AS msg FROM feed;

-- ═══════════════════════════════════════════
-- 6) 피드 댓글 100,000,000개 (피드당 평균 2개)
-- ═══════════════════════════════════════════
SELECT '--- [6/14] 피드 댓글 생성 (100,000,000개) ---' AS '';

DELIMITER //
DROP PROCEDURE IF EXISTS seed_comments_batch //
CREATE PROCEDURE seed_comments_batch()
BEGIN
    DECLARE batch INT DEFAULT 0;
    WHILE batch < 1000 DO
        INSERT INTO feed_comment (content, feed_id, user_id, created_at, modified_at)
        SELECT
            CONCAT('댓글 #', batch * 100000 + s.n, ' - 좋은 글이네요!'),
            @min_feed + ((batch * 100000 + s.n) % 50000000),
            ((batch * 100000 + s.n) % 500000) + 1,
            NOW() - INTERVAL (100000000 - (batch * 100000 + s.n)) SECOND,
            NOW()
        FROM _seq100k s;

        COMMIT;
        IF (batch + 1) % 100 = 0 THEN
            SELECT CONCAT('    댓글 진행: ', (batch + 1) * 100000, ' / 100,000,000  (', NOW(), ')') AS '';
        END IF;
        SET batch = batch + 1;
    END WHILE;
END //
DELIMITER ;
CALL seed_comments_batch();
DROP PROCEDURE IF EXISTS seed_comments_batch;

SELECT CONCAT('  댓글: ', COUNT(*)) AS msg FROM feed_comment;

-- ═══════════════════════════════════════════
-- 7) 피드 좋아요 50,000,000개
-- ═══════════════════════════════════════════
SELECT '--- [7/14] 피드 좋아요 생성 (50,000,000개) ---' AS '';

DELIMITER //
DROP PROCEDURE IF EXISTS seed_likes_batch //
CREATE PROCEDURE seed_likes_batch()
BEGIN
    DECLARE batch INT DEFAULT 0;
    WHILE batch < 500 DO
        INSERT IGNORE INTO feed_like (feed_id, user_id, created_at, modified_at)
        SELECT
            @min_feed + ((batch * 100000 + s.n) % 50000000),
            ((batch * 100000 + s.n) DIV 50000000 * 250000 + (batch * 100000 + s.n) % 250000) % 500000 + 1,
            NOW() - INTERVAL (50000000 - (batch * 100000 + s.n)) SECOND,
            NOW()
        FROM _seq100k s;

        COMMIT;
        IF (batch + 1) % 50 = 0 THEN
            SELECT CONCAT('    좋아요 진행: ', (batch + 1) * 100000, ' / 50,000,000  (', NOW(), ')') AS '';
        END IF;
        SET batch = batch + 1;
    END WHILE;
END //
DELIMITER ;
CALL seed_likes_batch();
DROP PROCEDURE IF EXISTS seed_likes_batch;

SELECT CONCAT('  좋아요: ', COUNT(*)) AS msg FROM feed_like;

-- ═══════════════════════════════════════════
-- 8) 피드 이미지 50,000,000개 (피드당 1개)
-- ═══════════════════════════════════════════
SELECT '--- [8/14] 피드 이미지 생성 (50,000,000개) ---' AS '';

DELIMITER //
DROP PROCEDURE IF EXISTS seed_images_batch //
CREATE PROCEDURE seed_images_batch()
BEGIN
    DECLARE batch INT DEFAULT 0;
    WHILE batch < 500 DO
        INSERT INTO feed_image (feed_image, feed_id, created_at, modified_at)
        SELECT
            CONCAT('https://d1c3fg3ti7m8cn.cloudfront.net/feed/', @min_feed + (batch * 100000 + s.n), '/img1.jpg'),
            @min_feed + (batch * 100000 + s.n),
            NOW(),
            NOW()
        FROM _seq100k s;

        COMMIT;
        IF (batch + 1) % 50 = 0 THEN
            SELECT CONCAT('    이미지 진행: ', (batch + 1) * 100000, ' / 50,000,000  (', NOW(), ')') AS '';
        END IF;
        SET batch = batch + 1;
    END WHILE;
END //
DELIMITER ;
CALL seed_images_batch();
DROP PROCEDURE IF EXISTS seed_images_batch;

SELECT CONCAT('  이미지: ', COUNT(*)) AS msg FROM feed_image;

-- ═══════════════════════════════════════════
-- 9) 스케줄 10,000,000개 + 유저스케줄 50,000,000개
-- ═══════════════════════════════════════════
SELECT '--- [9/14] 스케줄 생성 (10,000,000개) ---' AS '';

DELIMITER //
DROP PROCEDURE IF EXISTS seed_schedules_batch //
CREATE PROCEDURE seed_schedules_batch()
BEGIN
    DECLARE batch INT DEFAULT 0;
    WHILE batch < 100 DO
        INSERT INTO schedule (schedule_time, name, location, cost, user_limit, status, club_id, created_at, modified_at)
        SELECT
            NOW() + INTERVAL (batch * 100000 + s.n - 5000000) MINUTE,
            CONCAT('모임일정 ', batch * 100000 + s.n),
            CONCAT('장소 ', ((batch * 100000 + s.n) % 10) + 1),
            (FLOOR(RAND() * 10) + 1) * 1000,
            20,
            ELT(((batch * 100000 + s.n) % 4) + 1, 'READY', 'ENDED', 'SETTLING', 'CLOSED'),
            @min_club + ((batch * 100000 + s.n) % 200000),
            NOW() - INTERVAL (10000000 - (batch * 100000 + s.n)) MINUTE,
            NOW()
        FROM _seq100k s;

        COMMIT;
        IF (batch + 1) % 10 = 0 THEN
            SELECT CONCAT('    스케줄 진행: ', (batch + 1) * 100000, ' / 10,000,000  (', NOW(), ')') AS '';
        END IF;
        SET batch = batch + 1;
    END WHILE;
END //
DELIMITER ;
CALL seed_schedules_batch();
DROP PROCEDURE IF EXISTS seed_schedules_batch;

SELECT CONCAT('  스케줄: ', COUNT(*)) AS msg FROM schedule;

-- 유저 스케줄 참여 (스케줄당 5명 = 50,000,000건)
-- common.js getUserSchedules() 공식:
--   userId = ((n * 5 + p) % total_users) + 1
SELECT '--- 유저 스케줄 참여 (50,000,000건) ---' AS '';

SET @min_schedule = (SELECT MIN(schedule_id) FROM schedule WHERE schedule_id < 5000000);

DELIMITER //
DROP PROCEDURE IF EXISTS seed_user_schedules //
CREATE PROCEDURE seed_user_schedules()
BEGIN
    DECLARE p INT DEFAULT 0;
    DECLARE batch INT;
    WHILE p < 5 DO
        SET batch = 0;
        WHILE batch < 100 DO
            INSERT IGNORE INTO user_schedule (user_id, schedule_id, role, created_at, modified_at)
            SELECT
                (((batch * 100000 + s.n) * 5 + p) % 500000) + 1,
                @min_schedule + batch * 100000 + s.n,
                IF(p = 0, 'LEADER', 'MEMBER'),
                NOW(), NOW()
            FROM _seq100k s;

            COMMIT;
            SET batch = batch + 1;
        END WHILE;
        SELECT CONCAT('    유저스케줄 round ', p + 1, ' / 5 완료 (', (p + 1) * 10000000, ' / 50,000,000  ', NOW(), ')') AS '';
        SET p = p + 1;
    END WHILE;
END //
DELIMITER ;
CALL seed_user_schedules();
DROP PROCEDURE IF EXISTS seed_user_schedules;

SELECT CONCAT('  유저스케줄: ', COUNT(*)) AS msg FROM user_schedule;

-- ═══════════════════════════════════════════
-- 10) 채팅방 200,000개 + 참여자 1,000,000 + 메시지 50,000,000
-- common.js getUserChatRooms() 공식:
--   userId = ((n * 5 + p) % total_users) + 1
-- ═══════════════════════════════════════════
SELECT '--- [10/14] 채팅방 생성 (200,000개) ---' AS '';

DELIMITER //
DROP PROCEDURE IF EXISTS seed_chatrooms //
CREATE PROCEDURE seed_chatrooms()
BEGIN
    DECLARE batch INT DEFAULT 0;
    WHILE batch < 2 DO
        INSERT INTO chat_room (club_id, schedule_id, type, created_at, modified_at)
        SELECT
            @min_club + ((batch * 100000 + s.n) % 200000),
            IF((batch * 100000 + s.n) % 3 = 0, @min_schedule + ((batch * 100000 + s.n) % 10000000), NULL),
            IF((batch * 100000 + s.n) % 3 = 0, 'SCHEDULE', 'CLUB'),
            NOW() - INTERVAL (200000 - (batch * 100000 + s.n)) HOUR,
            NOW()
        FROM _seq100k s;
        COMMIT;
        SET batch = batch + 1;
    END WHILE;
END //
DELIMITER ;
CALL seed_chatrooms();
DROP PROCEDURE IF EXISTS seed_chatrooms;

-- 참여자 (방당 5명 = 1,000,000)
SELECT '--- 채팅 참여자 (1,000,000건) ---' AS '';

SET @min_chatroom = (SELECT MIN(chat_room_id) FROM chat_room);

DELIMITER //
DROP PROCEDURE IF EXISTS seed_user_chatrooms //
CREATE PROCEDURE seed_user_chatrooms()
BEGIN
    DECLARE p INT DEFAULT 0;
    DECLARE batch INT;
    WHILE p < 5 DO
        SET batch = 0;
        WHILE batch < 2 DO
            INSERT IGNORE INTO user_chat_room (chat_room_id, user_id, role, created_at, modified_at)
            SELECT
                @min_chatroom + batch * 100000 + s.n,
                (((batch * 100000 + s.n) * 5 + p) % 500000) + 1,
                IF(p = 0, 'LEADER', 'MEMBER'),
                NOW(), NOW()
            FROM _seq100k s;
            COMMIT;
            SET batch = batch + 1;
        END WHILE;
        SET p = p + 1;
    END WHILE;
END //
DELIMITER ;
CALL seed_user_chatrooms();
DROP PROCEDURE IF EXISTS seed_user_chatrooms;

-- 메시지 50,000,000개
SELECT '--- 채팅 메시지 (50,000,000건) ---' AS '';

DELIMITER //
DROP PROCEDURE IF EXISTS seed_messages_batch //
CREATE PROCEDURE seed_messages_batch()
BEGIN
    DECLARE batch INT DEFAULT 0;
    WHILE batch < 500 DO
        INSERT INTO message (chat_room_id, user_id, text, sent_at, deleted, created_at, modified_at)
        SELECT
            @min_chatroom + ((batch * 100000 + s.n) % 200000),
            ((batch * 100000 + s.n) % 500000) + 1,
            CONCAT('채팅 메시지 #', batch * 100000 + s.n, ' - 안녕하세요!'),
            NOW() - INTERVAL (50000000 - (batch * 100000 + s.n)) SECOND,
            FALSE,
            NOW() - INTERVAL (50000000 - (batch * 100000 + s.n)) SECOND,
            NOW()
        FROM _seq100k s;

        COMMIT;
        IF (batch + 1) % 50 = 0 THEN
            SELECT CONCAT('    메시지 진행: ', (batch + 1) * 100000, ' / 50,000,000  (', NOW(), ')') AS '';
        END IF;
        SET batch = batch + 1;
    END WHILE;
END //
DELIMITER ;
CALL seed_messages_batch();
DROP PROCEDURE IF EXISTS seed_messages_batch;

SELECT CONCAT('  채팅방: ', COUNT(*)) AS msg FROM chat_room;
SELECT CONCAT('  참여자: ', COUNT(*)) AS msg FROM user_chat_room;
SELECT CONCAT('  메시지: ', COUNT(*)) AS msg FROM message;

-- ═══════════════════════════════════════════
-- 11) 지갑 500,000개
-- ═══════════════════════════════════════════
SELECT '--- [11/14] 지갑 생성 (500,000개) ---' AS '';

DELIMITER //
DROP PROCEDURE IF EXISTS seed_wallets //
CREATE PROCEDURE seed_wallets()
BEGIN
    DECLARE batch INT DEFAULT 0;
    WHILE batch < 5 DO
        -- 일반 유저: 기본 잔액, 홀드 없음
        INSERT INTO wallet (user_id, posted_balance, pending_out, created_at, modified_at)
        SELECT batch * 100000 + s.n + 1, 100000, 0, NOW(), NOW()
        FROM _seq100k s
        WHERE NOT (batch = 0 AND s.n BETWEEN 1 AND 10)
        ON DUPLICATE KEY UPDATE posted_balance = 100000, pending_out = 0, modified_at = NOW();
        COMMIT;
        SET batch = batch + 1;
    END WHILE;
END //
DELIMITER ;
CALL seed_wallets();
DROP PROCEDURE IF EXISTS seed_wallets;

-- 정산 참여자 (userId 2~11): 500K 정산 × costPerUser 100 = pending_out 50,000,000
INSERT INTO wallet (user_id, posted_balance, pending_out, created_at, modified_at)
SELECT u.user_id, 50000000, 50000000, NOW(), NOW()
FROM `user` u WHERE u.user_id BETWEEN 2 AND 11
ON DUPLICATE KEY UPDATE posted_balance = 50000000, pending_out = 50000000, modified_at = NOW();
COMMIT;

SELECT CONCAT('  지갑: ', COUNT(*)) AS msg FROM wallet;

-- ═══════════════════════════════════════════
-- 12) 정산 500,000건 + 유저정산 5,000,000건
-- ═══════════════════════════════════════════
SELECT '--- [12/14] 정산 생성 (500,000건) ---' AS '';

-- 테스트 전용 스케줄 (5000000~5499999)
DELETE FROM user_settlement WHERE settlement_id IN (
    SELECT settlement_id FROM settlement WHERE schedule_id BETWEEN 5000000 AND 5499999
);
DELETE FROM settlement WHERE schedule_id BETWEEN 5000000 AND 5499999;
DELETE FROM schedule WHERE schedule_id BETWEEN 5000000 AND 5499999;
COMMIT;

DELIMITER //
DROP PROCEDURE IF EXISTS seed_settlement_schedules //
CREATE PROCEDURE seed_settlement_schedules()
BEGIN
    DECLARE batch INT DEFAULT 0;
    WHILE batch < 5 DO
        INSERT INTO schedule (schedule_id, created_at, modified_at, cost, location, name, status, schedule_time, user_limit, club_id)
        SELECT
            5000000 + batch * 100000 + s.n, NOW(), NOW(), 1000, 'LoadTest Location',
            CONCAT('정산테스트 스케줄 ', batch * 100000 + s.n), 'ENDED',
            DATE_SUB(NOW(), INTERVAL 1 DAY), 20, @min_club + ((batch * 100000 + s.n) % 200000)
        FROM _seq100k s
        ON DUPLICATE KEY UPDATE status = 'ENDED', modified_at = NOW();
        COMMIT;
        SET batch = batch + 1;
    END WHILE;
END //
DELIMITER ;
CALL seed_settlement_schedules();
DROP PROCEDURE IF EXISTS seed_settlement_schedules;

SELECT CONCAT('  정산용 스케줄: ', COUNT(*)) AS msg FROM schedule WHERE schedule_id BETWEEN 5000000 AND 5499999;

DELIMITER //
DROP PROCEDURE IF EXISTS seed_settlements //
CREATE PROCEDURE seed_settlements()
BEGIN
    DECLARE batch INT DEFAULT 0;
    WHILE batch < 5 DO
        INSERT INTO settlement (created_at, modified_at, completed_time, schedule_id, sum, total_status, user_id, version)
        SELECT NOW(), NOW(), NULL, 5000000 + batch * 100000 + s.n, 0, 'HOLDING', ((batch * 100000 + s.n) % 500000) + 1, 0
        FROM _seq100k s;
        COMMIT;
        SET batch = batch + 1;
    END WHILE;
END //
DELIMITER ;
CALL seed_settlements();
DROP PROCEDURE IF EXISTS seed_settlements;

-- 유저정산 (각 정산당 10명: userId 2~11 = 5,000,000건)
DELIMITER //
DROP PROCEDURE IF EXISTS seed_user_settlements //
CREATE PROCEDURE seed_user_settlements()
BEGIN
    DECLARE p INT DEFAULT 2;
    WHILE p <= 11 DO
        INSERT INTO user_settlement (created_at, modified_at, completed_time, status, settlement_id, user_id)
        SELECT NOW(), NOW(), NULL, 'HOLD_ACTIVE', s.settlement_id, p
        FROM settlement s
        WHERE s.schedule_id BETWEEN 5000000 AND 5499999 AND s.total_status = 'HOLDING';
        COMMIT;
        SELECT CONCAT('    유저정산 userId=', p, ' 완료 (', (p - 1) * 500000, ' / 5,000,000)') AS '';
        SET p = p + 1;
    END WHILE;
END //
DELIMITER ;
CALL seed_user_settlements();
DROP PROCEDURE IF EXISTS seed_user_settlements;

SELECT CONCAT('  정산: ', COUNT(*)) AS msg FROM settlement WHERE schedule_id BETWEEN 5000000 AND 5499999;
SELECT CONCAT('  유저정산: ', COUNT(*)) AS msg FROM user_settlement us
JOIN settlement s ON us.settlement_id = s.settlement_id WHERE s.schedule_id BETWEEN 5000000 AND 5499999;

-- ═══════════════════════════════════════════
-- 13) 알림 100,000,000건
-- ═══════════════════════════════════════════
SELECT '--- [13/14] 알림 생성 (100,000,000건) ---' AS '';

DELIMITER //
DROP PROCEDURE IF EXISTS seed_notifications_batch //
CREATE PROCEDURE seed_notifications_batch()
BEGIN
    DECLARE batch INT DEFAULT 0;
    WHILE batch < 1000 DO
        INSERT INTO notification (content, is_read, type, user_id, sse_sent, created_at, modified_at)
        SELECT
            CONCAT('알림 #', batch * 100000 + s.n, ' - ', ELT(((batch * 100000 + s.n) % 5) + 1, 'CHAT', 'COMMENT', 'LIKE', 'REFEED', 'SETTLEMENT'), ' 관련 알림입니다.'),
            IF(RAND() < 0.3, TRUE, FALSE),
            ELT(((batch * 100000 + s.n) % 5) + 1, 'CHAT', 'COMMENT', 'LIKE', 'REFEED', 'SETTLEMENT'),
            ((batch * 100000 + s.n) % 500000) + 1,
            IF(RAND() < 0.7, TRUE, FALSE),
            NOW() - INTERVAL (100000000 - (batch * 100000 + s.n)) SECOND,
            NOW()
        FROM _seq100k s;

        COMMIT;
        IF (batch + 1) % 100 = 0 THEN
            SELECT CONCAT('    알림 진행: ', (batch + 1) * 100000, ' / 100,000,000  (', NOW(), ')') AS '';
        END IF;
        SET batch = batch + 1;
    END WHILE;
END //
DELIMITER ;
CALL seed_notifications_batch();
DROP PROCEDURE IF EXISTS seed_notifications_batch;

SELECT CONCAT('  알림: ', COUNT(*)) AS msg FROM notification;

-- ═══════════════════════════════════════════
-- 14) FCM 토큰 500,000개
-- ═══════════════════════════════════════════
SELECT '--- [14/14] FCM 토큰 생성 (500,000개) ---' AS '';

DELIMITER //
DROP PROCEDURE IF EXISTS seed_fcm_tokens //
CREATE PROCEDURE seed_fcm_tokens()
BEGIN
    DECLARE batch INT DEFAULT 0;
    WHILE batch < 5 DO
        INSERT INTO fcm_token (user_id, token, device_type, created_at, modified_at)
        SELECT
            batch * 100000 + s.n + 1,
            CONCAT('fcm-token-loadtest-', batch * 100000 + s.n + 1, '-', UUID()),
            IF((batch * 100000 + s.n) % 2 = 0, 'ANDROID', 'IOS'),
            NOW(), NOW()
        FROM _seq100k s
        ON DUPLICATE KEY UPDATE token = VALUES(token), modified_at = NOW();
        COMMIT;
        SET batch = batch + 1;
    END WHILE;
END //
DELIMITER ;
CALL seed_fcm_tokens();
DROP PROCEDURE IF EXISTS seed_fcm_tokens;

SELECT CONCAT('  FCM 토큰: ', COUNT(*)) AS msg FROM fcm_token;

-- ═══════════════════════════════════════════
-- 카운트 동기화 (100,000건 배치)
-- ═══════════════════════════════════════════
SELECT '--- 피드 카운트 동기화 ---' AS '';

DELIMITER //
DROP PROCEDURE IF EXISTS sync_feed_counts //
CREATE PROCEDURE sync_feed_counts()
BEGIN
    DECLARE batch_start BIGINT;
    DECLARE batch_end BIGINT;
    SET batch_start = @min_feed;
    SET batch_end = @min_feed + 49999999;

    WHILE batch_start <= batch_end DO
        UPDATE feed f SET
            like_count = (SELECT COUNT(*) FROM feed_like fl WHERE fl.feed_id = f.feed_id),
            comment_count = (SELECT COUNT(*) FROM feed_comment fc WHERE fc.feed_id = f.feed_id)
        WHERE f.feed_id BETWEEN batch_start AND batch_start + 99999;
        COMMIT;

        IF (batch_start - @min_feed) % 5000000 = 0 THEN
            SELECT CONCAT('    카운트 동기화: ', batch_start - @min_feed, ' / 50,000,000  (', NOW(), ')') AS '';
        END IF;
        SET batch_start = batch_start + 100000;
    END WHILE;
END //
DELIMITER ;
CALL sync_feed_counts();
DROP PROCEDURE IF EXISTS sync_feed_counts;

-- 클럽 멤버 카운트 동기화
SELECT '--- 클럽 멤버 카운트 동기화 ---' AS '';
DELIMITER //
DROP PROCEDURE IF EXISTS sync_club_counts //
CREATE PROCEDURE sync_club_counts()
BEGIN
    DECLARE batch_start BIGINT;
    SET batch_start = @min_club;
    WHILE batch_start < @min_club + 200000 DO
        UPDATE club c SET
            member_count = (SELECT COUNT(*) FROM user_club uc WHERE uc.club_id = c.club_id)
        WHERE c.club_id BETWEEN batch_start AND batch_start + 9999;
        COMMIT;
        SET batch_start = batch_start + 10000;
    END WHILE;
END //
DELIMITER ;
CALL sync_club_counts();
DROP PROCEDURE IF EXISTS sync_club_counts;

-- ═══════════════════════════════════════════
-- 정리
-- ═══════════════════════════════════════════
DROP TABLE IF EXISTS _seq100k;
DROP TABLE IF EXISTS _digits;

SET FOREIGN_KEY_CHECKS = 1;
SET UNIQUE_CHECKS = 1;
SET autocommit = 1;

-- ═══════════════════════════════════════════
-- 최종 결과
-- ═══════════════════════════════════════════
SELECT '========================================' AS '';
SELECT '=== 시드 데이터 최종 결과 (500x) ===' AS '';
SELECT '========================================' AS '';
SELECT CONCAT('user:            ', (SELECT COUNT(*) FROM `user`)) AS result;
SELECT CONCAT('interest:        ', (SELECT COUNT(*) FROM interest)) AS result;
SELECT CONCAT('user_interest:   ', (SELECT COUNT(*) FROM user_interest)) AS result;
SELECT CONCAT('club:            ', (SELECT COUNT(*) FROM club)) AS result;
SELECT CONCAT('user_club:       ', (SELECT COUNT(*) FROM user_club)) AS result;
SELECT CONCAT('feed:            ', (SELECT COUNT(*) FROM feed)) AS result;
SELECT CONCAT('feed_comment:    ', (SELECT COUNT(*) FROM feed_comment)) AS result;
SELECT CONCAT('feed_like:       ', (SELECT COUNT(*) FROM feed_like)) AS result;
SELECT CONCAT('feed_image:      ', (SELECT COUNT(*) FROM feed_image)) AS result;
SELECT CONCAT('schedule:        ', (SELECT COUNT(*) FROM schedule)) AS result;
SELECT CONCAT('user_schedule:   ', (SELECT COUNT(*) FROM user_schedule)) AS result;
SELECT CONCAT('chat_room:       ', (SELECT COUNT(*) FROM chat_room)) AS result;
SELECT CONCAT('user_chat_room:  ', (SELECT COUNT(*) FROM user_chat_room)) AS result;
SELECT CONCAT('message:         ', (SELECT COUNT(*) FROM message)) AS result;
SELECT CONCAT('wallet:          ', (SELECT COUNT(*) FROM wallet)) AS result;
SELECT CONCAT('settlement:      ', (SELECT COUNT(*) FROM settlement)) AS result;
SELECT CONCAT('user_settlement: ', (SELECT COUNT(*) FROM user_settlement)) AS result;
SELECT CONCAT('notification:    ', (SELECT COUNT(*) FROM notification)) AS result;
SELECT CONCAT('fcm_token:       ', (SELECT COUNT(*) FROM fcm_token)) AS result;
SELECT CONCAT('소요시간: ', TIMEDIFF(NOW(), @START_TIME)) AS result;

-- ═══════════════════════════════════════════
-- k6 환경변수 가이드
-- ═══════════════════════════════════════════
SELECT '========================================' AS '';
SELECT '=== k6 환경변수 설정 가이드 (500x) ===' AS '';
SELECT '========================================' AS '';
SELECT CONCAT('MIN_CLUB=',      (SELECT MIN(club_id) FROM club))         AS env_var;
SELECT CONCAT('MIN_CHATROOM=',  (SELECT MIN(chat_room_id) FROM chat_room)) AS env_var;
SELECT CONCAT('MIN_SCHEDULE=',  (SELECT MIN(schedule_id) FROM schedule WHERE schedule_id < 5000000)) AS env_var;
SELECT CONCAT('TOTAL_USERS=500000')   AS env_var;
SELECT CONCAT('TOTAL_CLUBS=200000')    AS env_var;
SELECT CONCAT('TOTAL_CHATROOMS=200000') AS env_var;
SELECT CONCAT('TOTAL_SCHEDULES=10000000') AS env_var;
SELECT CONCAT('USER_COUNT=500000')    AS env_var;
SELECT CONCAT('SETTLEMENT_COUNT=500000') AS env_var;
SELECT '' AS '';
SELECT '위 값을 k6 실행 시 환경변수로 전달하세요.' AS '';

-- ═══════════════════════════════════════════
-- 데이터 정합성 검증
-- ═══════════════════════════════════════════
SELECT '========================================' AS '';
SELECT '=== 데이터 정합성 검증 ===' AS '';
SELECT '========================================' AS '';

SELECT CONCAT('유저 수 OK: ',
    IF((SELECT COUNT(*) FROM `user`) >= 500000, 'PASS', 'FAIL'),
    ' (', (SELECT COUNT(*) FROM `user`), ')') AS verify;

SELECT CONCAT('클럽 수 OK: ',
    IF((SELECT COUNT(*) FROM club) >= 200000, 'PASS', 'FAIL'),
    ' (', (SELECT COUNT(*) FROM club), ')') AS verify;

SELECT CONCAT('정산참여자 지갑 OK: ',
    IF((SELECT COUNT(*) FROM wallet WHERE user_id BETWEEN 2 AND 11 AND pending_out > 0) = 10,
       'PASS (10/10)', 'FAIL')) AS verify;

SELECT CONCAT('정산 데이터 OK: ',
    IF((SELECT COUNT(*) FROM settlement WHERE schedule_id BETWEEN 5000000 AND 5499999) >= 500000,
       CONCAT('PASS (', (SELECT COUNT(*) FROM settlement WHERE schedule_id BETWEEN 5000000 AND 5499999), '건)'),
       CONCAT('FAIL (', (SELECT COUNT(*) FROM settlement WHERE schedule_id BETWEEN 5000000 AND 5499999), '건)'))) AS verify;

SELECT CONCAT('유저정산 데이터 OK: ',
    IF((SELECT COUNT(*) FROM user_settlement us JOIN settlement s ON us.settlement_id = s.settlement_id WHERE s.schedule_id BETWEEN 5000000 AND 5499999) >= 5000000,
       'PASS', 'FAIL'),
    ' (', (SELECT COUNT(*) FROM user_settlement us JOIN settlement s ON us.settlement_id = s.settlement_id WHERE s.schedule_id BETWEEN 5000000 AND 5499999), '건)') AS verify;

SELECT CONCAT('클럽 가입 OK: ',
    IF((SELECT COUNT(*) FROM user_club) >= 2500000, 'PASS', 'FAIL'),
    ' (', (SELECT COUNT(*) FROM user_club), '건)') AS verify;

SELECT '=== 완료 ===' AS '';

DO RELEASE_LOCK('onlyone_seed_lock');
