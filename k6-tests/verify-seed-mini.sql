-- =============================================================
-- 로컬 검증용 미니 시드 (1x 스케일)
-- =============================================================
-- 목적: 100x 시드와 동일한 패턴/수식을 사용하되 1/100 규모로 투입
--       로드테스트 스크립트가 기대하는 ID가 실제 존재하는지 검증
--
-- 규모:
--   user:            1,000
--   club:            500
--   feed:            50,000
--   feed_comment:    150,000
--   feed_like:       100,000
--   feed_image:      100,000
--   schedule:        1,000
--   user_schedule:   5,000
--   chat_room:       250
--   user_chat_room:  1,250
--   message:         50,000
--   wallet:          1,000
--   settlement:      250 (schedule_id 5000000~5000249)
--   user_settlement: 2,500
--   notification:    100,000
--   fcm_token:       1,000
-- =============================================================

SET @START_TIME = NOW();
SELECT '=== 미니 시드 시작 (1x) ===' AS '';

SET FOREIGN_KEY_CHECKS = 0;
SET UNIQUE_CHECKS = 0;
SET autocommit = 0;
SET SESSION cte_max_recursion_depth = 200000;

-- 헬퍼 테이블 (일반 테이블 — MySQL은 TEMP 테이블 자기조인 불가)
DROP TABLE IF EXISTS _digits;
CREATE TABLE _digits (d INT NOT NULL) ENGINE=MEMORY;
INSERT INTO _digits VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);

DROP TABLE IF EXISTS _seq1k;
CREATE TABLE _seq1k (n INT NOT NULL, PRIMARY KEY(n)) ENGINE=MEMORY;
INSERT INTO _seq1k
SELECT d3.d*100 + d2.d*10 + d1.d
FROM _digits d1, _digits d2, _digits d3;

DROP TABLE IF EXISTS _seq100k;
CREATE TABLE _seq100k (n INT NOT NULL, PRIMARY KEY(n)) ENGINE=MEMORY;
INSERT INTO _seq100k
SELECT d5.d*10000 + d4.d*1000 + d3.d*100 + d2.d*10 + d1.d
FROM _digits d1, _digits d2, _digits d3, _digits d4, _digits d5;

SELECT CONCAT('  _seq1k: ', COUNT(*)) AS '' FROM _seq1k;
SELECT CONCAT('  _seq100k: ', COUNT(*)) AS '' FROM _seq100k;

-- ═══ 테이블 초기화 ═══
TRUNCATE TABLE fcm_token;
TRUNCATE TABLE notification;
TRUNCATE TABLE user_settlement;
TRUNCATE TABLE settlement;
TRUNCATE TABLE outbox_event;
TRUNCATE TABLE payment;
TRUNCATE TABLE wallet;
TRUNCATE TABLE message;
TRUNCATE TABLE user_chat_room;
TRUNCATE TABLE chat_room;
TRUNCATE TABLE user_schedule;
TRUNCATE TABLE schedule;
TRUNCATE TABLE feed_image;
TRUNCATE TABLE feed_like;
TRUNCATE TABLE feed_comment;
TRUNCATE TABLE feed;
TRUNCATE TABLE user_club;
TRUNCATE TABLE user_interest;
TRUNCATE TABLE interest;
TRUNCATE TABLE `user`;
COMMIT;
SELECT '  테이블 초기화 완료' AS '';

-- ═══ 1) 유저 1,000명 ═══
SELECT '--- [1] 유저 1,000명 ---' AS '';
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
    NOW() - INTERVAL (1000 - s.n) MINUTE, NOW()
FROM _seq1k s
ON DUPLICATE KEY UPDATE nickname = VALUES(nickname), modified_at = NOW();
COMMIT;
SELECT CONCAT('  유저: ', COUNT(*)) AS '' FROM `user`;

-- ═══ 2) 관심사 8개 ═══
SELECT '--- [2] 관심사 ---' AS '';
INSERT IGNORE INTO interest (interest_id, category, created_at, modified_at)
VALUES
    (1,'CULTURE',NOW(),NOW()), (2,'EXERCISE',NOW(),NOW()),
    (3,'TRAVEL',NOW(),NOW()),  (4,'MUSIC',NOW(),NOW()),
    (5,'CRAFT',NOW(),NOW()),   (6,'SOCIAL',NOW(),NOW()),
    (7,'LANGUAGE',NOW(),NOW()), (8,'FINANCE',NOW(),NOW());
