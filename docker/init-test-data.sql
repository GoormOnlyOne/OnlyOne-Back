-- OnlyOne-Back 대량 데이터 삽입 스크립트
-- 10,000,000개의 알림 데이터 생성 (부하 테스트용)

USE onlyone;

-- 1. 테스트용 사용자 생성 프로시저
DROP PROCEDURE IF EXISTS insert_test_users;

DELIMITER //
CREATE PROCEDURE insert_test_users(IN user_count INT)
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE batch_size INT DEFAULT 1000;
    DECLARE current_batch INT DEFAULT 0;

    SET autocommit = 0;

    START TRANSACTION;

    WHILE i <= user_count DO
        INSERT INTO `user` (
            kakao_id,
            nickname,
            birth,
            gender,
            status,
            role,
            created_at,
            modified_at
        ) VALUES (
            10000000 + i,
            CONCAT('테스트유저', i),
            DATE_SUB(CURDATE(), INTERVAL (18 + (i % 50)) YEAR),
            IF(i % 2 = 0, 'MALE', 'FEMALE'),
            IF(i % 100 = 0, 'INACTIVE', IF(i % 50 = 0, 'GUEST', 'ACTIVE')),
            IF(i % 1000 = 0, 'ROLE_ADMIN', 'ROLE_USER'),
            NOW(),
            NOW()
        );

        SET current_batch = current_batch + 1;

        -- 1000개마다 커밋
        IF current_batch >= batch_size THEN
            COMMIT;
            START TRANSACTION;
            SET current_batch = 0;

            -- 진행상황 출력
            SELECT CONCAT('Users inserted: ', i, '/', user_count) AS progress;
        END IF;

        SET i = i + 1;
    END WHILE;

    COMMIT;
    SET autocommit = 1;

    SELECT CONCAT('Total users inserted: ', user_count) AS result;
END //
DELIMITER ;

-- 2. 대량 알림 생성 프로시저
DROP PROCEDURE IF EXISTS insert_test_notifications;

DELIMITER //
CREATE PROCEDURE insert_test_notifications(
    IN notification_count INT,
    IN min_user_id BIGINT,
    IN max_user_id BIGINT
)
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE batch_size INT DEFAULT 5000;
    DECLARE current_batch INT DEFAULT 0;
    DECLARE random_user_id BIGINT;
    DECLARE random_type VARCHAR(20);
    DECLARE random_content TEXT;
    DECLARE random_is_read BOOLEAN;
    DECLARE random_days_ago INT;

    SET autocommit = 0;

    START TRANSACTION;

    WHILE i <= notification_count DO
        -- 랜덤 사용자 ID
        SET random_user_id = min_user_id + FLOOR(RAND() * (max_user_id - min_user_id + 1));

        -- 랜덤 알림 타입 (CHAT, SETTLEMENT, LIKE, COMMENT, REFEED)
        SET random_type = ELT(1 + FLOOR(RAND() * 5), 'CHAT', 'SETTLEMENT', 'LIKE', 'COMMENT', 'REFEED');

        -- 타입별 랜덤 내용
        SET random_content = CASE random_type
            WHEN 'CHAT' THEN CONCAT('발신자', FLOOR(RAND() * 1000), '님이 메시지를 보냈습니다.')
            WHEN 'SETTLEMENT' THEN CONCAT('정산', FLOOR(RAND() * 100), '이 완료되었습니다.')
            WHEN 'LIKE' THEN CONCAT('사용자', FLOOR(RAND() * 1000), '님이 회원님의 게시물을 좋아합니다.')
            WHEN 'COMMENT' THEN CONCAT('사용자', FLOOR(RAND() * 1000), '님이 댓글을 남겼습니다: 테스트 댓글 내용')
            WHEN 'REFEED' THEN CONCAT('사용자', FLOOR(RAND() * 1000), '님이 회원님의 게시물을 리피드했습니다.')
        END;

        -- 읽음 여부 (30%는 읽음)
        SET random_is_read = IF(RAND() < 0.3, TRUE, FALSE);

        -- 생성 시간 (최근 30일 이내 랜덤)
        SET random_days_ago = FLOOR(RAND() * 30);

        INSERT INTO notification (
            user_id,
            type,
            content,
            is_read,
            sse_sent,
            created_at,
            modified_at
        ) VALUES (
            random_user_id,
            random_type,
            random_content,
            random_is_read,
            TRUE,
            DATE_SUB(NOW(), INTERVAL random_days_ago DAY) + INTERVAL FLOOR(RAND() * 86400) SECOND,
            NOW()
        );

        SET current_batch = current_batch + 1;

        -- 5000개마다 커밋 (성능 최적화)
        IF current_batch >= batch_size THEN
            COMMIT;
            START TRANSACTION;
            SET current_batch = 0;

            -- 진행상황 출력 (100,000개마다)
            IF i % 100000 = 0 THEN
                SELECT CONCAT('Notifications inserted: ', i, '/', notification_count,
                             ' (', ROUND(i * 100.0 / notification_count, 2), '%)') AS progress;
            END IF;
        END IF;

        SET i = i + 1;
    END WHILE;

    COMMIT;
    SET autocommit = 1;

    SELECT CONCAT('Total notifications inserted: ', notification_count) AS result;
