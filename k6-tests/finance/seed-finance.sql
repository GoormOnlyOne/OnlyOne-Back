-- =============================================================
-- Finance 부하 테스트용 시드 데이터 (100x 스케일, 10M+ 대응)
-- =============================================================
-- 실행: docker exec -i onlyone-mysql mysql -uroot -proot onlyone < k6-tests/seed-finance.sql
--
-- 테스트 대상 사용자: userId 1~100000
-- 일반 유저: 지갑(posted_balance=100000, pending_out=0)
-- 정산 참여자(userId 2~11): 지갑(posted_balance=10000000, pending_out=10000000)
-- schedule 100,000개 (schedule_id 5000000~5099999)
-- settlement 100,000개 (HOLDING, receiver=userId 1)
-- user_settlement 1,000,000개 (각 settlement당 참여자 10명, HOLD_ACTIVE)
-- =============================================================

SET @START_TIME = NOW();
SELECT '=== Finance 시드 데이터 생성 시작 (100x) ===' AS msg;

-- ── 1) 지갑 초기화 ──
SELECT '--- 지갑 초기화 (100,000) ---' AS msg;

-- 일반 유저 (userId 1, 12~100000): 기본 잔액
INSERT INTO wallet (user_id, posted_balance, pending_out, created_at, modified_at)
SELECT u.user_id, 100000, 0, NOW(), NOW()
FROM user u
WHERE u.user_id BETWEEN 1 AND 100000
  AND u.user_id NOT BETWEEN 2 AND 11
  AND NOT EXISTS (SELECT 1 FROM wallet w WHERE w.user_id = u.user_id)
ON DUPLICATE KEY UPDATE
    posted_balance = 100000,
    pending_out = 0,
    modified_at = NOW();

UPDATE wallet SET posted_balance = 100000, pending_out = 0, modified_at = NOW()
WHERE user_id BETWEEN 1 AND 100000
  AND user_id NOT BETWEEN 2 AND 11;

-- 정산 참여자 (userId 2~11): 100,000 정산 x costPerUser 100 = pending_out 10,000,000
-- captureHold 조건: pending_out >= amount AND posted_balance >= amount
-- holdBalanceIfEnough가 이미 실행된 상태를 시뮬레이션
INSERT INTO wallet (user_id, posted_balance, pending_out, created_at, modified_at)
SELECT u.user_id, 10000000, 10000000, NOW(), NOW()
FROM user u WHERE u.user_id BETWEEN 2 AND 11
ON DUPLICATE KEY UPDATE
    posted_balance = 10000000,
    pending_out = 10000000,
    modified_at = NOW();

UPDATE wallet SET posted_balance = 10000000, pending_out = 10000000, modified_at = NOW()
WHERE user_id BETWEEN 2 AND 11;

SELECT CONCAT('  지갑 count: ', COUNT(*)) AS msg FROM wallet WHERE user_id BETWEEN 1 AND 100000;

-- ── 2) 기존 부하테스트 결제 데이터 정리 ──
SELECT '--- 기존 결제 데이터 정리 ---' AS msg;
SET FOREIGN_KEY_CHECKS = 0;
DELETE FROM wallet_transaction WHERE payment_id IN (SELECT payment_id FROM payment WHERE toss_order_id LIKE 'loadtest-%');
DELETE FROM payment WHERE toss_order_id LIKE 'loadtest-%';
SET FOREIGN_KEY_CHECKS = 1;

-- ── 3) 테스트 전용 schedule 100,000개 (schedule_id 5000000~5099999) ──
SELECT '--- 테스트 스케줄 생성 (5000000~5099999) ---' AS msg;

-- 기존 테스트 스케줄 정리 (FK 임시 비활성화)
SET FOREIGN_KEY_CHECKS = 0;
DELETE FROM user_settlement WHERE settlement_id IN (
    SELECT settlement_id FROM settlement WHERE schedule_id BETWEEN 5000000 AND 5099999
);
DELETE FROM settlement WHERE schedule_id BETWEEN 5000000 AND 5099999;
DELETE FROM user_schedule WHERE schedule_id BETWEEN 5000000 AND 5099999;
DELETE FROM schedule WHERE schedule_id BETWEEN 5000000 AND 5099999;
SET FOREIGN_KEY_CHECKS = 1;

-- schedule 100,000개 삽입 (모든 테스트 스케줄은 MIN(club_id) 클럽에 소속)
SET @finance_club = (SELECT MIN(club_id) FROM club);