COMMIT;

INSERT IGNORE INTO user_interest (user_id, interest_id, created_at, modified_at)
SELECT user_id, ((user_id % 8) + 1), NOW(), NOW() FROM `user`;
INSERT IGNORE INTO user_interest (user_id, interest_id, created_at, modified_at)
SELECT user_id, (((user_id + 3) % 8) + 1), NOW(), NOW() FROM `user`;
COMMIT;

-- ═══ 3) 클럽 500개 ═══
SELECT '--- [3] 클럽 500개 ---' AS '';
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
FROM (SELECT n FROM _seq1k WHERE n < 500) s;
COMMIT;

SET @min_club = (SELECT MIN(club_id) FROM club);
SELECT CONCAT('  클럽: ', COUNT(*), ' (min_club=', @min_club, ')') AS '' FROM club;

-- ═══ 4) 유저-클럽 가입 ═══
SELECT '--- [4] 유저-클럽 가입 ---' AS '';
INSERT IGNORE INTO user_club (user_id, club_id, role, created_at, modified_at)
SELECT u.user_id, @min_club + (u.user_id % 500), 'MEMBER', NOW(), NOW()
FROM `user` u;
COMMIT;
INSERT IGNORE INTO user_club (user_id, club_id, role, created_at, modified_at)
SELECT u.user_id, @min_club + ((u.user_id + 166) % 500), 'MEMBER', NOW(), NOW()
FROM `user` u;
COMMIT;
INSERT IGNORE INTO user_club (user_id, club_id, role, created_at, modified_at)
SELECT u.user_id, @min_club + ((u.user_id + 333) % 500), 'MEMBER', NOW(), NOW()
FROM `user` u;
COMMIT;

UPDATE user_club uc
JOIN (SELECT MIN(user_club_id) AS first_id FROM user_club GROUP BY club_id) t
ON uc.user_club_id = t.first_id SET uc.role = 'LEADER';
COMMIT;
SELECT CONCAT('  유저-클럽: ', COUNT(*)) AS '' FROM user_club;

-- ═══ 5) 피드 50,000개 ═══
SELECT '--- [5] 피드 50,000개 ---' AS '';
-- 패턴: club_id = @min_club + (seq % 500), user_id = (seq % 1000) + 1

-- 40000 ORIGINAL (앞 40000)
INSERT INTO feed (content, club_id, user_id, type, parent_feed_id, root_feed_id,
                 like_count, comment_count, deleted, created_at, modified_at)
SELECT
    CONCAT('테스트 피드 #', s.n),
    @min_club + (s.n % 500),
    (s.n % 1000) + 1,
    'ORIGINAL', NULL, NULL,
    FLOOR(RAND() * 30), FLOOR(RAND() * 15), FALSE,
    NOW() - INTERVAL (50000 - s.n) SECOND, NOW()
FROM (SELECT n FROM _seq100k WHERE n < 40000) s;
COMMIT;

-- 10000 REFEED (뒤 10000)
SET @min_feed = (SELECT MIN(feed_id) FROM feed);
INSERT INTO feed (content, club_id, user_id, type, parent_feed_id, root_feed_id,
                 like_count, comment_count, deleted, created_at, modified_at)
SELECT
    CONCAT('리피드 #', 40000 + s.n),
    @min_club + ((40000 + s.n) % 500),
    ((40000 + s.n) % 1000) + 1,
    'REFEED',
    @min_feed + ((40000 + s.n) % 40000),
    @min_feed + ((40000 + s.n) % 40000),
    FLOOR(RAND() * 5), 0, FALSE,
    NOW() - INTERVAL (10000 - s.n) SECOND, NOW()
FROM (SELECT n FROM _seq100k WHERE n < 10000) s;
COMMIT;

SET @min_feed = (SELECT MIN(feed_id) FROM feed);
SELECT CONCAT('  피드: ', COUNT(*), ' (min_feed=', @min_feed, ')') AS '' FROM feed;
SELECT CONCAT('    ORIGINAL: ', COUNT(*)) AS '' FROM feed WHERE type = 'ORIGINAL';
SELECT CONCAT('    REFEED: ', COUNT(*)) AS '' FROM feed WHERE type = 'REFEED';

