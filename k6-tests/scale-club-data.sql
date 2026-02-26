-- =============================================================
-- scale-club-data.sql
-- Club 도메인 대규모 테스트 데이터 시딩 (k6 부하 테스트용)
-- =============================================================
--
-- 목표:
--   - user       : 2,000명  (userId 1~2000, kakaoId 10000001~10002000)
--   - interest   : 8건      (기존 카테고리)
--   - club       : 50,000건 (userLimit 10~100, 다양한 city/district/interest)
--   - user_club  : 사용자당 5~15개 클럽 멤버십 (약 20,000 건)
--
-- 특징:
--   - INSERT IGNORE / ON DUPLICATE KEY 사용 (멱등성)
--   - 벌크 INSERT (1,000건 단위 커밋)
--   - club.member_count 를 실제 user_club 기준으로 보정
--   - 기존 init-test-data.sql 프로시저와 독립 실행 가능
--
-- 실행 방법:
--   mysql -h 127.0.0.1 -P 3340 -u root -p onlyone < k6-tests/scale-club-data.sql
--
-- =============================================================

USE onlyone;

-- ============================================
-- 0. 성능 최적화 (삽입 속도 향상)
-- ============================================
SET @old_autocommit         = @@autocommit;
SET @old_unique_checks      = @@unique_checks;
SET @old_foreign_key_checks = @@foreign_key_checks;

SET autocommit         = 0;
SET unique_checks      = 0;
SET foreign_key_checks = 0;

-- ============================================
-- 1. Interest 기본 데이터 (8개 카테고리)
-- ============================================
INSERT IGNORE INTO interest (interest_id, category, created_at, modified_at) VALUES
(1, 'CULTURE',  NOW(), NOW()),
(2, 'EXERCISE', NOW(), NOW()),
(3, 'TRAVEL',   NOW(), NOW()),
(4, 'MUSIC',    NOW(), NOW()),
(5, 'CRAFT',    NOW(), NOW()),
(6, 'SOCIAL',   NOW(), NOW()),
(7, 'LANGUAGE', NOW(), NOW()),
(8, 'FINANCE',  NOW(), NOW());

COMMIT;

SELECT 'Interest 8건 확인 완료' AS step;

-- ============================================
-- 2. User 2,000명 생성
--    kakaoId = 10000000 + userId
-- ============================================
DROP PROCEDURE IF EXISTS scale_insert_users;

DELIMITER //
CREATE PROCEDURE scale_insert_users()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE batch INT DEFAULT 0;

    START TRANSACTION;

    WHILE i <= 2000 DO
        INSERT INTO `user` (
            kakao_id, nickname, birth, gender, status, role,
            city, district, created_at, modified_at
        ) VALUES (
            10000000 + i,
            CONCAT('loaduser', i),
            DATE_SUB(CURDATE(), INTERVAL (20 + (i % 40)) YEAR),
            IF(i % 2 = 0, 'MALE', 'FEMALE'),
            'ACTIVE',
            'ROLE_USER',
            ELT(((i - 1) % 8) + 1, '서울', '부산', '대구', '인천', '광주', '대전', '울산', '세종'),
            ELT(((i - 1) % 5) + 1, '강남구', '해운대구', '중구', '남구', '서구'),
            NOW(),
            NOW()
        ) ON DUPLICATE KEY UPDATE modified_at = NOW();

        SET batch = batch + 1;
        IF batch >= 500 THEN
            COMMIT;
            START TRANSACTION;
            SET batch = 0;
        END IF;

        SET i = i + 1;
    END WHILE;

    COMMIT;
    SELECT CONCAT('Users: 2,000 upserted (id 1~', (SELECT MAX(user_id) FROM `user`), ')') AS result;
END //
DELIMITER ;

CALL scale_insert_users();
DROP PROCEDURE IF EXISTS scale_insert_users;