END //
DELIMITER ;

-- 3. 특정 사용자에게 집중된 알림 생성 (핫스팟 시뮬레이션)
DROP PROCEDURE IF EXISTS insert_hotspot_notifications;

DELIMITER //
CREATE PROCEDURE insert_hotspot_notifications(
    IN notification_count INT,
    IN target_user_id BIGINT
)
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE batch_size INT DEFAULT 5000;
    DECLARE current_batch INT DEFAULT 0;

    SET autocommit = 0;
    START TRANSACTION;

    WHILE i <= notification_count DO
        INSERT INTO notification (
            user_id,
            type,
            content,
            is_read,
            sse_sent,
            created_at,
            modified_at
        ) VALUES (
            target_user_id,
            ELT(1 + FLOOR(RAND() * 5), 'CHAT', 'SETTLEMENT', 'LIKE', 'COMMENT', 'REFEED'),
            CONCAT('핫스팟 테스트 알림 #', i),
            FALSE,
            FALSE,
            NOW() - INTERVAL FLOOR(RAND() * 3600) SECOND,
            NOW()
        );

        SET current_batch = current_batch + 1;

        IF current_batch >= batch_size THEN
            COMMIT;
            START TRANSACTION;
            SET current_batch = 0;
        END IF;

        SET i = i + 1;
    END WHILE;

    COMMIT;
    SET autocommit = 1;

    SELECT CONCAT('Hotspot notifications inserted: ', notification_count, ' for user ', target_user_id) AS result;
END //
DELIMITER ;

-- 4. 인덱스 최적화
-- 인덱스는 수동으로 생성하거나 애플리케이션에서 자동 생성됨
-- CREATE INDEX idx_notification_user_created ON notification(user_id, created_at DESC);
-- CREATE INDEX idx_notification_user_read ON notification(user_id, is_read);
-- CREATE INDEX idx_notification_created_at ON notification(created_at);
-- CREATE INDEX idx_notification_type ON notification(type);

-- 5. 통계 정보 조회 프로시저
DROP PROCEDURE IF EXISTS show_test_data_stats;

DELIMITER //
CREATE PROCEDURE show_test_data_stats()
BEGIN
    SELECT 'User Statistics' AS category;
    SELECT
        COUNT(*) AS total_users,
        SUM(CASE WHEN status = 'ACTIVE' THEN 1 ELSE 0 END) AS active_users,
        SUM(CASE WHEN status = 'INACTIVE' THEN 1 ELSE 0 END) AS inactive_users,
        SUM(CASE WHEN status = 'GUEST' THEN 1 ELSE 0 END) AS guest_users,
        SUM(CASE WHEN role = 'ROLE_ADMIN' THEN 1 ELSE 0 END) AS admin_users
    FROM `user`;

    SELECT 'Notification Statistics' AS category;
    SELECT
        COUNT(*) AS total_notifications,
        SUM(CASE WHEN is_read = TRUE THEN 1 ELSE 0 END) AS read_notifications,
        SUM(CASE WHEN is_read = FALSE THEN 1 ELSE 0 END) AS unread_notifications
    FROM notification;

    SELECT 'Notification Type Distribution' AS category;
    SELECT
        type,
        COUNT(*) AS count,
        ROUND(COUNT(*) * 100.0 / (SELECT COUNT(*) FROM notification), 2) AS percentage
    FROM notification
    GROUP BY type
    ORDER BY count DESC;

    SELECT 'Top 10 Users by Notification Count' AS category;
    SELECT
        user_id,
        COUNT(*) AS notification_count,
        SUM(CASE WHEN is_read = FALSE THEN 1 ELSE 0 END) AS unread_count
    FROM notification
    GROUP BY user_id
    ORDER BY notification_count DESC
    LIMIT 10;
END //
DELIMITER ;