-- ═══ 6) 피드 댓글 150,000개 ═══
SELECT '--- [6] 피드 댓글 150,000개 ---' AS '';
INSERT INTO feed_comment (content, feed_id, user_id, created_at, modified_at)
SELECT
    CONCAT('댓글 #', batch.b * 100000 + s.n),
    @min_feed + ((batch.b * 100000 + s.n) % 50000),
    ((batch.b * 100000 + s.n) % 1000) + 1,
    NOW() - INTERVAL (150000 - (batch.b * 100000 + s.n)) SECOND, NOW()
FROM _seq100k s
CROSS JOIN (SELECT 0 AS b UNION ALL SELECT 1) batch
WHERE batch.b * 100000 + s.n < 150000;
COMMIT;
SELECT CONCAT('  댓글: ', COUNT(*)) AS '' FROM feed_comment;

-- ═══ 7) 피드 좋아요 100,000개 ═══
SELECT '--- [7] 피드 좋아요 100,000개 ---' AS '';
INSERT IGNORE INTO feed_like (feed_id, user_id, created_at, modified_at)
SELECT
    @min_feed + (s.n % 50000),
    (s.n DIV 50000 * 500 + s.n % 500) % 1000 + 1,
    NOW() - INTERVAL (100000 - s.n) SECOND, NOW()
FROM _seq100k s;
COMMIT;
SELECT CONCAT('  좋아요: ', COUNT(*)) AS '' FROM feed_like;

-- ═══ 8) 피드 이미지 100,000개 ═══
SELECT '--- [8] 피드 이미지 100,000개 ---' AS '';
INSERT INTO feed_image (feed_image, feed_id, created_at, modified_at)
SELECT
    CONCAT('https://d1c3fg3ti7m8cn.cloudfront.net/feed/', @min_feed + (s.n DIV 2), '/img', (s.n % 2) + 1, '.jpg'),
    @min_feed + (s.n DIV 2),
    NOW(), NOW()
FROM _seq100k s;
COMMIT;
SELECT CONCAT('  이미지: ', COUNT(*)) AS '' FROM feed_image;

-- ═══ 9) 스케줄 1,000개 + 유저스케줄 5,000개 ═══
SELECT '--- [9] 스케줄 1,000개 ---' AS '';
INSERT INTO schedule (schedule_time, name, location, cost, user_limit, status, club_id, created_at, modified_at)
SELECT
    NOW() + INTERVAL (s.n - 500) HOUR,
    CONCAT('모임일정 ', s.n),
    CONCAT('장소 ', (s.n % 10) + 1),
    (FLOOR(RAND() * 10) + 1) * 1000, 20,
    ELT((s.n % 4) + 1, 'READY','ENDED','SETTLING','CLOSED'),
    @min_club + (s.n % 500),
    NOW() - INTERVAL (1000 - s.n) MINUTE, NOW()
FROM _seq1k s;
COMMIT;

SET @min_schedule = (SELECT MIN(schedule_id) FROM schedule);
SELECT CONCAT('  스케줄: ', COUNT(*), ' (min_schedule=', @min_schedule, ')') AS '' FROM schedule;

-- 유저 스케줄 (스케줄당 5명 = 5,000)
INSERT IGNORE INTO user_schedule (user_id, schedule_id, role, created_at, modified_at)
SELECT ((s.n * 5 + 0) % 1000) + 1, @min_schedule + s.n, 'LEADER', NOW(), NOW() FROM _seq1k s;
INSERT IGNORE INTO user_schedule (user_id, schedule_id, role, created_at, modified_at)
SELECT ((s.n * 5 + 1) % 1000) + 1, @min_schedule + s.n, 'MEMBER', NOW(), NOW() FROM _seq1k s;
INSERT IGNORE INTO user_schedule (user_id, schedule_id, role, created_at, modified_at)
SELECT ((s.n * 5 + 2) % 1000) + 1, @min_schedule + s.n, 'MEMBER', NOW(), NOW() FROM _seq1k s;
INSERT IGNORE INTO user_schedule (user_id, schedule_id, role, created_at, modified_at)
SELECT ((s.n * 5 + 3) % 1000) + 1, @min_schedule + s.n, 'MEMBER', NOW(), NOW() FROM _seq1k s;
INSERT IGNORE INTO user_schedule (user_id, schedule_id, role, created_at, modified_at)
SELECT ((s.n * 5 + 4) % 1000) + 1, @min_schedule + s.n, 'MEMBER', NOW(), NOW() FROM _seq1k s;
COMMIT;
SELECT CONCAT('  유저스케줄: ', COUNT(*)) AS '' FROM user_schedule;

