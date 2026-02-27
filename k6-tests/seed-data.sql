-- =============================================================
-- seed-data.sql
-- OnlyOne-Back k6 부하 테스트 통합 시드 데이터
-- =============================================================
--
-- 실행 방법:
--   mysql -h 127.0.0.1 -P 3340 -u root -p onlyone < k6-tests/seed-data.sql
--
-- 실행 순서 (자동):
--   Phase 1: Club 기본 데이터 (user 2K, interest 8, club 50K, user_club ~20K)
--   Phase 2: Finance 대규모 데이터 (wallet 2K, schedule 5K, settlement 5K, transaction 40K)
--   Phase 3: Search 키워드 업데이트 (club name/description 한국어 키워드 + 동의어)
--   Phase 4: 확장 데이터 (user_club 60K, user_settlement 50K, user_interest 10K)
--   Phase 5: Notification 기본 (5K건)
--
-- 선택 실행 (수동 CALL):
--   CALL seed_delivery_data();  -- SSE 전달 검증용 (~30분, 10M건)
--   CALL seed_batch_data();     -- 배치 포화 테스트용 (~30분, 10M건)
--   CALL seed_conflict_data();  -- 동시성 충돌 테스트용 (~30분, 10M건)
--
-- 예상 최종 데이터 (Phase 1~5 완료 후):
--   user:              2,000
--   interest:          8
--   club:              50,000
--   user_club:         ~60,000
--   user_interest:     ~10,000
--   wallet:            2,000
--   schedule:          5,000
--   user_schedule:     ~55,000
--   settlement:        5,000
--   user_settlement:   ~50,000
--   wallet_transaction:~40,000
--   notification:      5,000
--
-- 예상 실행 시간: ~5분 (Phase 1~5)
-- =============================================================

USE onlyone;

-- ============================================
-- 글로벌 성능 최적화
-- ============================================
SET @old_autocommit         = @@autocommit;
SET @old_unique_checks      = @@unique_checks;
SET @old_foreign_key_checks = @@foreign_key_checks;

SET autocommit         = 0;
SET unique_checks      = 0;
SET foreign_key_checks = 0;


-- #############################################################
-- Phase 1: Club 기본 데이터
-- user 2,000명, interest 8, club 50,000건, user_club ~20,000건
-- #############################################################
SELECT '========== Phase 1: Club 기본 데이터 ==========' AS phase;

-- 1-1. Interest 8개 카테고리
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
SELECT 'Interest 8건 완료' AS step;

-- 1-2. User 2,000명
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
            NOW(), NOW()
        ) ON DUPLICATE KEY UPDATE modified_at = NOW();

        SET batch = batch + 1;
        IF batch >= 500 THEN
            COMMIT; START TRANSACTION; SET batch = 0;
        END IF;
        SET i = i + 1;
    END WHILE;
    COMMIT;
    SELECT CONCAT('Users: 2,000 완료 (max_id=', (SELECT MAX(user_id) FROM `user`), ')') AS result;
END //
DELIMITER ;

CALL scale_insert_users();
DROP PROCEDURE IF EXISTS scale_insert_users;

-- 1-3. Club 50,000건
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
        SET v_city = ELT(((i - 1) % 8) + 1, '서울', '부산', '대구', '인천', '광주', '대전', '울산', '세종');
        SET v_district = ELT(((i - 1) % 5) + 1, '강남구', '해운대구', '중구', '남구', '서구');
        SET v_user_limit = 10 + (i % 91);

        INSERT INTO club (
            name, user_limit, description, club_image,
            city, district, member_count, interest_id,
            created_at, modified_at
        ) VALUES (
            CONCAT('클럽_', i), v_user_limit,
            CONCAT('k6 부하 테스트 클럽 #', i, ' - ', v_city, ' ', v_district),
            NULL, v_city, v_district, 0, v_interest_id,
            DATE_SUB(NOW(), INTERVAL (i % 730) DAY), NOW()
        ) ON DUPLICATE KEY UPDATE modified_at = NOW();

        SET batch = batch + 1;
        IF batch >= 1000 THEN
            COMMIT; START TRANSACTION; SET batch = 0;
            IF i % 10000 = 0 THEN
                SELECT CONCAT('  Clubs: ', i, '/50000') AS progress;
            END IF;
        END IF;
        SET i = i + 1;
    END WHILE;
    COMMIT;
    SELECT CONCAT('Clubs: 50,000 완료 (max_id=', (SELECT MAX(club_id) FROM club), ')') AS result;
END //
DELIMITER ;

CALL scale_insert_clubs();
DROP PROCEDURE IF EXISTS scale_insert_clubs;

-- 1-4. UserClub: 유저당 5~15개 멤버십 (~20,000건)
DROP PROCEDURE IF EXISTS scale_insert_user_clubs;

DELIMITER //
CREATE PROCEDURE scale_insert_user_clubs()
proc_body: BEGIN
    DECLARE v_user_id INT DEFAULT 1;
    DECLARE v_max_user INT;
    DECLARE v_max_club INT;
    DECLARE v_club_count INT;
    DECLARE v_club_id BIGINT;
    DECLARE v_role VARCHAR(10);
    DECLARE j INT;
    DECLARE batch INT DEFAULT 0;
    DECLARE total_inserted INT DEFAULT 0;
    DECLARE PRIME1 INT DEFAULT 7919;
    DECLARE PRIME2 INT DEFAULT 104729;
    DECLARE PRIME3 INT DEFAULT 15485863;

    SELECT MAX(user_id) INTO v_max_user FROM `user`;
    SELECT MAX(club_id) INTO v_max_club FROM club;
    IF v_max_user IS NULL OR v_max_club IS NULL THEN
        SELECT 'ERROR: user 또는 club 비어있음' AS error;
        LEAVE proc_body;
    END IF;

    START TRANSACTION;
    WHILE v_user_id <= v_max_user AND v_user_id <= 2000 DO
        SET v_club_count = 5 + ((v_user_id * 7) % 11);
        SET j = 0;
        WHILE j < v_club_count DO
            SET v_club_id = (((v_user_id * PRIME1) + (j * PRIME2) + PRIME3) % v_max_club) + 1;
            SET v_role = IF(j = 0, 'LEADER', 'MEMBER');
            INSERT IGNORE INTO user_club (user_id, club_id, role, created_at, modified_at)
            VALUES (v_user_id, v_club_id, v_role, NOW(), NOW());
            SET total_inserted = total_inserted + 1;
            SET batch = batch + 1;
            IF batch >= 2000 THEN COMMIT; START TRANSACTION; SET batch = 0; END IF;
            SET j = j + 1;
        END WHILE;
        IF v_user_id % 500 = 0 THEN
            COMMIT; START TRANSACTION; SET batch = 0;
            SELECT CONCAT('  UserClubs: user ', v_user_id, '/2000') AS progress;
        END IF;
        SET v_user_id = v_user_id + 1;
    END WHILE;
    COMMIT;
    SELECT CONCAT('UserClubs: ~', total_inserted, ' (actual=', (SELECT COUNT(*) FROM user_club), ')') AS result;