-- ============================================
-- 6. Interest 기본 데이터 (8개 카테고리)
-- ============================================
INSERT IGNORE INTO interest (interest_id, category, created_at, modified_at) VALUES
(1, 'CULTURE', NOW(), NOW()),
(2, 'EXERCISE', NOW(), NOW()),
(3, 'TRAVEL', NOW(), NOW()),
(4, 'MUSIC', NOW(), NOW()),
(5, 'CRAFT', NOW(), NOW()),
(6, 'SOCIAL', NOW(), NOW()),
(7, 'LANGUAGE', NOW(), NOW()),
(8, 'FINANCE', NOW(), NOW());

-- ============================================
-- 7. UserInterest 대량 생성 (사용자당 1-3개)
-- ============================================
DROP PROCEDURE IF EXISTS insert_test_user_interests;

DELIMITER //
CREATE PROCEDURE insert_test_user_interests(IN user_count INT)
BEGIN
    DECLARE v_user_id INT DEFAULT 1;
    DECLARE v_interest_count INT;
    DECLARE j INT;
    DECLARE v_interest_id BIGINT;
    DECLARE batch_size INT DEFAULT 5000;
    DECLARE current_batch INT DEFAULT 0;
    DECLARE total_inserted INT DEFAULT 0;

    SET autocommit = 0;
    START TRANSACTION;

    WHILE v_user_id <= user_count DO
        SET v_interest_count = (v_user_id % 3) + 1;
        SET j = 0;
        WHILE j < v_interest_count DO
            SET v_interest_id = ((v_user_id + j) % 8) + 1;

            INSERT IGNORE INTO user_interest (
                user_id, interest_id, created_at, modified_at
            ) VALUES (
                v_user_id, v_interest_id, NOW(), NOW()
            );

            SET total_inserted = total_inserted + 1;
            SET current_batch = current_batch + 1;

            IF current_batch >= batch_size THEN
                COMMIT;
                START TRANSACTION;
                SET current_batch = 0;
            END IF;

            SET j = j + 1;
        END WHILE;

        SET v_user_id = v_user_id + 1;
    END WHILE;

    COMMIT;
    SET autocommit = 1;
    SELECT CONCAT('UserInterests: ~', total_inserted, ' inserted') AS result;
END //
DELIMITER ;

-- ============================================
-- 8. 클럽 대량 생성
-- ============================================
DROP PROCEDURE IF EXISTS insert_test_clubs;

DELIMITER //
CREATE PROCEDURE insert_test_clubs(IN club_count INT)
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE batch_size INT DEFAULT 1000;
    DECLARE current_batch INT DEFAULT 0;
    DECLARE v_city VARCHAR(20);
    DECLARE v_district VARCHAR(20);
    DECLARE v_interest_id BIGINT;

    SET autocommit = 0;
    START TRANSACTION;

    WHILE i <= club_count DO
        SET v_interest_id = ((i - 1) % 8) + 1;
        SET v_city = ELT(((i - 1) % 8) + 1, '서울', '부산', '대구', '인천', '광주', '대전', '울산', '세종');
        SET v_district = ELT(((i - 1) % 5) + 1, '강남구', '해운대구', '중구', '남구', '서구');

        INSERT INTO club (
            name, user_limit, description, club_image, city, district,
            member_count, interest_id, created_at, modified_at
        ) VALUES (
            CONCAT('테스트클럽_', i),
            50 + (i % 50),
            CONCAT('부하 테스트용 클럽 #', i, '. 다양한 활동을 함께 즐겨요!'),
            NULL,
            v_city,
            v_district,
            0,
            v_interest_id,
            DATE_SUB(NOW(), INTERVAL (i % 365) DAY),
            NOW()
        );

        SET current_batch = current_batch + 1;
        IF current_batch >= batch_size THEN
            COMMIT;
            START TRANSACTION;
            SET current_batch = 0;
            SELECT CONCAT('Clubs inserted: ', i, '/', club_count) AS progress;
        END IF;

        SET i = i + 1;
    END WHILE;

    COMMIT;
    SET autocommit = 1;
    SELECT CONCAT('Clubs: ', club_count, ' inserted') AS result;
END //
DELIMITER ;

-- ============================================
-- 9. UserClub 대량 생성 (멤버/클럽)
-- ============================================
DROP PROCEDURE IF EXISTS insert_test_user_clubs;