-- ═══ 10) 채팅방 250개 + 참여자 1,250 + 메시지 50,000 ═══
SELECT '--- [10] 채팅방 250개 ---' AS '';
INSERT INTO chat_room (club_id, schedule_id, type, created_at, modified_at)
SELECT
    @min_club + (s.n % 500),
    IF(s.n % 3 = 0, @min_schedule + (s.n % 1000), NULL),
    IF(s.n % 3 = 0, 'SCHEDULE', 'CLUB'),
    NOW() - INTERVAL (250 - s.n) HOUR, NOW()
FROM (SELECT n FROM _seq1k WHERE n < 250) s;
COMMIT;

SET @min_chatroom = (SELECT MIN(chat_room_id) FROM chat_room);

-- 참여자 (방당 5명 = 1,250)
INSERT IGNORE INTO user_chat_room (chat_room_id, user_id, role, created_at, modified_at)
SELECT @min_chatroom + s.n, ((s.n * 5 + 0) % 1000) + 1, 'LEADER', NOW(), NOW() FROM (SELECT n FROM _seq1k WHERE n < 250) s;
INSERT IGNORE INTO user_chat_room (chat_room_id, user_id, role, created_at, modified_at)
SELECT @min_chatroom + s.n, ((s.n * 5 + 1) % 1000) + 1, 'MEMBER', NOW(), NOW() FROM (SELECT n FROM _seq1k WHERE n < 250) s;
INSERT IGNORE INTO user_chat_room (chat_room_id, user_id, role, created_at, modified_at)
SELECT @min_chatroom + s.n, ((s.n * 5 + 2) % 1000) + 1, 'MEMBER', NOW(), NOW() FROM (SELECT n FROM _seq1k WHERE n < 250) s;
INSERT IGNORE INTO user_chat_room (chat_room_id, user_id, role, created_at, modified_at)
SELECT @min_chatroom + s.n, ((s.n * 5 + 3) % 1000) + 1, 'MEMBER', NOW(), NOW() FROM (SELECT n FROM _seq1k WHERE n < 250) s;
INSERT IGNORE INTO user_chat_room (chat_room_id, user_id, role, created_at, modified_at)
SELECT @min_chatroom + s.n, ((s.n * 5 + 4) % 1000) + 1, 'MEMBER', NOW(), NOW() FROM (SELECT n FROM _seq1k WHERE n < 250) s;
COMMIT;

-- 메시지 50,000개
SELECT '--- 채팅 메시지 50,000건 ---' AS '';
INSERT INTO message (chat_room_id, user_id, text, sent_at, deleted, created_at, modified_at)
SELECT
    @min_chatroom + (s.n % 250),
    (s.n % 1000) + 1,
    CONCAT('채팅 메시지 #', s.n, ' - 안녕하세요!'),
    NOW() - INTERVAL (50000 - s.n) SECOND, FALSE,
    NOW() - INTERVAL (50000 - s.n) SECOND, NOW()
FROM (SELECT n FROM _seq100k WHERE n < 50000) s;
COMMIT;

SELECT CONCAT('  채팅방: ', COUNT(*)) AS '' FROM chat_room;
SELECT CONCAT('  참여자: ', COUNT(*)) AS '' FROM user_chat_room;
SELECT CONCAT('  메시지: ', COUNT(*)) AS '' FROM message;

-- ═══ 11) 지갑 1,000개 ═══
SELECT '--- [11] 지갑 1,000개 ---' AS '';
INSERT INTO wallet (user_id, posted_balance, pending_out, created_at, modified_at)
SELECT u.user_id, 100000, 0, NOW(), NOW()
FROM `user` u
WHERE NOT EXISTS (SELECT 1 FROM wallet w WHERE w.user_id = u.user_id)
ON DUPLICATE KEY UPDATE posted_balance = 100000, pending_out = 0, modified_at = NOW();
COMMIT;
SELECT CONCAT('  지갑: ', COUNT(*)) AS '' FROM wallet;

-- ═══ 12) 정산 250건 (schedule_id 5000000~5000249) ═══
SELECT '--- [12] 정산 250건 ---' AS '';