-- 헬퍼 테이블 생성 (100K 시퀀스)
DROP TABLE IF EXISTS _digits;
CREATE TABLE _digits (d INT NOT NULL) ENGINE=MEMORY;
INSERT INTO _digits VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);
DROP TABLE IF EXISTS _seq100k;
CREATE TABLE _seq100k (n INT NOT NULL, PRIMARY KEY(n)) ENGINE=InnoDB;
INSERT INTO _seq100k
SELECT d5.d*10000 + d4.d*1000 + d3.d*100 + d2.d*10 + d1.d
FROM _digits d1, _digits d2, _digits d3, _digits d4, _digits d5;

INSERT INTO schedule (schedule_id, created_at, modified_at, cost, location, name, status, schedule_time, user_limit, club_id)
SELECT
    5000000 + s.n,
    NOW(),
    NOW(),
    1000,
    'LoadTest Location',
    CONCAT('LoadTest Schedule ', s.n),
    'ENDED',
    DATE_SUB(NOW(), INTERVAL 1 DAY),
    20,
    @finance_club
FROM _seq100k s
ON DUPLICATE KEY UPDATE
    status = 'ENDED',
    modified_at = NOW();

SELECT CONCAT('  스케줄 count: ', COUNT(*)) AS msg FROM schedule WHERE schedule_id BETWEEN 5000000 AND 5099999;

-- ── 4) settlement 100,000개 (각 schedule마다 1건, HOLDING, receiver=userId 1) ──
SELECT '--- 정산 생성 (HOLDING x 100,000) ---' AS msg;

INSERT INTO settlement (created_at, modified_at, completed_time, schedule_id, sum, total_status, user_id, version)
SELECT
    NOW(),
    NOW(),
    NULL,
    5000000 + s.n,
    0,
    'HOLDING',
    (s.n % 100000) + 1,
    0
FROM _seq100k s;

SELECT CONCAT('  정산 count: ', COUNT(*)) AS msg
FROM settlement WHERE schedule_id BETWEEN 5000000 AND 5099999 AND total_status = 'HOLDING';

-- ── 5) user_settlement (각 settlement당 참여자 10명, HOLD_ACTIVE = 1,000,000건) ──
SELECT '--- 참여자 정산 생성 (10명 x 100,000건) ---' AS msg;

DELIMITER //
DROP PROCEDURE IF EXISTS seed_user_settlements_finance //
CREATE PROCEDURE seed_user_settlements_finance()
BEGIN
    DECLARE p INT DEFAULT 2;
    WHILE p <= 11 DO
        INSERT INTO user_settlement (created_at, modified_at, completed_time, status, settlement_id, user_id)
        SELECT NOW(), NOW(), NULL, 'HOLD_ACTIVE', s.settlement_id, p
        FROM settlement s
        WHERE s.schedule_id BETWEEN 5000000 AND 5099999
          AND s.total_status = 'HOLDING';
        SELECT CONCAT('    유저정산 userId=', p, ' 완료') AS msg;
        SET p = p + 1;
    END WHILE;
END //
DELIMITER ;
CALL seed_user_settlements_finance();
DROP PROCEDURE IF EXISTS seed_user_settlements_finance;

SELECT CONCAT('  참여자 정산 count: ', COUNT(*)) AS msg
FROM user_settlement us
JOIN settlement s ON us.settlement_id = s.settlement_id
WHERE s.schedule_id BETWEEN 5000000 AND 5099999;

-- ── 6) 기존 정산 데이터 HOLDING으로 리셋 ──
SELECT '--- 기존 정산 상태 리셋 ---' AS msg;
UPDATE settlement SET total_status = 'HOLDING', completed_time = NULL, modified_at = NOW()
WHERE schedule_id BETWEEN 5000000 AND 5099999;

UPDATE user_settlement SET status = 'HOLD_ACTIVE', completed_time = NULL, modified_at = NOW()
WHERE settlement_id IN (
    SELECT settlement_id FROM settlement WHERE schedule_id BETWEEN 5000000 AND 5099999
);

-- 헬퍼 테이블 정리
DROP TABLE IF EXISTS _seq100k;
DROP TABLE IF EXISTS _digits;

SELECT TIMEDIFF(NOW(), @START_TIME) AS elapsed;
SELECT '=== Finance 시드 데이터 완료 ===' AS msg;
