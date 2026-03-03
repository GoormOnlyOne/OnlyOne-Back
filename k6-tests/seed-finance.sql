-- =============================================================
-- Finance 부하 테스트용 시드 데이터 (100x 스케일)
-- =============================================================
-- 실행: docker exec -i onlyone-mysql mysql -uroot -proot onlyone < k6-tests/seed-finance.sql
--
-- 테스트 대상 사용자: userId 1~100000
-- 각 사용자에 지갑(posted_balance=100000, pending_out=0) 보장
-- schedule 25,000개 (schedule_id 5000000~5024999)
-- settlement 25,000개 (HOLDING, receiver=userId 1)
-- user_settlement 250,000개 (각 settlement당 참여자 10명, HOLD_ACTIVE)
-- =============================================================

SET @START_TIME = NOW();
SELECT '=== Finance 시드 데이터 생성 시작 (100x) ===' AS msg;

-- ── 1) 지갑 초기화 ──
SELECT '--- 지갑 초기화 (100,000) ---' AS msg;

INSERT INTO wallet (user_id, posted_balance, pending_out, created_at, modified_at)
SELECT u.user_id, 100000, 0, NOW(), NOW()
FROM user u
WHERE u.user_id BETWEEN 1 AND 100000
  AND NOT EXISTS (SELECT 1 FROM wallet w WHERE w.user_id = u.user_id)
ON DUPLICATE KEY UPDATE
    posted_balance = 100000,
    pending_out = 0,
    modified_at = NOW();

UPDATE wallet SET posted_balance = 100000, pending_out = 0, modified_at = NOW()
WHERE user_id BETWEEN 1 AND 100000;

SELECT CONCAT('  지갑 count: ', COUNT(*)) AS msg FROM wallet WHERE user_id BETWEEN 1 AND 100000;

-- ── 2) 기존 부하테스트 결제 데이터 정리 ──
SELECT '--- 기존 결제 데이터 정리 ---' AS msg;
SET FOREIGN_KEY_CHECKS = 0;
DELETE FROM wallet_transaction WHERE payment_id IN (SELECT payment_id FROM payment WHERE toss_order_id LIKE 'loadtest-%');
DELETE FROM payment WHERE toss_order_id LIKE 'loadtest-%';
SET FOREIGN_KEY_CHECKS = 1;

-- ── 3) 테스트 전용 schedule 25,000개 (schedule_id 5000000~5024999) ──
SELECT '--- 테스트 스케줄 생성 (5000000~5024999) ---' AS msg;

-- 기존 테스트 스케줄 정리
DELETE FROM user_settlement WHERE settlement_id IN (
    SELECT settlement_id FROM settlement WHERE schedule_id BETWEEN 5000000 AND 5024999
);
DELETE FROM settlement WHERE schedule_id BETWEEN 5000000 AND 5024999;
DELETE FROM schedule WHERE schedule_id BETWEEN 5000000 AND 5024999;

-- 실제 존재하는 club_id 25,000개를 임시 테이블로 준비
DROP TABLE IF EXISTS _valid_clubs;
CREATE TABLE _valid_clubs (
    seq INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    club_id BIGINT NOT NULL
) ENGINE=InnoDB;
INSERT INTO _valid_clubs (club_id)
SELECT club_id FROM club ORDER BY club_id LIMIT 25000;

-- schedule 25,000개 삽입 (실제 존재하는 club_id 사용)
INSERT INTO schedule (schedule_id, created_at, modified_at, cost, location, name, status, schedule_time, user_limit, club_id)
SELECT
    5000000 + (u.user_id - 1),
    NOW(),
    NOW(),
    1000,
    'LoadTest Location',
    CONCAT('LoadTest Schedule ', u.user_id),
    'ENDED',
    DATE_SUB(NOW(), INTERVAL 1 DAY),
    20,
    vc.club_id
FROM user u
JOIN _valid_clubs vc ON vc.seq = u.user_id
WHERE u.user_id BETWEEN 1 AND 25000
ON DUPLICATE KEY UPDATE
    status = 'ENDED',
    modified_at = NOW();

DROP TABLE IF EXISTS _valid_clubs;

SELECT CONCAT('  스케줄 count: ', COUNT(*)) AS msg FROM schedule WHERE schedule_id BETWEEN 5000000 AND 5024999;

-- ── 4) settlement 25,000개 (각 schedule마다 1건, HOLDING, receiver=userId 1) ──
SELECT '--- 정산 생성 (HOLDING x 25,000) ---' AS msg;

INSERT INTO settlement (created_at, modified_at, completed_time, schedule_id, sum, total_status, user_id)
SELECT
    NOW(),
    NOW(),
    NULL,
    5000000 + (u.user_id - 1),
    0,
    'HOLDING',
    1
FROM user u
WHERE u.user_id BETWEEN 1 AND 25000;

SELECT CONCAT('  정산 count: ', COUNT(*)) AS msg
FROM settlement WHERE schedule_id BETWEEN 5000000 AND 5024999 AND total_status = 'HOLDING';

-- ── 5) user_settlement (각 settlement당 참여자 10명, HOLD_ACTIVE = 250,000건) ──
SELECT '--- 참여자 정산 생성 (10명 x 25,000건) ---' AS msg;

INSERT INTO user_settlement (created_at, modified_at, completed_time, status, settlement_id, user_id)
SELECT
    NOW(),
    NOW(),
    NULL,
    'HOLD_ACTIVE',
    s.settlement_id,
    participant.user_id
FROM settlement s
CROSS JOIN (
    SELECT user_id FROM user WHERE user_id BETWEEN 2 AND 11
) participant
WHERE s.schedule_id BETWEEN 5000000 AND 5024999
  AND s.total_status = 'HOLDING';

SELECT CONCAT('  참여자 정산 count: ', COUNT(*)) AS msg
FROM user_settlement us
JOIN settlement s ON us.settlement_id = s.settlement_id
WHERE s.schedule_id BETWEEN 5000000 AND 5024999;

-- ── 6) 기존 정산 데이터 HOLDING으로 리셋 ──
SELECT '--- 기존 정산 상태 리셋 ---' AS msg;
UPDATE settlement SET total_status = 'HOLDING', completed_time = NULL, modified_at = NOW()
WHERE schedule_id BETWEEN 5000000 AND 5024999;

UPDATE user_settlement SET status = 'HOLD_ACTIVE', completed_time = NULL, modified_at = NOW()
WHERE settlement_id IN (
    SELECT settlement_id FROM settlement WHERE schedule_id BETWEEN 5000000 AND 5024999
);

SELECT TIMEDIFF(NOW(), @START_TIME) AS elapsed;
SELECT '=== Finance 시드 데이터 완료 ===' AS msg;