DELETE FROM user_settlement WHERE settlement_id IN (
    SELECT settlement_id FROM settlement WHERE schedule_id BETWEEN 5000000 AND 5000249
);
DELETE FROM settlement WHERE schedule_id BETWEEN 5000000 AND 5000249;
DELETE FROM schedule WHERE schedule_id BETWEEN 5000000 AND 5000249;
COMMIT;

INSERT INTO schedule (schedule_id, created_at, modified_at, cost, location, name, status, schedule_time, user_limit, club_id)
SELECT
    5000000 + (s.n), NOW(), NOW(), 1000, 'LoadTest Location',
    CONCAT('정산테스트 스케줄 ', s.n), 'ENDED',
    DATE_SUB(NOW(), INTERVAL 1 DAY), 20, @min_club
FROM (SELECT n FROM _seq1k WHERE n < 250) s
ON DUPLICATE KEY UPDATE status = 'ENDED', modified_at = NOW();
COMMIT;

INSERT INTO settlement (created_at, modified_at, completed_time, schedule_id, sum, total_status, user_id)
SELECT NOW(), NOW(), NULL, 5000000 + s.n, 0, 'HOLDING', 1
FROM (SELECT n FROM _seq1k WHERE n < 250) s;
COMMIT;

INSERT INTO user_settlement (created_at, modified_at, completed_time, status, settlement_id, user_id)
SELECT NOW(), NOW(), NULL, 'HOLD_ACTIVE', s.settlement_id, p.user_id
FROM settlement s
CROSS JOIN (SELECT user_id FROM `user` WHERE user_id BETWEEN 2 AND 11) p
WHERE s.schedule_id BETWEEN 5000000 AND 5000249 AND s.total_status = 'HOLDING';
COMMIT;

SELECT CONCAT('  정산 스케줄: ', COUNT(*)) AS '' FROM schedule WHERE schedule_id BETWEEN 5000000 AND 5000249;
SELECT CONCAT('  정산: ', COUNT(*)) AS '' FROM settlement WHERE schedule_id BETWEEN 5000000 AND 5000249;
SELECT CONCAT('  유저정산: ', COUNT(*)) AS '' FROM user_settlement us
JOIN settlement s ON us.settlement_id = s.settlement_id WHERE s.schedule_id BETWEEN 5000000 AND 5000249;

-- ═══ 13) 알림 100,000건 ═══
SELECT '--- [13] 알림 100,000건 ---' AS '';
INSERT INTO notification (content, is_read, type, user_id, sse_sent, created_at, modified_at)
SELECT
    CONCAT('알림 #', s.n, ' - ', ELT((s.n % 5) + 1, 'CHAT','COMMENT','LIKE','REFEED','SETTLEMENT'), ' 관련 알림입니다.'),
    IF(RAND() < 0.3, TRUE, FALSE),
    ELT((s.n % 5) + 1, 'CHAT','COMMENT','LIKE','REFEED','SETTLEMENT'),
    (s.n % 1000) + 1,
    IF(RAND() < 0.7, TRUE, FALSE),
    NOW() - INTERVAL (100000 - s.n) SECOND, NOW()
FROM _seq100k s;
COMMIT;
SELECT CONCAT('  알림: ', COUNT(*)) AS '' FROM notification;

-- ═══ 14) FCM 토큰 1,000개 ═══
SELECT '--- [14] FCM 토큰 1,000개 ---' AS '';
INSERT INTO fcm_token (user_id, token, device_type, created_at, modified_at)
SELECT
    u.user_id,
    CONCAT('fcm-token-loadtest-', u.user_id, '-', UUID()),
    IF(u.user_id % 2 = 0, 'ANDROID', 'IOS'),
    NOW(), NOW()
FROM `user` u
ON DUPLICATE KEY UPDATE token = VALUES(token), modified_at = NOW();
COMMIT;
SELECT CONCAT('  FCM 토큰: ', COUNT(*)) AS '' FROM fcm_token;

-- ═══ 카운트 동기화 ═══
SELECT '--- 카운트 동기화 ---' AS '';
UPDATE feed f SET
    like_count = (SELECT COUNT(*) FROM feed_like fl WHERE fl.feed_id = f.feed_id),
    comment_count = (SELECT COUNT(*) FROM feed_comment fc WHERE fc.feed_id = f.feed_id)