END //
DELIMITER ;

CALL scale_insert_user_clubs();
DROP PROCEDURE IF EXISTS scale_insert_user_clubs;

-- 1-5. club.member_count 보정
SELECT 'Updating club.member_count...' AS step;
UPDATE club c JOIN (
    SELECT club_id, COUNT(*) AS cnt FROM user_club GROUP BY club_id
) uc ON c.club_id = uc.club_id
SET c.member_count = uc.cnt;
COMMIT;
SELECT CONCAT('member_count 보정 완료 (', (SELECT COUNT(*) FROM club WHERE member_count > 0), ' clubs)') AS result;


-- #############################################################
-- Phase 2: Finance 대규모 데이터
-- wallet 2K, schedule 5K, settlement 5K, transaction 40K
-- #############################################################
SELECT '========== Phase 2: Finance 대규모 데이터 ==========' AS phase;

-- 2-0. 기존 정산 테스트 데이터 정리
START TRANSACTION;
DELETE FROM wallet_transaction WHERE operation_id LIKE 'seed:%';
DELETE FROM wallet_transaction WHERE payment_id IS NOT NULL AND payment_id IN (
    SELECT payment_id FROM payment WHERE toss_order_id LIKE 'order_%'
);
DELETE FROM payment WHERE toss_order_id LIKE 'order_%';
DELETE us FROM user_settlement us
    JOIN settlement s ON us.settlement_id = s.settlement_id
    JOIN schedule sc ON s.schedule_id = sc.schedule_id
    WHERE sc.name LIKE '정산테스트_%';
DELETE s FROM settlement s
    JOIN schedule sc ON s.schedule_id = sc.schedule_id
    WHERE sc.name LIKE '정산테스트_%';
DELETE usc FROM user_schedule usc
    JOIN schedule sc ON usc.schedule_id = sc.schedule_id
    WHERE sc.name LIKE '정산테스트_%';
DELETE FROM schedule WHERE name LIKE '정산테스트_%';
COMMIT;
SELECT '기존 정산 데이터 정리 완료' AS step;

-- 2-1. Wallet: 유저당 1개, 잔액 1,000,000
DROP PROCEDURE IF EXISTS finance_insert_wallets;

DELIMITER //
CREATE PROCEDURE finance_insert_wallets()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE batch INT DEFAULT 0;
    START TRANSACTION;
    WHILE i <= 2000 DO
        INSERT INTO wallet (user_id, posted_balance, pending_out, created_at, modified_at)
        VALUES (i, 1000000, 0, NOW(), NOW())
        ON DUPLICATE KEY UPDATE posted_balance = 1000000, pending_out = 0, modified_at = NOW();
        SET batch = batch + 1;
        IF batch >= 500 THEN COMMIT; START TRANSACTION; SET batch = 0; END IF;
        SET i = i + 1;
    END WHILE;
    COMMIT;
    SELECT CONCAT('Wallets: ', (SELECT COUNT(*) FROM wallet), '건') AS result;
END //
DELIMITER ;

CALL finance_insert_wallets();
DROP PROCEDURE IF EXISTS finance_insert_wallets;

-- 2-2. Schedule: club 1~50 × 100개 = 5,000건
DROP PROCEDURE IF EXISTS finance_insert_schedules;

DELIMITER //
CREATE PROCEDURE finance_insert_schedules()
BEGIN
    DECLARE v_club_id INT DEFAULT 1;
    DECLARE v_sched_idx INT;
    DECLARE batch INT DEFAULT 0;
    START TRANSACTION;
    WHILE v_club_id <= 50 DO
        SET v_sched_idx = 0;
        WHILE v_sched_idx < 100 DO
            INSERT INTO schedule (
                schedule_time, name, location, cost, user_limit,
                status, club_id, created_at, modified_at
            ) VALUES (
                DATE_SUB(NOW(), INTERVAL (v_club_id + v_sched_idx) DAY),
                CONCAT('정산테스트_', v_club_id, '_', v_sched_idx),
                CONCAT('장소_', v_club_id),
                10000 + (v_club_id * 100), 20, 'ENDED', v_club_id, NOW(), NOW()
            );
            SET batch = batch + 1;
            SET v_sched_idx = v_sched_idx + 1;
        END WHILE;
        IF batch >= 500 THEN COMMIT; START TRANSACTION; SET batch = 0; END IF;
        SET v_club_id = v_club_id + 1;
    END WHILE;
    COMMIT;
    SELECT CONCAT('Schedules: ', (SELECT COUNT(*) FROM schedule WHERE name LIKE '정산테스트_%'), '건') AS result;
END //
DELIMITER ;

CALL finance_insert_schedules();
DROP PROCEDURE IF EXISTS finance_insert_schedules;

-- 2-3. Settlement + UserSchedule + UserSettlement
DROP PROCEDURE IF EXISTS finance_insert_settlement_data;