DELIMITER //
CREATE PROCEDURE insert_test_user_clubs(IN members_per_club INT, IN club_count INT, IN user_count INT)
BEGIN
    DECLARE v_club_id INT DEFAULT 1;
    DECLARE v_member_idx INT;
    DECLARE v_user_id BIGINT;
    DECLARE v_role VARCHAR(10);
    DECLARE batch_size INT DEFAULT 5000;
    DECLARE current_batch INT DEFAULT 0;
    DECLARE total_inserted INT DEFAULT 0;

    SET autocommit = 0;
    START TRANSACTION;

    WHILE v_club_id <= club_count DO
        SET v_member_idx = 0;
        WHILE v_member_idx < members_per_club DO
            SET v_user_id = ((v_club_id - 1) * members_per_club + v_member_idx) % user_count + 1;
            SET v_role = IF(v_member_idx = 0, 'LEADER', 'MEMBER');

            INSERT IGNORE INTO user_club (
                user_id, club_id, role, created_at, modified_at
            ) VALUES (
                v_user_id, v_club_id, v_role, NOW(), NOW()
            );

            SET current_batch = current_batch + 1;
            SET total_inserted = total_inserted + 1;

            IF current_batch >= batch_size THEN
                COMMIT;
                START TRANSACTION;
                SET current_batch = 0;
                IF total_inserted % 50000 = 0 THEN
                    SELECT CONCAT('UserClubs inserted: ', total_inserted) AS progress;
                END IF;
            END IF;

            SET v_member_idx = v_member_idx + 1;
        END WHILE;

        UPDATE club SET member_count = members_per_club WHERE club_id = v_club_id;
        SET v_club_id = v_club_id + 1;
    END WHILE;

    COMMIT;
    SET autocommit = 1;
    SELECT CONCAT('UserClubs: ', total_inserted, ' inserted') AS result;
END //
DELIMITER ;

-- ============================================
-- 10. 일정 대량 생성 (READY 상태)
-- ============================================
DROP PROCEDURE IF EXISTS insert_test_schedules;

DELIMITER //
CREATE PROCEDURE insert_test_schedules(IN schedule_count INT, IN club_count INT, IN user_count INT)
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE v_club_id BIGINT;
    DECLARE v_leader_id BIGINT;
    DECLARE batch_size INT DEFAULT 1000;
    DECLARE current_batch INT DEFAULT 0;

    SET autocommit = 0;
    START TRANSACTION;

    WHILE i <= schedule_count DO
        SET v_club_id = ((i - 1) % club_count) + 1;

        INSERT INTO schedule (
            name, location, schedule_time, cost, user_limit,
            status, club_id, created_at, modified_at
        ) VALUES (
            CONCAT('테스트일정_', i),
            CONCAT('테스트 장소 ', (i % 50) + 1),
            DATE_ADD(NOW(), INTERVAL (i % 60 + 1) DAY),
            (i % 10 + 1) * 5000,
            20 + (i % 30),
            'READY',
            v_club_id,
            NOW(),
            NOW()
        );

        -- 리더 UserSchedule
        SET v_leader_id = ((v_club_id - 1) * 50) % user_count + 1;
        INSERT IGNORE INTO user_schedule (
            user_id, schedule_id, role, created_at, modified_at
        ) VALUES (
            v_leader_id, i, 'LEADER', NOW(), NOW()
        );

        SET current_batch = current_batch + 1;
        IF current_batch >= batch_size THEN
            COMMIT;
            START TRANSACTION;
            SET current_batch = 0;
            SELECT CONCAT('Schedules inserted: ', i, '/', schedule_count) AS progress;
        END IF;

        SET i = i + 1;
    END WHILE;

    COMMIT;
    SET autocommit = 1;
    SELECT CONCAT('Schedules: ', schedule_count, ' inserted') AS result;
END //
DELIMITER ;

-- ============================================
-- 11. 피드 + 이미지 대량 생성
-- ============================================
DROP PROCEDURE IF EXISTS insert_test_feeds;