-- ============================================
-- 3. Club 50,000건 생성
--    - user_limit: 10 ~ 100 (다양)
--    - interest_id: 1~8 순환
--    - city/district: 8개 도시, 5개 구 조합
-- ============================================
DROP PROCEDURE IF EXISTS scale_insert_clubs;

DELIMITER //
CREATE PROCEDURE scale_insert_clubs()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE batch INT DEFAULT 0;
    DECLARE v_city VARCHAR(20);
    DECLARE v_district VARCHAR(20);
    DECLARE v_interest_id BIGINT;
    DECLARE v_user_limit INT;

    START TRANSACTION;

    WHILE i <= 50000 DO
        SET v_interest_id = ((i - 1) % 8) + 1;
        SET v_city = ELT(((i - 1) % 8) + 1,
            '서울', '부산', '대구', '인천', '광주', '대전', '울산', '세종');
        SET v_district = ELT(((i - 1) % 5) + 1,
            '강남구', '해운대구', '중구', '남구', '서구');
        -- user_limit: 10 ~ 100 범위로 다양하게
        SET v_user_limit = 10 + (i % 91);

        INSERT INTO club (
            name, user_limit, description, club_image,
            city, district, member_count, interest_id,
            created_at, modified_at
        ) VALUES (
            CONCAT('클럽_', i),
            v_user_limit,
            CONCAT('k6 부하 테스트 클럽 #', i, ' - ', v_city, ' ', v_district),
            NULL,
            v_city,
            v_district,
            0,
            v_interest_id,
            DATE_SUB(NOW(), INTERVAL (i % 730) DAY),
            NOW()
        ) ON DUPLICATE KEY UPDATE modified_at = NOW();

        SET batch = batch + 1;
        IF batch >= 1000 THEN
            COMMIT;
            START TRANSACTION;
            SET batch = 0;
            IF i % 10000 = 0 THEN
                SELECT CONCAT('  Clubs progress: ', i, '/50000') AS progress;
            END IF;
        END IF;

        SET i = i + 1;
    END WHILE;

    COMMIT;
    SELECT CONCAT('Clubs: 50,000 upserted (max club_id=',
        (SELECT MAX(club_id) FROM club), ')') AS result;
END //
DELIMITER ;

CALL scale_insert_clubs();
DROP PROCEDURE IF EXISTS scale_insert_clubs;

-- ============================================
-- 4. UserClub 생성: 사용자당 5~15개 클럽 멤버십
--    - 결정론적 랜덤: (userId * prime + offset) % clubCount
--    - 첫 번째 클럽은 LEADER, 나머지 MEMBER
--    - INSERT IGNORE로 중복 안전
-- ============================================
DROP PROCEDURE IF EXISTS scale_insert_user_clubs;