DELIMITER //
CREATE PROCEDURE finance_insert_settlement_data()
BEGIN
    DECLARE v_schedule_id BIGINT;
    DECLARE v_club_id BIGINT;
    DECLARE v_leader_id BIGINT;
    DECLARE v_participant_id BIGINT;
    DECLARE v_settlement_id BIGINT;
    DECLARE j INT;
    DECLARE batch INT DEFAULT 0;
    DECLARE done INT DEFAULT 0;
    DECLARE cur CURSOR FOR
        SELECT schedule_id, club_id FROM schedule WHERE name LIKE '정산테스트_%' ORDER BY schedule_id;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;

    START TRANSACTION;
    OPEN cur;
    read_loop: LOOP
        FETCH cur INTO v_schedule_id, v_club_id;
        IF done THEN LEAVE read_loop; END IF;
        SET v_leader_id = ((v_club_id - 1) % 50) + 1;

        INSERT IGNORE INTO user_schedule (user_id, schedule_id, role, created_at, modified_at)
        VALUES (v_leader_id, v_schedule_id, 'LEADER', NOW(), NOW());

        INSERT INTO settlement (schedule_id, sum, total_status, user_id, created_at, modified_at)
        VALUES (v_schedule_id, 0, 'HOLDING', v_leader_id, NOW(), NOW());
        SET v_settlement_id = LAST_INSERT_ID();

        SET j = 1;
        WHILE j <= 10 DO
            SET v_participant_id = ((v_club_id * 7 + v_schedule_id * 13 + j * 31) % 2000) + 1;
            IF v_participant_id != v_leader_id THEN
                INSERT IGNORE INTO user_schedule (user_id, schedule_id, role, created_at, modified_at)
                VALUES (v_participant_id, v_schedule_id, 'MEMBER', NOW(), NOW());
                INSERT INTO user_settlement (status, settlement_id, user_id, created_at, modified_at)
                VALUES ('HOLD_ACTIVE', v_settlement_id, v_participant_id, NOW(), NOW());
            END IF;
            SET j = j + 1;
        END WHILE;

        SET batch = batch + 1;
        IF batch >= 100 THEN COMMIT; START TRANSACTION; SET batch = 0; END IF;
    END LOOP;
    CLOSE cur;
    COMMIT;
    SELECT CONCAT('Settlements: ', (SELECT COUNT(*) FROM settlement), '건') AS result;
    SELECT CONCAT('UserSettlements: ', (SELECT COUNT(*) FROM user_settlement), '건') AS result;
END //
DELIMITER ;

CALL finance_insert_settlement_data();
DROP PROCEDURE IF EXISTS finance_insert_settlement_data;

-- 2-4. WalletTransaction: 유저 1~2000 × 20건 = 40,000건
DROP PROCEDURE IF EXISTS finance_insert_wallet_transactions;

DELIMITER //
CREATE PROCEDURE finance_insert_wallet_transactions()
BEGIN
    DECLARE v_user_id INT DEFAULT 1;
    DECLARE v_wallet_id BIGINT;
    DECLARE j INT;
    DECLARE batch INT DEFAULT 0;
    START TRANSACTION;
    WHILE v_user_id <= 2000 DO
        SELECT wallet_id INTO v_wallet_id FROM wallet WHERE user_id = v_user_id LIMIT 1;
        IF v_wallet_id IS NOT NULL THEN
            SET j = 1;
            WHILE j <= 20 DO
                INSERT INTO wallet_transaction (
                    operation_id, type, amount, balance, status,
                    wallet_id, target_wallet_id, created_at, modified_at
                ) VALUES (
                    CONCAT('seed:charge:', v_user_id, ':v', j), 'CHARGE',
                    5000 + (j * 500), 1000000, 'COMPLETED',
                    v_wallet_id, v_wallet_id,
                    DATE_SUB(NOW(), INTERVAL (j * v_user_id % 365) DAY), NOW()
                ) ON DUPLICATE KEY UPDATE modified_at = NOW();
                SET j = j + 1;
            END WHILE;
        END IF;
        SET batch = batch + 1;
        IF batch >= 100 THEN COMMIT; START TRANSACTION; SET batch = 0; END IF;
        SET v_user_id = v_user_id + 1;
    END WHILE;
    COMMIT;
    SELECT CONCAT('WalletTransactions: ', (SELECT COUNT(*) FROM wallet_transaction), '건') AS result;
END //
DELIMITER ;

CALL finance_insert_wallet_transactions();
DROP PROCEDURE IF EXISTS finance_insert_wallet_transactions;


-- #############################################################
-- Phase 3: Search 키워드 업데이트
-- club 50,000건의 name/description을 한국어 키워드로 업데이트
-- #############################################################
SELECT '========== Phase 3: Search 키워드 업데이트 ==========' AS phase;

DROP PROCEDURE IF EXISTS search_update_club_names;