DELIMITER //
CREATE PROCEDURE insert_test_feeds(IN feed_count INT, IN club_count INT, IN user_count INT)
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE v_club_id BIGINT;
    DECLARE v_user_id BIGINT;
    DECLARE v_feed_id BIGINT;
    DECLARE j INT;
    DECLARE batch_size INT DEFAULT 2000;
    DECLARE current_batch INT DEFAULT 0;

    SET autocommit = 0;
    START TRANSACTION;

    WHILE i <= feed_count DO
        SET v_club_id = ((i - 1) % club_count) + 1;
        SET v_user_id = ((i - 1) % user_count) + 1;

        INSERT INTO feed (
            content, club_id, user_id, type, like_count, deleted,
            created_at, modified_at
        ) VALUES (
            CONCAT('부하 테스트 피드 #', i, '. 오늘도 즐거운 모임이었습니다!'),
            v_club_id,
            v_user_id,
            'ORIGINAL',
            FLOOR(RAND() * 200),
            FALSE,
            DATE_SUB(NOW(), INTERVAL (i % 180) DAY) + INTERVAL FLOOR(RAND() * 86400) SECOND,
            NOW()
        );

        SET v_feed_id = LAST_INSERT_ID();

        -- 피드당 3개 이미지
        SET j = 1;
        WHILE j <= 3 DO
            INSERT INTO feed_image (
                feed_image, feed_id, created_at, modified_at
            ) VALUES (
                CONCAT('https://example.com/images/feed_', v_feed_id, '_', j, '.jpg'),
                v_feed_id,
                NOW(),
                NOW()
            );
            SET j = j + 1;
        END WHILE;

        SET current_batch = current_batch + 1;
        IF current_batch >= batch_size THEN
            COMMIT;
            START TRANSACTION;
            SET current_batch = 0;
            IF i % 10000 = 0 THEN
                SELECT CONCAT('Feeds inserted: ', i, '/', feed_count) AS progress;
            END IF;
        END IF;

        SET i = i + 1;
    END WHILE;

    COMMIT;
    SET autocommit = 1;
    SELECT CONCAT('Feeds: ', feed_count, ' + FeedImages: ', feed_count * 3, ' inserted') AS result;
END //
DELIMITER ;

-- ============================================
-- 12. 좋아요 대량 생성
-- ============================================
DROP PROCEDURE IF EXISTS insert_test_feed_likes;

DELIMITER //
CREATE PROCEDURE insert_test_feed_likes(IN like_count INT, IN feed_count INT, IN user_count INT)
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE v_feed_id BIGINT;
    DECLARE v_user_id BIGINT;
    DECLARE batch_size INT DEFAULT 5000;
    DECLARE current_batch INT DEFAULT 0;

    SET autocommit = 0;
    START TRANSACTION;

    WHILE i <= like_count DO
        SET v_feed_id = ((i - 1) % feed_count) + 1;
        SET v_user_id = FLOOR(RAND() * user_count) + 1;

        INSERT IGNORE INTO feed_like (
            feed_id, user_id, created_at, modified_at
        ) VALUES (
            v_feed_id, v_user_id,
            DATE_SUB(NOW(), INTERVAL FLOOR(RAND() * 60) DAY),
            NOW()
        );

        SET current_batch = current_batch + 1;
        IF current_batch >= batch_size THEN
            COMMIT;
            START TRANSACTION;
            SET current_batch = 0;
            IF i % 100000 = 0 THEN
                SELECT CONCAT('FeedLikes inserted: ', i, '/', like_count) AS progress;
            END IF;
        END IF;

        SET i = i + 1;
    END WHILE;

    COMMIT;
    SET autocommit = 1;
    SELECT CONCAT('FeedLikes: ~', like_count, ' inserted (dupes ignored)') AS result;
END //
DELIMITER ;

-- ============================================
-- 13. 댓글 대량 생성
-- ============================================
DROP PROCEDURE IF EXISTS insert_test_feed_comments;

DELIMITER //
CREATE PROCEDURE insert_test_feed_comments(IN comment_count INT, IN feed_count INT, IN user_count INT)
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE v_feed_id BIGINT;
    DECLARE v_user_id BIGINT;
    DECLARE batch_size INT DEFAULT 5000;
    DECLARE current_batch INT DEFAULT 0;

    SET autocommit = 0;
    START TRANSACTION;

    WHILE i <= comment_count DO
        SET v_feed_id = ((i - 1) % feed_count) + 1;
        SET v_user_id = FLOOR(RAND() * user_count) + 1;

        INSERT INTO feed_comment (
            content, feed_id, user_id, created_at, modified_at
        ) VALUES (
            CONCAT('테스트 댓글 #', i, ' - 좋은 모임이네요!'),
            v_feed_id, v_user_id,
            DATE_SUB(NOW(), INTERVAL FLOOR(RAND() * 60) DAY),
            NOW()
        );

        SET current_batch = current_batch + 1;
        IF current_batch >= batch_size THEN
            COMMIT;
            START TRANSACTION;
            SET current_batch = 0;
            IF i % 50000 = 0 THEN
                SELECT CONCAT('FeedComments inserted: ', i, '/', comment_count) AS progress;
            END IF;
        END IF;

        SET i = i + 1;
    END WHILE;

    COMMIT;
    SET autocommit = 1;
    SELECT CONCAT('FeedComments: ', comment_count, ' inserted') AS result;
