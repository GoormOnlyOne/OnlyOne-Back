-- =============================================================
-- 정산 도메인 증설 시드 (기존 데이터 유지 + 추가)
-- =============================================================
-- 목표: settlement 69K → 500K, user_settlement 691K → 5M
-- 실행: mysql -uroot -proot onlyone < seed-settlement-boost.sql
-- 예상 소요: 5~10분
-- =============================================================

SET @START_TIME = NOW();
SET FOREIGN_KEY_CHECKS = 0;
SET UNIQUE_CHECKS = 0;
SET autocommit = 0;

-- ── 기존 데이터 파악 ──
SELECT '--- 정산 증설 시드 시작 ---' AS '';

SET @existing_clubs = (SELECT COUNT(*) FROM club);
SET @min_club = (SELECT MIN(club_id) FROM club);
SET @max_settlement_id = (SELECT COALESCE(MAX(settlement_id), 0) FROM settlement);
SET @max_schedule_id_finance = (SELECT COALESCE(MAX(schedule_id), 5000000) FROM schedule WHERE schedule_id >= 5000000);
SET @total_users = (SELECT COUNT(*) FROM user);

SELECT CONCAT('  기존 clubs: ', @existing_clubs) AS '';
SELECT CONCAT('  기존 max settlement_id: ', @max_settlement_id) AS '';
SELECT CONCAT('  기존 max finance schedule_id: ', @max_schedule_id_finance) AS '';
SELECT CONCAT('  기존 total users: ', @total_users) AS '';

-- ── 상수 ──
SET @target_settlements = 500000;
SET @new_settlements = @target_settlements - (SELECT COUNT(*) FROM settlement);
SET @schedule_base = 5000000;

SELECT CONCAT('  추가할 settlements: ', @new_settlements) AS '';

-- ── 헬퍼 테이블 ──
DROP TABLE IF EXISTS _digits;
CREATE TABLE _digits (d INT NOT NULL) ENGINE=MEMORY;
INSERT INTO _digits VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);

DROP TABLE IF EXISTS _seq100k;
CREATE TABLE _seq100k (n INT NOT NULL, PRIMARY KEY(n)) ENGINE=InnoDB;
INSERT INTO _seq100k
SELECT d5.d*10000 + d4.d*1000 + d3.d*100 + d2.d*10 + d1.d
FROM _digits d1, _digits d2, _digits d3, _digits d4, _digits d5;
COMMIT;

-- ═══════════════════════════════════════════
-- 1) 추가 schedules (5069149 ~ 5499999)
--    club_id = (schedule_id - 5000000) % existing_clubs + min_club
-- ═══════════════════════════════════════════
SELECT '--- [1/4] 추가 schedules 생성 ---' AS '';

INSERT IGNORE INTO schedule (schedule_id, club_id, name, location,
    schedule_time, user_limit, cost, status, created_at, modified_at)
SELECT
    @max_schedule_id_finance + 1 + s.n AS schedule_id,
    @min_club + ((@max_schedule_id_finance + 1 + s.n - @schedule_base) % @existing_clubs) AS club_id,
    CONCAT('정산스케줄', @max_schedule_id_finance + 1 + s.n) AS name,
    '서울시 강남구' AS location,
    DATE_ADD('2026-01-01', INTERVAL (s.n % 365) DAY) AS schedule_time,
    20 AS user_limit,
    10000 AS cost,
    'ENDED' AS status,
    NOW() AS created_at,
    NOW() AS modified_at
FROM _seq100k s
WHERE s.n < (@target_settlements - (@max_schedule_id_finance - @schedule_base + 1))
  AND s.n < 500000;
COMMIT;

SELECT CONCAT('  schedules 추가 완료, 총: ',
    (SELECT COUNT(*) FROM schedule WHERE schedule_id >= 5000000)) AS '';