DELIMITER //
CREATE PROCEDURE search_update_club_names()
BEGIN
    DECLARE v_id BIGINT DEFAULT 1;
    DECLARE v_max_id BIGINT;
    DECLARE v_interest_id BIGINT;
    DECLARE v_city VARCHAR(50);
    DECLARE v_name VARCHAR(200);
    DECLARE v_desc TEXT;
    DECLARE v_idx INT;
    DECLARE batch INT DEFAULT 0;

    SELECT MAX(club_id) INTO v_max_id FROM club;
    IF v_max_id IS NULL THEN SELECT 'ERROR: club 비어있음' AS error; END IF;

    START TRANSACTION;
    WHILE v_id <= v_max_id DO
        SET v_interest_id = ((v_id - 1) % 8) + 1;
        SET v_idx = ((v_id - 1) / 8) % 100;

        CASE v_interest_id
            WHEN 1 THEN
                SET v_name = ELT((v_idx % 20) + 1,
                    '영화 감상 동아리', '독서 모임 책벌레', '뮤지컬 관람 클럽', '미술관 투어',
                    '전시회 탐방 모임', '클래식 음악 감상회', '연극 동호회', '사진 촬영 클럽',
                    '문화유산 탐방', '박물관 나들이', '오페라 감상 동아리', '영화 리뷰 모임',
                    '시네마 동호회', '포토그래피 스터디', '현대미술 감상', '전통문화 체험',
                    '독서토론 북클럽', '다큐멘터리 감상', '인디영화 모임', '문화예술 탐험대');
                SET v_desc = CONCAT(v_name, ' - ', ELT((v_idx % 10) + 1,
                    '함께 문화생활을 즐기는 모임입니다', '다양한 문화 콘텐츠를 체험해요',
                    '매주 모여서 문화활동을 합니다', '문화를 사랑하는 사람들의 모임',
                    '새로운 문화를 발견하는 즐거움', '함께하면 더 즐거운 문화생활',
                    '취미를 공유하는 문화 동아리', '주말마다 문화 나들이를 가요',
                    '일상에 문화를 더하는 모임', '감성을 채우는 문화 모임'));
            WHEN 2 THEN
                SET v_name = ELT((v_idx % 25) + 1,
                    '축구 동호회', '풋볼 클럽', '농구 모임', '배드민턴 동아리',
                    '테니스 클럽', '러닝 크루', '마라톤 동호회', '헬스 메이트',
                    '요가 클래스', '필라테스 모임', '수영 동호회', '등산 모임',
                    '하이킹 클럽', '자전거 라이딩', '볼링 동아리', '탁구 클럽',
                    '배구 동호회', '골프 모임', '클라이밍 크루', '크로스핏 클럽',
                    '복싱 동아리', '유도 모임', '검도 클럽', '주짓수 동호회',
                    '웨이트 트레이닝');
                SET v_desc = CONCAT(v_name, ' - ', ELT((v_idx % 10) + 1,
                    '함께 운동하며 건강을 지켜요', '매주 정기적으로 운동하는 모임입니다',
                    '초보부터 고수까지 함께하는 운동 동호회', '운동으로 스트레스를 풀어요',
                    '건강한 라이프스타일을 추구합니다', '재미있게 운동하고 친목도 다져요',
                    '주말마다 함께 땀 흘리는 모임', '체력 향상과 친목을 동시에',
                    '운동을 좋아하는 사람들의 커뮤니티', '건강하고 즐거운 운동 라이프'));
            WHEN 3 THEN
                SET v_name = ELT((v_idx % 20) + 1,
                    '국내 여행 동호회', '해외 배낭여행 모임', '제주도 여행 클럽', '캠핑 동아리',
                    '글램핑 모임', '맛집 탐방 여행', '사진 여행 클럽', '힐링 여행 모임',
                    '트래킹 여행 동호회', '자동차 여행 크루', '기차 여행 모임', '섬 여행 클럽',
                    '템플스테이 모임', '한달살기 동호회', '도보 여행 클럽', '유럽 여행 모임',
                    '동남아 여행 클럽', '일본 여행 동호회', '카페 투어 모임', '여행 사진 동아리');
                SET v_desc = CONCAT(v_name, ' - ', ELT((v_idx % 10) + 1,
                    '새로운 곳을 함께 탐험하는 여행 모임', '여행을 사랑하는 사람들의 커뮤니티',
                    '매달 새로운 여행지를 발견해요', '함께 떠나는 즐거운 여행',
                    '여행 경험을 공유하는 모임입니다', '국내외 다양한 여행을 함께해요',
                    '여행으로 일상을 리프레시', '새로운 문화를 체험하는 여행 동호회',
                    '자연과 함께하는 힐링 여행', '추억을 만드는 여행 모임'));
            WHEN 4 THEN
                SET v_name = ELT((v_idx % 20) + 1,
                    '밴드 동아리', '기타 동호회', '피아노 모임', '드럼 클럽',
                    '보컬 트레이닝', '작곡 동아리', '재즈 감상 모임', 'K-POP 댄스 크루',
                    '합창 동호회', '우쿨렐레 모임', '바이올린 클럽', '음악 감상 동아리',
                    'DJ 클럽', '힙합 크루', 'EDM 모임', '어쿠스틱 밴드',
                    '뮤직 프로듀싱', '인디 음악 동호회', '클래식 연주 모임', '카페 라이브');
                SET v_desc = CONCAT(v_name, ' - ', ELT((v_idx % 10) + 1,
                    '음악을 사랑하는 사람들의 모임', '함께 연주하고 노래하는 동호회',
                    '음악으로 하나 되는 커뮤니티', '다양한 장르의 음악을 즐겨요',
                    '음악적 감성을 나누는 모임', '매주 합주하며 실력을 키워요',
                    '음악이 있는 일상을 만들어요', '자유롭게 음악을 즐기는 공간',
                    '음악으로 스트레스를 해소해요', '함께 성장하는 음악 동아리'));
            WHEN 5 THEN
                SET v_name = ELT((v_idx % 20) + 1,
                    '도자기 공예 모임', '가죽 공예 클럽', '뜨개질 동아리', '목공 동호회',
                    '캘리그라피 모임', '비즈 공예 클럽', '꽃꽂이 동아리', '향초 만들기',
                    '레진 아트 모임', '자수 동호회', '종이 접기 클럽', '미니어처 공예',
                    '실버 악세서리', '천연비누 만들기', '페인팅 동아리', '일러스트 모임',
                    '수채화 클래스', '유화 동호회', '디지털 드로잉', '핸드메이드 공방');
                SET v_desc = CONCAT(v_name, ' - ', ELT((v_idx % 10) + 1,
                    '손으로 만드는 즐거움을 나눠요', '함께 만들며 창의력을 키워요',
                    '공예를 사랑하는 사람들의 모임', '세상에 하나뿐인 작품을 만들어요',
                    'DIY 공예를 즐기는 동호회', '매주 새로운 작품에 도전해요',
                    '초보도 환영하는 공예 모임', '취미 공예로 힐링하세요',
                    '핸드메이드의 매력에 빠져보세요', '함께 만들면 더 즐거운 공예'));
            WHEN 6 THEN
                SET v_name = ELT((v_idx % 20) + 1,
                    '소셜 다이닝', '와인 모임', '보드게임 클럽', '맥주 동호회',
                    '커피 모임', '독서 토론회', '영어 회화 모임', '일본어 스터디',
                    '봉사활동 동아리', '친목 모임', '네트워킹 클럽', '직장인 소모임',
                    '주말 브런치 모임', '바베큐 파티 클럽', '문화 살롱', '취미 공유 모임',
                    '동네 친구 만들기', '또래 모임', '싱글 모임', '산책 메이트');
                SET v_desc = CONCAT(v_name, ' - ', ELT((v_idx % 10) + 1,
                    '새로운 친구를 만나는 즐거움', '다양한 사람들과 교류하는 모임',
                    '함께하면 더 즐거운 시간', '편안한 분위기에서 대화를 나눠요',
                    '소통과 교류의 장', '일상을 함께 공유하는 모임입니다',
                    '취미가 같은 친구를 만들어요', '소소한 행복을 나누는 모임',
                    '새로운 인연을 만나는 공간', '즐거운 시간을 함께 보내요'));
            WHEN 7 THEN
                SET v_name = ELT((v_idx % 20) + 1,
                    '영어 회화 스터디', '일본어 공부 모임', '중국어 학습 클럽', '프랑스어 동아리',
                    '스페인어 모임', '토익 스터디', '토플 준비반', '영어 독서 클럽',
                    '비즈니스 영어', '여행 영어 모임', '원어민 대화 클럽', '한국어 교실',
                    '통번역 스터디', '언어 교환 모임', '영어 발음 클리닉', '일본어 회화',
                    '중국어 HSK 스터디', '독일어 학습 모임', '이탈리아어 클럽', '다국어 모임');
                SET v_desc = CONCAT(v_name, ' - ', ELT((v_idx % 10) + 1,
                    '함께 공부하며 실력을 키워요', '매주 정기적으로 학습하는 모임',
                    '재미있게 외국어를 배워요', '실전 회화를 연습하는 스터디',
                    '언어 실력 향상을 목표로 합니다', '다양한 언어를 배우는 모임',
                    '초보부터 고급까지 레벨별 학습', '원어민과 함께하는 회화 연습',
                    '자격증 취득을 위한 스터디', '꾸준한 학습으로 목표를 달성해요'));
            WHEN 8 THEN
                SET v_name = ELT((v_idx % 20) + 1,
                    '주식 투자 스터디', '부동산 공부 모임', '비트코인 투자 클럽', '재테크 동아리',
                    '금융 스터디', '경제 뉴스 토론', '펀드 투자 모임', '자산관리 클럽',
                    '창업 스터디', '스타트업 네트워킹', '사이드 프로젝트 모임', 'IT 개발 동아리',
                    '프로그래밍 스터디', '코딩 모임', '데이터 분석 클럽', 'AI 스터디',
                    '마케팅 동호회', '디자인 씽킹 모임', '블록체인 스터디', '암호화폐 리서치');
                SET v_desc = CONCAT(v_name, ' - ', ELT((v_idx % 10) + 1,
                    '함께 공부하며 투자 실력을 키워요', '재테크 정보를 공유하는 모임',
                    '경제적 자유를 향한 스터디', '매주 투자 전략을 토론합니다',
                    '재무 관리 능력을 향상시켜요', '다양한 투자 방법을 연구합니다',
                    '실전 투자 경험을 나누는 모임', '함께 성장하는 재테크 커뮤니티',
                    '최신 금융 트렌드를 학습해요', '스마트한 자산관리를 배워요'));
        END CASE;

        SET v_city = ELT(((v_id - 1) % 8) + 1, '서울', '부산', '대구', '인천', '광주', '대전', '울산', '세종');
        IF v_idx >= 50 THEN SET v_name = CONCAT(v_city, ' ', v_name); END IF;

        UPDATE club SET name = v_name, description = v_desc WHERE club_id = v_id;

        SET batch = batch + 1;
        IF batch >= 1000 THEN
            COMMIT; START TRANSACTION; SET batch = 0;
            IF v_id % 10000 = 0 THEN SELECT CONCAT('  Search update: ', v_id, '/', v_max_id) AS progress; END IF;
        END IF;
        SET v_id = v_id + 1;
    END WHILE;
    COMMIT;
    SELECT CONCAT('Club name/description 업데이트: ', v_max_id, '건') AS result;