END //
DELIMITER ;

-- ============================================
-- 14. 채팅방 + 멤버 대량 생성
-- ============================================
DROP PROCEDURE IF EXISTS insert_test_chat_rooms;

DELIMITER //
CREATE PROCEDURE insert_test_chat_rooms(IN room_count INT, IN members_per_room INT, IN club_count INT, IN user_count INT)
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE v_club_id BIGINT;
    DECLARE v_chat_room_id BIGINT;
    DECLARE j INT;
    DECLARE v_user_id BIGINT;
    DECLARE v_role VARCHAR(10);
    DECLARE batch_size INT DEFAULT 1000;
    DECLARE current_batch INT DEFAULT 0;

    SET autocommit = 0;
    START TRANSACTION;

    WHILE i <= room_count DO
        SET v_club_id = ((i - 1) % club_count) + 1;

        INSERT INTO chat_room (
            club_id, type, created_at, modified_at
        ) VALUES (
            v_club_id, 'CLUB', NOW(), NOW()
        );

        SET v_chat_room_id = LAST_INSERT_ID();

        SET j = 0;
        WHILE j < members_per_room DO
            SET v_user_id = ((v_club_id - 1) * members_per_room + j) % user_count + 1;
            SET v_role = IF(j = 0, 'LEADER', 'MEMBER');

            INSERT IGNORE INTO user_chat_room (
                chat_room_id, user_id, role, created_at, modified_at
            ) VALUES (
                v_chat_room_id, v_user_id, v_role, NOW(), NOW()
            );

            SET j = j + 1;
        END WHILE;

        SET current_batch = current_batch + 1;
        IF current_batch >= batch_size THEN
            COMMIT;
            START TRANSACTION;
            SET current_batch = 0;
            SELECT CONCAT('ChatRooms inserted: ', i, '/', room_count) AS progress;
        END IF;

        SET i = i + 1;
    END WHILE;

    COMMIT;
    SET autocommit = 1;
    SELECT CONCAT('ChatRooms: ', room_count, ' + UserChatRooms: ', room_count * members_per_room, ' inserted') AS result;
END //
DELIMITER ;

-- ============================================
-- 15. 메시지 대량 생성
-- ============================================
DROP PROCEDURE IF EXISTS insert_test_messages;

DELIMITER //
CREATE PROCEDURE insert_test_messages(IN message_count INT, IN room_count INT, IN user_count INT)
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE v_chat_room_id BIGINT;
    DECLARE v_user_id BIGINT;
    DECLARE batch_size INT DEFAULT 5000;
    DECLARE current_batch INT DEFAULT 0;

    SET autocommit = 0;
    START TRANSACTION;

    WHILE i <= message_count DO
        SET v_chat_room_id = ((i - 1) % room_count) + 1;
        SET v_user_id = FLOOR(RAND() * user_count) + 1;

        INSERT INTO message (
            chat_room_id, user_id, text, sent_at, deleted,
            created_at, modified_at
        ) VALUES (
            v_chat_room_id,
            v_user_id,
            CONCAT('테스트 메시지 #', i, ' - 안녕하세요!'),
            DATE_SUB(NOW(), INTERVAL FLOOR(RAND() * 60) DAY) + INTERVAL FLOOR(RAND() * 86400) SECOND,
            FALSE,
            DATE_SUB(NOW(), INTERVAL FLOOR(RAND() * 60) DAY),
            NOW()
        );

        SET current_batch = current_batch + 1;
        IF current_batch >= batch_size THEN
            COMMIT;
            START TRANSACTION;
            SET current_batch = 0;
            IF i % 100000 = 0 THEN
                SELECT CONCAT('Messages inserted: ', i, '/', message_count) AS progress;
            END IF;
        END IF;

        SET i = i + 1;
    END WHILE;

    COMMIT;
    SET autocommit = 1;
    SELECT CONCAT('Messages: ', message_count, ' inserted') AS result;
END //
DELIMITER ;

-- ============================================
-- 16. 지갑 대량 생성
-- ============================================
DROP PROCEDURE IF EXISTS insert_test_wallets;

DELIMITER //
CREATE PROCEDURE insert_test_wallets(IN wallet_count INT)
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE batch_size INT DEFAULT 1000;
    DECLARE current_batch INT DEFAULT 0;

    SET autocommit = 0;
    START TRANSACTION;

    WHILE i <= wallet_count DO
        INSERT IGNORE INTO wallet (
            user_id, posted_balance, pending_out, created_at, modified_at
        ) VALUES (
            i, 100000, 0, NOW(), NOW()
        );

        SET current_batch = current_batch + 1;
        IF current_batch >= batch_size THEN
            COMMIT;
            START TRANSACTION;
            SET current_batch = 0;
        END IF;

        SET i = i + 1;
    END WHILE;

    COMMIT;
    SET autocommit = 1;
    SELECT CONCAT('Wallets: ', wallet_count, ' inserted (balance=100000)') AS result;