WHERE f.feed_id BETWEEN @min_feed AND @min_feed + 49999;
COMMIT;

UPDATE club c SET member_count = (SELECT COUNT(*) FROM user_club uc WHERE uc.club_id = c.club_id);
COMMIT;

-- ═══ 정리 ═══
DROP TABLE IF EXISTS _seq100k;
DROP TABLE IF EXISTS _seq1k;
DROP TABLE IF EXISTS _digits;

SET FOREIGN_KEY_CHECKS = 1;
SET UNIQUE_CHECKS = 1;
SET autocommit = 1;

-- ═══ 최종 검증 ═══
SELECT '========================================' AS '';
SELECT '=== 미니 시드 최종 결과 ===' AS '';
SELECT '========================================' AS '';
SELECT CONCAT('user:            ', (SELECT COUNT(*) FROM `user`)) AS result;
SELECT CONCAT('club:            ', (SELECT COUNT(*) FROM club)) AS result;
SELECT CONCAT('user_club:       ', (SELECT COUNT(*) FROM user_club)) AS result;
SELECT CONCAT('feed:            ', (SELECT COUNT(*) FROM feed)) AS result;
SELECT CONCAT('  ORIGINAL:      ', (SELECT COUNT(*) FROM feed WHERE type='ORIGINAL')) AS result;
SELECT CONCAT('  REFEED:        ', (SELECT COUNT(*) FROM feed WHERE type='REFEED')) AS result;
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

-- ID 범위 검증
SELECT '--- ID 범위 검증 ---' AS '';
SELECT CONCAT('user_id range: ', MIN(user_id), ' ~ ', MAX(user_id)) AS '' FROM `user`;
SELECT CONCAT('kakao_id range: ', MIN(kakao_id), ' ~ ', MAX(kakao_id)) AS '' FROM `user`;
SELECT CONCAT('club_id range: ', MIN(club_id), ' ~ ', MAX(club_id)) AS '' FROM club;
SELECT CONCAT('feed_id range: ', MIN(feed_id), ' ~ ', MAX(feed_id)) AS '' FROM feed;
SELECT CONCAT('schedule_id range: ', MIN(schedule_id), ' ~ ', MAX(schedule_id)) AS '' FROM schedule;
SELECT CONCAT('settlement schedule_id range: ', MIN(schedule_id), ' ~ ', MAX(schedule_id)) AS '' FROM schedule WHERE schedule_id >= 5000000;
SELECT CONCAT('chat_room_id range: ', MIN(chat_room_id), ' ~ ', MAX(chat_room_id)) AS '' FROM chat_room;

-- 핵심 패턴 검증: 유저1의 데이터 존재 확인
SELECT '--- 유저1 데이터 존재 확인 ---' AS '';
SELECT CONCAT('user_id=1 존재: ', IF(COUNT(*) > 0, 'OK', 'FAIL')) AS '' FROM `user` WHERE user_id = 1;
SELECT CONCAT('user_id=1 클럽 가입: ', COUNT(*), '개') AS '' FROM user_club WHERE user_id = 1;
SELECT CONCAT('user_id=1 피드: ', COUNT(*), '개') AS '' FROM feed WHERE user_id = 1;
SELECT CONCAT('user_id=1 알림: ', COUNT(*), '개') AS '' FROM notification WHERE user_id = 1;
SELECT CONCAT('user_id=1 지갑: ', IF(COUNT(*) > 0, 'OK', 'FAIL')) AS '' FROM wallet WHERE user_id = 1;
SELECT CONCAT('user_id=1 FCM: ', IF(COUNT(*) > 0, 'OK', 'FAIL')) AS '' FROM fcm_token WHERE user_id = 1;

-- 정산 패턴 검증
SELECT '--- 정산 패턴 검증 ---' AS '';
SELECT CONCAT('schedule_id=5000000 존재: ', IF(COUNT(*) > 0, 'OK', 'FAIL')) AS '' FROM schedule WHERE schedule_id = 5000000;
SELECT CONCAT('schedule_id=5000000 정산: ', IF(COUNT(*) > 0, 'OK', 'FAIL')) AS '' FROM settlement WHERE schedule_id = 5000000;

SELECT CONCAT('소요시간: ', TIMEDIFF(NOW(), @START_TIME)) AS '';
SELECT '=== 미니 시드 완료 ===' AS '';