-- ═══════════════════════════════════════════
-- 2) 추가 settlements (HOLDING 상태)
--    receiver = (settlement_idx % total_users) + 1 (다양한 유저)
-- ═══════════════════════════════════════════
SELECT '--- [2/4] 추가 settlements 생성 ---' AS '';

-- 배치 프로시저 (5만건씩)
DELIMITER //
DROP PROCEDURE IF EXISTS seed_settlements_boost //
CREATE PROCEDURE seed_settlements_boost()
BEGIN
    DECLARE batch_start INT DEFAULT 0;
    DECLARE batch_size INT DEFAULT 50000;
    DECLARE remaining INT;
    DECLARE max_sid BIGINT;
    DECLARE max_sched BIGINT;
    DECLARE total_u INT;
    DECLARE existing_c INT;
    DECLARE min_c BIGINT;

    SET remaining = (SELECT @target_settlements - COUNT(*) FROM settlement);
    SET max_sid = (SELECT COALESCE(MAX(settlement_id), 0) FROM settlement);
    SET max_sched = (SELECT MAX(schedule_id) FROM schedule WHERE schedule_id >= 5000000);
    SET total_u = @total_users;
    SET existing_c = @existing_clubs;
    SET min_c = @min_club;

    WHILE batch_start < remaining DO
        INSERT INTO settlement (schedule_id, user_id, sum, total_status, version, created_at, modified_at)
        SELECT
            5000000 + ((max_sid + 1 - 100001 + batch_start + s.n) % (max_sched - 5000000 + 1)) AS schedule_id,
            ((batch_start + s.n) % total_u) + 1 AS user_id,
            100000 AS sum,
            CASE
                WHEN (batch_start + s.n) % 10 < 7 THEN 'HOLDING'
                WHEN (batch_start + s.n) % 10 < 9 THEN 'COMPLETED'
                ELSE 'FAILED'
            END AS total_status,
            0 AS version,
            DATE_SUB(NOW(), INTERVAL ((batch_start + s.n) % 365) DAY) AS created_at,
            NOW() AS modified_at
        FROM _seq100k s
        WHERE s.n < LEAST(batch_size, remaining - batch_start);
        COMMIT;

        SET batch_start = batch_start + batch_size;
        SELECT CONCAT('  settlements batch: ', LEAST(batch_start, remaining), '/', remaining) AS '';
    END WHILE;
END //
DELIMITER ;

CALL seed_settlements_boost();

SELECT CONCAT('  settlements 총: ', (SELECT COUNT(*) FROM settlement)) AS '';

-- ═══════════════════════════════════════════
-- 3) user_settlement — 각 settlement당 10명, 다양한 유저
-- ═══════════════════════════════════════════
SELECT '--- [3/4] user_settlement 생성 (기존 삭제 후 재생성) ---' AS '';

-- 기존 user_settlement 삭제 (user 2-11에만 치중된 데이터)
TRUNCATE TABLE user_settlement;

-- 배치 프로시저
DELIMITER //
DROP PROCEDURE IF EXISTS seed_user_settlements_boost //
CREATE PROCEDURE seed_user_settlements_boost()
BEGIN
    DECLARE batch_start INT DEFAULT 0;
    DECLARE batch_size INT DEFAULT 50000;
    DECLARE total_settle INT;
    DECLARE total_u INT;

    SET total_settle = (SELECT COUNT(*) FROM settlement);
    SET total_u = @total_users;

    -- settlement_id 목록을 임시 테이블에 캐싱
    DROP TEMPORARY TABLE IF EXISTS _settlement_ids;
    CREATE TEMPORARY TABLE _settlement_ids (
        row_num INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
        sid BIGINT NOT NULL
    ) ENGINE=MEMORY;
    INSERT INTO _settlement_ids (sid)
    SELECT settlement_id FROM settlement ORDER BY settlement_id;

    WHILE batch_start < total_settle DO
        -- 각 settlement에 10명 참여자 삽입
        INSERT INTO user_settlement (settlement_id, user_id, status, created_at, modified_at)
        SELECT
            t.sid,
            ((t.row_num * 10 + p.d - 10) % total_u) + 1 AS user_id,
            CASE
                WHEN s_tbl.total_status = 'COMPLETED' THEN 'COMPLETED'
                WHEN s_tbl.total_status = 'FAILED' THEN 'FAILED'
                ELSE 'HOLD_ACTIVE'
            END AS status,
            s_tbl.created_at,
            NOW()
        FROM _settlement_ids t
        JOIN _digits p ON p.d BETWEEN 0 AND 9
        JOIN settlement s_tbl ON s_tbl.settlement_id = t.sid
        WHERE t.row_num > batch_start AND t.row_num <= batch_start + batch_size;
        COMMIT;

        SET batch_start = batch_start + batch_size;
        SELECT CONCAT('  user_settlement batch: ', LEAST(batch_start, total_settle), '/', total_settle) AS '';
    END WHILE;

    DROP TEMPORARY TABLE IF EXISTS _settlement_ids;