END //
DELIMITER ;

-- ============================================
-- 17. 전체 도메인 통합 통계 조회
-- ============================================
DROP PROCEDURE IF EXISTS show_test_data_stats;

DELIMITER //
CREATE PROCEDURE show_test_data_stats()
BEGIN
    SELECT '=== Load Test Data Statistics ===' AS header;

    SELECT 'Users' AS entity, COUNT(*) AS count FROM `user`
    UNION ALL SELECT 'Interests', COUNT(*) FROM interest
    UNION ALL SELECT 'UserInterests', COUNT(*) FROM user_interest
    UNION ALL SELECT 'Clubs', COUNT(*) FROM club
    UNION ALL SELECT 'UserClubs', COUNT(*) FROM user_club
    UNION ALL SELECT 'Schedules', COUNT(*) FROM schedule
    UNION ALL SELECT 'UserSchedules', COUNT(*) FROM user_schedule
    UNION ALL SELECT 'Feeds', COUNT(*) FROM feed
    UNION ALL SELECT 'FeedImages', COUNT(*) FROM feed_image
    UNION ALL SELECT 'FeedLikes', COUNT(*) FROM feed_like
    UNION ALL SELECT 'FeedComments', COUNT(*) FROM feed_comment
    UNION ALL SELECT 'ChatRooms', COUNT(*) FROM chat_room
    UNION ALL SELECT 'UserChatRooms', COUNT(*) FROM user_chat_room
    UNION ALL SELECT 'Messages', COUNT(*) FROM message
    UNION ALL SELECT 'Wallets', COUNT(*) FROM wallet
    UNION ALL SELECT 'Notifications', COUNT(*) FROM notification;
END //
DELIMITER ;

-- ============================================
-- 18. 전체 일괄 실행 프로시저
-- ============================================
DROP PROCEDURE IF EXISTS setup_all_load_test_data;

DELIMITER //
CREATE PROCEDURE setup_all_load_test_data()
BEGIN
    DECLARE start_time DATETIME DEFAULT NOW();

    SELECT '=== Full Load Test Data Setup ===' AS status;

    SELECT '>>> 1/11: Users 10,000...' AS step;
    CALL insert_test_users(10000);

    SELECT '>>> 2/11: UserInterests...' AS step;
    CALL insert_test_user_interests(10000);

    SELECT '>>> 3/11: Clubs 10,000...' AS step;
    CALL insert_test_clubs(10000);

    SELECT '>>> 4/11: UserClubs 500,000 (50/club)...' AS step;
    CALL insert_test_user_clubs(50, 10000, 10000);

    SELECT '>>> 5/11: Schedules 5,000...' AS step;
    CALL insert_test_schedules(5000, 10000, 10000);

    SELECT '>>> 6/11: Feeds 100,000 + Images 300,000...' AS step;
    CALL insert_test_feeds(100000, 10000, 10000);

    SELECT '>>> 7/11: FeedLikes 1,000,000...' AS step;
    CALL insert_test_feed_likes(1000000, 100000, 10000);

    SELECT '>>> 8/11: FeedComments 500,000...' AS step;
    CALL insert_test_feed_comments(500000, 100000, 10000);

    SELECT '>>> 9/11: ChatRooms 1,000 + Members...' AS step;
    CALL insert_test_chat_rooms(1000, 10, 10000, 10000);

    SELECT '>>> 10/11: Messages 1,000,000...' AS step;
    CALL insert_test_messages(1000000, 1000, 10000);

    SELECT '>>> 11/11: Wallets 10,000...' AS step;
    CALL insert_test_wallets(10000);

    SELECT CONCAT('=== Complete! Duration: ', TIMESTAMPDIFF(SECOND, start_time, NOW()), 's ===') AS status;
    CALL show_test_data_stats();
END //
DELIMITER ;

-- ============================================
-- 19. 전체 초기화
-- ============================================
DROP PROCEDURE IF EXISTS cleanup_all_load_test_data;