DELIMITER //
CREATE PROCEDURE scale_insert_user_clubs()
proc_body: BEGIN
    DECLARE v_user_id INT DEFAULT 1;
    DECLARE v_max_user INT;
    DECLARE v_max_club INT;
    DECLARE v_club_count INT;           -- 이 유저가 가입할 클럽 수 (5~15)
    DECLARE v_club_id BIGINT;
    DECLARE v_role VARCHAR(10);
    DECLARE j INT;
    DECLARE batch INT DEFAULT 0;
    DECLARE total_inserted INT DEFAULT 0;
    -- 결정론적 분산을 위한 소수들
    DECLARE PRIME1 INT DEFAULT 7919;
    DECLARE PRIME2 INT DEFAULT 104729;
    DECLARE PRIME3 INT DEFAULT 15485863;

    SELECT MAX(user_id) INTO v_max_user FROM `user`;
    SELECT MAX(club_id) INTO v_max_club FROM club;

    IF v_max_user IS NULL OR v_max_club IS NULL THEN
        SELECT 'ERROR: user 또는 club 테이블이 비어있습니다' AS error;
        LEAVE proc_body;
    END IF;

    START TRANSACTION;

    WHILE v_user_id <= v_max_user AND v_user_id <= 2000 DO
        -- 사용자마다 5~15개 클럽 (결정론적)
        SET v_club_count = 5 + ((v_user_id * 7) % 11);  -- 5..15

        SET j = 0;
        WHILE j < v_club_count DO
            -- 결정론적이지만 잘 분산되는 club_id 산출
            SET v_club_id = (((v_user_id * PRIME1) + (j * PRIME2) + PRIME3) % v_max_club) + 1;

            -- 첫 번째 가입 클럽은 LEADER, 나머지 MEMBER
            SET v_role = IF(j = 0, 'LEADER', 'MEMBER');

            INSERT IGNORE INTO user_club (
                user_id, club_id, role, created_at, modified_at
            ) VALUES (
                v_user_id, v_club_id, v_role, NOW(), NOW()
            );

            SET total_inserted = total_inserted + 1;
            SET batch = batch + 1;

            IF batch >= 2000 THEN
                COMMIT;
                START TRANSACTION;
                SET batch = 0;
            END IF;

            SET j = j + 1;
        END WHILE;

        IF v_user_id % 500 = 0 THEN
            COMMIT;
            START TRANSACTION;
            SET batch = 0;
            SELECT CONCAT('  UserClubs progress: user ', v_user_id, '/2000, total ~', total_inserted) AS progress;
        END IF;

        SET v_user_id = v_user_id + 1;
    END WHILE;

    COMMIT;
    SELECT CONCAT('UserClubs: ~', total_inserted,
        ' attempted (actual=', (SELECT COUNT(*) FROM user_club), ')') AS result;
END //
DELIMITER ;

CALL scale_insert_user_clubs();
DROP PROCEDURE IF EXISTS scale_insert_user_clubs;

-- ============================================
-- 5. club.member_count 보정
--    실제 user_club 건수 기준으로 member_count 업데이트
-- ============================================
SELECT 'Updating club.member_count from user_club...' AS step;

UPDATE club c
    JOIN (
        SELECT club_id, COUNT(*) AS cnt
        FROM user_club
        GROUP BY club_id
    ) uc ON c.club_id = uc.club_id
SET c.member_count = uc.cnt;

COMMIT;

SELECT CONCAT('member_count 보정 완료 (',
    (SELECT COUNT(*) FROM club WHERE member_count > 0),
    ' clubs with members)') AS result;

-- ============================================
-- 6. 설정 복원
-- ============================================
SET autocommit         = @old_autocommit;
SET unique_checks      = @old_unique_checks;
SET foreign_key_checks = @old_foreign_key_checks;

-- ============================================
-- 7. 최종 통계
-- ============================================
SELECT '=== Scale Club Data - Final Stats ===' AS header;

SELECT 'Users' AS entity, COUNT(*) AS count FROM `user`
UNION ALL SELECT 'Interests', COUNT(*) FROM interest
UNION ALL SELECT 'Clubs', COUNT(*) FROM club
UNION ALL SELECT 'UserClubs', COUNT(*) FROM user_club;

SELECT
    'UserClub distribution' AS stat,
    MIN(club_cnt) AS min_clubs_per_user,
    MAX(club_cnt) AS max_clubs_per_user,
    ROUND(AVG(club_cnt), 1) AS avg_clubs_per_user
FROM (
    SELECT user_id, COUNT(*) AS club_cnt
    FROM user_club
    WHERE user_id <= 2000
    GROUP BY user_id
) sub;

SELECT
    'Club membership distribution' AS stat,
    MIN(mem_cnt) AS min_members_per_club,
    MAX(mem_cnt) AS max_members_per_club,
    ROUND(AVG(mem_cnt), 1) AS avg_members_per_club
FROM (
    SELECT club_id, COUNT(*) AS mem_cnt
    FROM user_club
    GROUP BY club_id
) sub;

SELECT
    'Club userLimit distribution' AS stat,
    MIN(user_limit) AS min_limit,
    MAX(user_limit) AS max_limit,
    ROUND(AVG(user_limit), 1) AS avg_limit
FROM club;

SELECT '=== Done ===' AS status;