END //
DELIMITER ;

CALL search_update_club_names();
DROP PROCEDURE IF EXISTS search_update_club_names;

-- 3-2. 동의어 테스트용 특수 클럽 (club_id 1~16)
START TRANSACTION;
UPDATE club SET name = '강남 축구 동호회 얼리버드', description = '매주 일요일 아침 축구를 즐기는 강남 지역 동호회입니다. 축구를 사랑하는 분들 환영합니다.' WHERE club_id = 1;
UPDATE club SET name = '부산 풋볼 클럽 시사이드', description = '해운대 해변에서 풋볼을 즐기는 부산 동호회. soccer 매니아들의 모임.' WHERE club_id = 2;
UPDATE club SET name = '서울 등산 모임 한라산', description = '매주 주말 등산을 가는 서울 기반 산악회입니다. 등산화 신고 출발!' WHERE club_id = 3;
UPDATE club SET name = '인천 하이킹 클럽 자유로', description = '하이킹과 트래킹을 좋아하는 인천 hiking 모임. 산행의 즐거움을 함께해요.' WHERE club_id = 4;
UPDATE club SET name = '대구 요가 필라테스 힐링', description = '요가와 필라테스를 함께 즐기는 대구 동호회. yoga 초보 환영!' WHERE club_id = 5;
UPDATE club SET name = '판교 프로그래밍 스터디', description = 'IT 개발자들의 프로그래밍 스터디. programming과 개발 역량을 함께 키워요.' WHERE club_id = 6;
UPDATE club SET name = '강남 IT 개발 동아리', description = '개발자 커뮤니티. 프로그래밍 언어 학습과 프로젝트를 함께 진행합니다.' WHERE club_id = 7;
UPDATE club SET name = '서울 주식 투자 스터디', description = '주식과 재테크를 공부하는 투자 모임. investment 전략을 함께 연구합니다.' WHERE club_id = 8;
UPDATE club SET name = '온라인 게임 동호회', description = '게임을 좋아하는 게이머들의 모임. gaming 마니아 환영합니다!' WHERE club_id = 9;
UPDATE club SET name = '카페 투어 모임 라떼는', description = '맛있는 커피를 찾아다니는 카페 투어 동호회. cafe 탐방을 함께해요.' WHERE club_id = 10;
UPDATE club SET name = '독서 모임 책읽는밤', description = '독서를 사랑하는 사람들의 모임. reading 습관을 함께 만들어요.' WHERE club_id = 11;
UPDATE club SET name = '댄스 크루 프리스타일', description = '춤을 배우고 즐기는 dance 동호회. 장르 불문 다양한 댄스를 함께해요.' WHERE club_id = 12;
UPDATE club SET name = '요리 동호회 맛있는세상', description = '요리를 배우고 나누는 cooking 모임. 다양한 레시피를 함께 만들어요.' WHERE club_id = 13;
UPDATE club SET name = '사진 동아리 셔터찬스', description = '사진 촬영을 즐기는 포토그래피 동호회. photography 출사를 함께 떠나요.' WHERE club_id = 14;
UPDATE club SET name = '한강 러닝 크루', description = '한강 러닝과 조깅을 즐기는 달리기 동호회. running 매니아 모여라!' WHERE club_id = 15;
UPDATE club SET name = '창업 네트워킹 모임', description = '스타트업 창업가들의 networking 모임. startup 생태계 정보를 공유해요.' WHERE club_id = 16;
COMMIT;
SELECT '동의어 테스트 클럽 16건 업데이트 완료' AS step;


-- #############################################################
-- Phase 4: 확장 데이터
-- user_club 60K, user_settlement 50K, user_interest 10K
-- #############################################################
SELECT '========== Phase 4: 확장 데이터 ==========' AS phase;

-- 4-1. user_club 확대 (유저당 20~50개)
DROP PROCEDURE IF EXISTS expand_user_clubs;