DELIMITER //
CREATE PROCEDURE cleanup_all_load_test_data()
BEGIN
    SET FOREIGN_KEY_CHECKS = 0;
    TRUNCATE TABLE wallet_transaction;
    TRUNCATE TABLE wallet;
    TRUNCATE TABLE message;
    TRUNCATE TABLE user_chat_room;
    TRUNCATE TABLE chat_room;
    TRUNCATE TABLE feed_comment;
    TRUNCATE TABLE feed_like;
    TRUNCATE TABLE feed_image;
    TRUNCATE TABLE feed;
    TRUNCATE TABLE user_schedule;
    TRUNCATE TABLE schedule;
    TRUNCATE TABLE user_club;
    TRUNCATE TABLE club;
    TRUNCATE TABLE user_interest;
    TRUNCATE TABLE notification;
    DELETE FROM `user` WHERE kakao_id >= 10000000;
    SET FOREIGN_KEY_CHECKS = 1;
    SELECT 'All load test data cleaned up' AS status;
END //
DELIMITER ;

-- ============================================
-- 실행 가이드
-- ============================================
--
-- ■ 전체 한방 실행 (약 30-60분):
--   CALL setup_all_load_test_data();
--
-- ■ 기존 유저/알림만 있는 상태에서 추가 도메인만:
--   CALL insert_test_user_interests(10000);
--   CALL insert_test_clubs(10000);
--   CALL insert_test_user_clubs(50, 10000, 10000);
--   CALL insert_test_schedules(5000, 10000, 10000);
--   CALL insert_test_feeds(100000, 10000, 10000);
--   CALL insert_test_feed_likes(1000000, 100000, 10000);
--   CALL insert_test_feed_comments(500000, 100000, 10000);
--   CALL insert_test_chat_rooms(1000, 10, 10000, 10000);
--   CALL insert_test_messages(1000000, 1000, 10000);
--   CALL insert_test_wallets(10000);
--
-- ■ 기존 알림 1천만건 추가 (별도):
--   CALL insert_test_notifications(10000000, 1, 10000);
--
-- ■ 통계 확인:
--   CALL show_test_data_stats();
--
-- ■ 전체 초기화:
--   CALL cleanup_all_load_test_data();
--
-- ============================================
-- 데이터 규모 요약
-- ============================================
-- | 테이블          | 건수        |
-- |-----------------|-------------|
-- | user            | 10,000      |
-- | interest        | 8           |
-- | user_interest   | ~20,000     |
-- | club            | 10,000      |
-- | user_club       | 500,000     |
-- | schedule        | 5,000       |
-- | feed            | 100,000     |
-- | feed_image      | 300,000     |
-- | feed_like       | ~1,000,000  |
-- | feed_comment    | 500,000     |
-- | chat_room       | 1,000       |
-- | user_chat_room  | 10,000      |
-- | message         | 1,000,000   |
-- | wallet          | 10,000      |
-- | notification    | 10,000,000  |

-- ============================================
-- 20. 결제 부하 테스트 전용 경량 시딩
-- ============================================
-- User + Wallet만 생성 (다른 도메인 데이터 불필요)
-- k6 JWT: kakaoId = 10000000 + userId, sub = userId
-- 소요 시간: ~10초
-- ============================================
DROP PROCEDURE IF EXISTS setup_payment_load_test_data;

DELIMITER //
CREATE PROCEDURE setup_payment_load_test_data()
BEGIN
    DECLARE start_time DATETIME DEFAULT NOW();

    SELECT '=== Payment Load Test Data Setup ===' AS status;

    -- 1. 테스트 유저 10,000명
    SELECT '>>> 1/2: Users 10,000...' AS step;
    CALL insert_test_users(10000);

    -- 2. 지갑 10,000개 (초기 잔액 100,000)
    SELECT '>>> 2/2: Wallets 10,000...' AS step;
    CALL insert_test_wallets(10000);

    SELECT CONCAT('=== Payment data ready! Duration: ',
        TIMESTAMPDIFF(SECOND, start_time, NOW()), 's ===') AS status;

    SELECT 'Users' AS entity, COUNT(*) AS count FROM `user`
    UNION ALL SELECT 'Wallets', COUNT(*) FROM wallet;
END //
DELIMITER ;
-- |-----------------|-------------|
-- | 합계            | ~12,355,000 |
--
-- ============================================
-- 성능 최적화 팁
-- ============================================
--
-- 실행 전 (더 빠른 삽입):
-- SET GLOBAL innodb_flush_log_at_trx_commit = 2;
-- SET GLOBAL sync_binlog = 0;
--
-- 실행 후 복원:
-- SET GLOBAL innodb_flush_log_at_trx_commit = 1;
-- SET GLOBAL sync_binlog = 1;