END //
DELIMITER ;

CALL seed_user_settlements_boost();

SELECT CONCAT('  user_settlement 총: ', (SELECT COUNT(*) FROM user_settlement)) AS '';

-- ═══════════════════════════════════════════
-- 4) wallet pending_out 업데이트
--    HOLD_ACTIVE 참여자의 pending_out 설정
-- ═══════════════════════════════════════════
SELECT '--- [4/4] wallet pending_out 업데이트 ---' AS '';

UPDATE wallet w
JOIN (
    SELECT us.user_id, SUM(s.sum / 10) AS total_pending
    FROM user_settlement us
    JOIN settlement s ON s.settlement_id = us.settlement_id
    WHERE us.status = 'HOLD_ACTIVE'
    GROUP BY us.user_id
) agg ON w.user_id = agg.user_id
SET w.pending_out = agg.total_pending;
COMMIT;

SELECT CONCAT('  pending_out 업데이트 완료, 영향 rows: ',
    (SELECT COUNT(*) FROM wallet WHERE pending_out > 0)) AS '';

-- ── 정리 ──
DROP TABLE IF EXISTS _digits;
DROP TABLE IF EXISTS _seq100k;
DROP PROCEDURE IF EXISTS seed_settlements_boost;
DROP PROCEDURE IF EXISTS seed_user_settlements_boost;

SET FOREIGN_KEY_CHECKS = 1;
SET UNIQUE_CHECKS = 1;

-- ── 검증 ──
SELECT '' AS '';
SELECT '========================================' AS '';
SELECT '=== 정산 증설 검증 ===' AS '';
SELECT '========================================' AS '';
SELECT CONCAT('  settlement:       ', (SELECT COUNT(*) FROM settlement)) AS '';
SELECT CONCAT('  user_settlement:  ', (SELECT COUNT(*) FROM user_settlement)) AS '';
SELECT CONCAT('  schedule (>=5M):  ', (SELECT COUNT(*) FROM schedule WHERE schedule_id >= 5000000)) AS '';

SELECT CONCAT('  HOLDING:    ', cnt) AS '' FROM (SELECT COUNT(*) cnt FROM settlement WHERE total_status='HOLDING') t;
SELECT CONCAT('  COMPLETED:  ', cnt) AS '' FROM (SELECT COUNT(*) cnt FROM settlement WHERE total_status='COMPLETED') t;
SELECT CONCAT('  FAILED:     ', cnt) AS '' FROM (SELECT COUNT(*) cnt FROM settlement WHERE total_status='FAILED') t;

SELECT CONCAT('  user_settlement 유저 다양성: ',
    (SELECT COUNT(DISTINCT user_id) FROM user_settlement), ' distinct users') AS '';

SELECT CONCAT('  소요시간: ', TIMEDIFF(NOW(), @START_TIME)) AS '';
SELECT '=== 정산 증설 완료 ===' AS '';