DELIMITER //
CREATE PROCEDURE expand_user_clubs()
BEGIN
    DECLARE v_user_id BIGINT DEFAULT 1;
    DECLARE v_club_id BIGINT;
    DECLARE v_max_user BIGINT DEFAULT 2000;
    DECLARE v_max_club BIGINT DEFAULT 50000;
    DECLARE v_clubs_to_add INT;
    DECLARE v_added INT DEFAULT 0;
    DECLARE batch INT DEFAULT 0;
    DECLARE v_i INT;

    START TRANSACTION;
    WHILE v_user_id <= v_max_user DO
        SET v_clubs_to_add = 15 + FLOOR(RAND() * 21);
        SET v_i = 0;
        WHILE v_i < v_clubs_to_add DO
            SET v_club_id = ((v_user_id * 37 + v_i * 131 + v_user_id * v_i * 7) % v_max_club) + 1;
            INSERT IGNORE INTO user_club (user_id, club_id, role, created_at, modified_at)
            VALUES (v_user_id, v_club_id, IF(RAND() < 0.1, 'LEADER', 'MEMBER'), NOW(), NOW());
            IF ROW_COUNT() > 0 THEN SET v_added = v_added + 1; END IF;
            SET batch = batch + 1;
            IF batch >= 2000 THEN COMMIT; START TRANSACTION; SET batch = 0; END IF;
            SET v_i = v_i + 1;
        END WHILE;
        IF v_user_id % 500 = 0 THEN
            SELECT CONCAT('  user_club 확대: user ', v_user_id, '/', v_max_user, ' | added=', v_added) AS progress;
        END IF;
        SET v_user_id = v_user_id + 1;
    END WHILE;
    COMMIT;
    SELECT CONCAT('user_club 확대 완료: 신규 ', v_added, '건') AS result;
END //
DELIMITER ;

CALL expand_user_clubs();
DROP PROCEDURE IF EXISTS expand_user_clubs;

-- club.member_count 재보정
START TRANSACTION;
UPDATE club c SET c.member_count = (SELECT COUNT(*) FROM user_club uc WHERE uc.club_id = c.club_id);
COMMIT;
SELECT 'club.member_count 재보정 완료' AS step;

-- 4-2. user_settlement 대량 투입 (~50,000건)
DROP PROCEDURE IF EXISTS ensure_settlements;

DELIMITER //
CREATE PROCEDURE ensure_settlements()
BEGIN
    DECLARE v_count INT;
    SELECT COUNT(*) INTO v_count FROM settlement;
    IF v_count < 500 THEN
        INSERT IGNORE INTO schedule (schedule_id, club_id, title, description, location,
                                     start_time, end_time, max_participants, created_at, modified_at)
        SELECT s.n, ((s.n - 1) % 50000) + 1, CONCAT('정산 테스트 일정 ', s.n),
               '부하 테스트용 더미 일정', '서울시 강남구',
               DATE_SUB(NOW(), INTERVAL FLOOR(RAND() * 90) DAY),
               DATE_SUB(NOW(), INTERVAL FLOOR(RAND() * 89) DAY), 20, NOW(), NOW()
        FROM (SELECT @row := @row + 1 AS n FROM
            (SELECT 0 UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
             UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
            (SELECT 0 UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
             UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,
            (SELECT 0 UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
             UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c,
            (SELECT @row := 0) init) s
        WHERE s.n <= 1000 AND NOT EXISTS (SELECT 1 FROM schedule sc WHERE sc.schedule_id = s.n);

        INSERT IGNORE INTO settlement (settlement_id, schedule_id, total_amount, per_person_amount,
                                        status, created_at, modified_at)
        SELECT sc.schedule_id, sc.schedule_id, 100000, 10000,
               ELT(FLOOR(RAND() * 3) + 1, 'PENDING', 'IN_PROGRESS', 'COMPLETED'), NOW(), NOW()
        FROM schedule sc WHERE sc.schedule_id <= 1000
        AND NOT EXISTS (SELECT 1 FROM settlement st WHERE st.schedule_id = sc.schedule_id);
    END IF;
END //
DELIMITER ;

CALL ensure_settlements();
DROP PROCEDURE IF EXISTS ensure_settlements;

DROP PROCEDURE IF EXISTS insert_user_settlements;

DELIMITER //
CREATE PROCEDURE insert_user_settlements()
proc_body: BEGIN
    DECLARE v_user_id BIGINT DEFAULT 1;
    DECLARE v_max_settlement BIGINT;
    DECLARE v_settlement_id BIGINT;
    DECLARE v_settlements_per_user INT;
    DECLARE v_status VARCHAR(20);
    DECLARE v_added INT DEFAULT 0;
    DECLARE batch INT DEFAULT 0;
    DECLARE v_i INT;

    SELECT MAX(settlement_id) INTO v_max_settlement FROM settlement;
    IF v_max_settlement IS NULL THEN
        SELECT 'ERROR: settlement 비어있음' AS error; LEAVE proc_body;
    END IF;

    START TRANSACTION;
    WHILE v_user_id <= 2000 DO
        SET v_settlements_per_user = 15 + FLOOR(RAND() * 21);
        SET v_i = 0;
        WHILE v_i < v_settlements_per_user DO
            SET v_settlement_id = ((v_user_id * 23 + v_i * 97) % v_max_settlement) + 1;
            SET v_status = ELT(FLOOR(RAND() * 100) + 1,
                'PENDING','PENDING','PENDING','PENDING','PENDING','PENDING','PENDING','PENDING','PENDING','PENDING',
                'PENDING','PENDING','PENDING','PENDING','PENDING','PENDING','PENDING','PENDING','PENDING','PENDING',
                'PENDING','PENDING','PENDING','PENDING','PENDING','PENDING','PENDING','PENDING','PENDING','PENDING',
                'PENDING','PENDING','PENDING','PENDING','PENDING','PENDING','PENDING','PENDING','PENDING','PENDING',
                'IN_PROGRESS','IN_PROGRESS','IN_PROGRESS','IN_PROGRESS','IN_PROGRESS',
                'IN_PROGRESS','IN_PROGRESS','IN_PROGRESS','IN_PROGRESS','IN_PROGRESS',
                'IN_PROGRESS','IN_PROGRESS','IN_PROGRESS','IN_PROGRESS','IN_PROGRESS',
                'COMPLETED','COMPLETED','COMPLETED','COMPLETED','COMPLETED','COMPLETED','COMPLETED','COMPLETED','COMPLETED','COMPLETED',
                'COMPLETED','COMPLETED','COMPLETED','COMPLETED','COMPLETED','COMPLETED','COMPLETED','COMPLETED','COMPLETED','COMPLETED',
                'COMPLETED','COMPLETED','COMPLETED','COMPLETED','COMPLETED','COMPLETED','COMPLETED','COMPLETED','COMPLETED','COMPLETED',
                'COMPLETED','COMPLETED','COMPLETED','COMPLETED','COMPLETED','COMPLETED','COMPLETED','COMPLETED','COMPLETED','COMPLETED',
                'COMPLETED','COMPLETED','COMPLETED','COMPLETED','COMPLETED');
            INSERT IGNORE INTO user_settlement (user_id, settlement_id, status, completed_time, created_at, modified_at)
            VALUES (v_user_id, v_settlement_id, v_status,
                    IF(v_status = 'COMPLETED', DATE_SUB(NOW(), INTERVAL FLOOR(RAND() * 30) DAY), NULL), NOW(), NOW());
            IF ROW_COUNT() > 0 THEN SET v_added = v_added + 1; END IF;
            SET batch = batch + 1;
            IF batch >= 2000 THEN COMMIT; START TRANSACTION; SET batch = 0; END IF;
            SET v_i = v_i + 1;
        END WHILE;
        IF v_user_id % 500 = 0 THEN
            SELECT CONCAT('  user_settlement: user ', v_user_id, '/2000 | added=', v_added) AS progress;
        END IF;
        SET v_user_id = v_user_id + 1;
    END WHILE;
    COMMIT;
    SELECT CONCAT('user_settlement 완료: ', v_added, '건') AS result;
END //
DELIMITER ;

CALL insert_user_settlements();
DROP PROCEDURE IF EXISTS insert_user_settlements;

-- 4-3. user_interest 보강 (유저당 2~4개)
DROP PROCEDURE IF EXISTS expand_user_interests;

DELIMITER //
CREATE PROCEDURE expand_user_interests()
BEGIN
    DECLARE v_user_id BIGINT DEFAULT 1;
    DECLARE v_interest_count INT;
    DECLARE v_interest_id BIGINT;
    DECLARE v_added INT DEFAULT 0;
    DECLARE batch INT DEFAULT 0;
    DECLARE v_i INT;
    START TRANSACTION;
    WHILE v_user_id <= 2000 DO
        SET v_interest_count = 2 + FLOOR(RAND() * 3);
        SET v_i = 0;
        WHILE v_i < v_interest_count DO
            SET v_interest_id = ((v_user_id * 3 + v_i * 5) % 8) + 1;
            INSERT IGNORE INTO user_interest (user_id, interest_id, created_at, modified_at)
            VALUES (v_user_id, v_interest_id, NOW(), NOW());
            IF ROW_COUNT() > 0 THEN SET v_added = v_added + 1; END IF;
            SET batch = batch + 1;
            IF batch >= 2000 THEN COMMIT; START TRANSACTION; SET batch = 0; END IF;
            SET v_i = v_i + 1;
        END WHILE;
        SET v_user_id = v_user_id + 1;
    END WHILE;
    COMMIT;
    SELECT CONCAT('user_interest 보강 완료: ', v_added, '건') AS result;
END //
DELIMITER ;

CALL expand_user_interests();
DROP PROCEDURE IF EXISTS expand_user_interests;

-- 4-4. 유저 지역 정보 보강
UPDATE `user` u
SET u.city = ELT(((u.user_id - 1) % 8) + 1, '서울', '부산', '대구', '인천', '광주', '대전', '울산', '세종'),
    u.district = ELT(((u.user_id - 1) % 5) + 1, '강남구', '해운대구', '중구', '남구', '서구')
WHERE u.city IS NULL OR u.district IS NULL;
SELECT CONCAT('유저 지역 보강: ', ROW_COUNT(), '건') AS result;


-- #############################################################
-- Phase 5: Notification 기본 데이터
-- 읽음 상태 리셋 + 테스트용 5,000건
-- #############################################################
SELECT '========== Phase 5: Notification 기본 데이터 ==========' AS phase;

UPDATE notification SET is_read = 0 WHERE user_id <= 1000 AND is_read = 1;

INSERT INTO notification (content, is_read, sse_sent, type, user_id, created_at, modified_at)
SELECT
    CONCAT('테스트 알림 #', seq.n, ' - ', u.user_id),
    0, 1,
    ELT(1 + FLOOR(RAND() * 5), 'CHAT', 'SETTLEMENT', 'LIKE', 'COMMENT', 'REFEED'),
    u.user_id,
    NOW() - INTERVAL FLOOR(RAND() * 3600) SECOND,
    NOW()
FROM
    (SELECT @row := @row + 1 AS n FROM
        (SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5
         UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9 UNION SELECT 10) a
        CROSS JOIN
        (SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5) b,
        (SELECT @row := 0) r
    ) seq
    CROSS JOIN (SELECT user_id FROM `user` WHERE user_id <= 100) u;
COMMIT;
SELECT CONCAT('Notification 기본 데이터: ', ROW_COUNT(), '건') AS result;


-- #############################################################
-- OPTIONAL: 대량 알림 시드 (수동 CALL 전용)
-- 각각 ~30분 소요, 10,000,000건씩 생성
-- 사용법:
--   CALL seed_delivery_data();  -- SSE 전달 검증 테스트 전
--   CALL seed_batch_data();     -- 배치 포화 테스트 전
--   CALL seed_conflict_data();  -- 동시성 충돌 테스트 전
-- #############################################################

-- SSE 전달 검증: 유저 1~1000 × 10,000건 (sse_sent=false)
DROP PROCEDURE IF EXISTS seed_delivery_data;

DELIMITER $$
CREATE PROCEDURE seed_delivery_data()
BEGIN
    DECLARE v_user_id INT DEFAULT 1;
    DECLARE v_batch INT DEFAULT 0;
    DELETE FROM notification WHERE content LIKE '테스트 SSE 전달%';
    WHILE v_user_id <= 1000 DO
        SET v_batch = 0;
        WHILE v_batch < 10 DO
            INSERT INTO notification (content, is_read, sse_sent, type, user_id, created_at, modified_at)
            SELECT CONCAT('테스트 SSE 전달 #', (v_batch * 1000) + seq.n, ' - user', v_user_id),
                0, 0, ELT(1 + (seq.n % 5), 'CHAT', 'SETTLEMENT', 'LIKE', 'COMMENT', 'REFEED'),
                v_user_id, NOW() - INTERVAL ((v_batch * 1000) + seq.n) SECOND, NOW()
            FROM (SELECT a.n + b.n * 10 + c.n * 100 + 1 AS n FROM
                (SELECT 0 AS n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
                 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a
                CROSS JOIN (SELECT 0 AS n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
                 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b
                CROSS JOIN (SELECT 0 AS n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
                 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c
            ) seq;
            SET v_batch = v_batch + 1;
            IF v_batch = 10 THEN COMMIT; END IF;
        END WHILE;
        IF v_user_id % 100 = 0 THEN
            COMMIT;
            SELECT CONCAT('delivery: ', v_user_id, '/1000 유저 (', v_user_id * 10000, '건)') AS progress;
        END IF;
        SET v_user_id = v_user_id + 1;
    END WHILE;
    COMMIT;
    SELECT 'SSE 전달 시드 완료: ~10,000,000건' AS result;
END$$
DELIMITER ;

-- 배치 포화: 유저 1~1000 × 10,000건 (sse_sent=false, 30초 간격)
DROP PROCEDURE IF EXISTS seed_batch_data;

DELIMITER $$
CREATE PROCEDURE seed_batch_data()
BEGIN
    DECLARE v_user_id INT DEFAULT 1;
    DECLARE v_batch INT DEFAULT 0;
    DELETE FROM notification WHERE content LIKE '테스트 배치%';
    WHILE v_user_id <= 1000 DO
        SET v_batch = 0;
        WHILE v_batch < 10 DO
            INSERT INTO notification (content, is_read, sse_sent, type, user_id, created_at, modified_at)
            SELECT CONCAT('테스트 배치 #', (v_batch * 1000) + seq.n, ' - user', v_user_id),
                0, 0, ELT(1 + (seq.n % 5), 'CHAT', 'SETTLEMENT', 'LIKE', 'COMMENT', 'REFEED'),
                v_user_id, NOW() - INTERVAL ((v_batch * 1000) + seq.n) * 30 SECOND, NOW()
            FROM (SELECT a.n + b.n * 10 + c.n * 100 + 1 AS n FROM
                (SELECT 0 AS n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
                 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a
                CROSS JOIN (SELECT 0 AS n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
                 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b
                CROSS JOIN (SELECT 0 AS n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
                 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c
            ) seq;
            SET v_batch = v_batch + 1;
            IF v_batch = 10 THEN COMMIT; END IF;
        END WHILE;
        IF v_user_id % 100 = 0 THEN
            COMMIT;
            SELECT CONCAT('batch: ', v_user_id, '/1000 유저 (', v_user_id * 10000, '건)') AS progress;
        END IF;
        SET v_user_id = v_user_id + 1;
    END WHILE;
    COMMIT;
    SELECT '배치 포화 시드 완료: ~10,000,000건' AS result;
END$$
DELIMITER ;

-- 동시성 충돌: 유저 1에게 10,000,000건 (sse_sent=true, is_read=false)
DROP PROCEDURE IF EXISTS seed_conflict_data;

DELIMITER $$
CREATE PROCEDURE seed_conflict_data()
BEGIN
    DECLARE v_batch INT DEFAULT 0;
    DELETE FROM notification WHERE content LIKE '테스트 충돌%';
    WHILE v_batch < 10000 DO
        INSERT INTO notification (content, is_read, sse_sent, type, user_id, created_at, modified_at)
        SELECT CONCAT('테스트 충돌 #', (v_batch * 1000) + seq.n),
            0, 1, ELT(1 + (seq.n % 5), 'CHAT', 'SETTLEMENT', 'LIKE', 'COMMENT', 'REFEED'),
            1, NOW() - INTERVAL ((v_batch * 1000) + seq.n) SECOND, NOW()
        FROM (SELECT a.n + b.n * 10 + c.n * 100 + 1 AS n FROM
            (SELECT 0 AS n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
             UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a
            CROSS JOIN (SELECT 0 AS n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
             UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b
            CROSS JOIN (SELECT 0 AS n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
             UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c
        ) seq;
        SET v_batch = v_batch + 1;
        IF v_batch % 100 = 0 THEN COMMIT; END IF;
        IF v_batch % 1000 = 0 THEN
            SELECT CONCAT('conflict: ', v_batch, '/10000 배치 (', v_batch * 1000, '건)') AS progress;
        END IF;
    END WHILE;
    COMMIT;
    SELECT '동시성 충돌 시드 완료: ~10,000,000건' AS result;
END$$
DELIMITER ;


-- #############################################################
-- 글로벌 설정 복원
-- #############################################################
SET autocommit         = @old_autocommit;
SET unique_checks      = @old_unique_checks;
SET foreign_key_checks = @old_foreign_key_checks;


-- #############################################################
-- 최종 통계
-- #############################################################
SELECT '========================================' AS separator;
SELECT '=== 시드 데이터 최종 통계 ===' AS header;
SELECT '========================================' AS separator;

SELECT 'user' AS entity, COUNT(*) AS count FROM `user`
UNION ALL SELECT 'interest', COUNT(*) FROM interest
UNION ALL SELECT 'club', COUNT(*) FROM club
UNION ALL SELECT 'user_club', COUNT(*) FROM user_club
UNION ALL SELECT 'user_interest', COUNT(*) FROM user_interest
UNION ALL SELECT 'wallet', COUNT(*) FROM wallet
UNION ALL SELECT 'schedule', COUNT(*) FROM schedule
UNION ALL SELECT 'user_schedule', COUNT(*) FROM user_schedule
UNION ALL SELECT 'settlement', COUNT(*) FROM settlement
UNION ALL SELECT 'user_settlement', COUNT(*) FROM user_settlement
UNION ALL SELECT 'wallet_transaction', COUNT(*) FROM wallet_transaction
UNION ALL SELECT 'notification', COUNT(*) FROM notification;

SELECT '--- 유저당 모임 수 ---' AS header;
SELECT MIN(cnt) AS min_clubs, MAX(cnt) AS max_clubs, ROUND(AVG(cnt),1) AS avg_clubs
FROM (SELECT COUNT(*) AS cnt FROM user_club GROUP BY user_id) t;

SELECT '--- 정산 상태 분포 ---' AS header;
SELECT status, COUNT(*) AS cnt,
       ROUND(COUNT(*) * 100.0 / (SELECT COUNT(*) FROM user_settlement), 1) AS pct
FROM user_settlement GROUP BY status;

SELECT '=== 완료 - reindexAll 실행 필요 ===' AS status;
