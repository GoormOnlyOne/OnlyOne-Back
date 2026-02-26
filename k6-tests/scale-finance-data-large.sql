-- =============================================================
-- scale-finance-data-large.sql
-- Finance 도메인 대규모 부하 테스트 시드 데이터
-- =============================================================
--
-- 전제: scale-club-data.sql 이 먼저 실행되어야 함
--       (user 2000명, club 50000건, user_club 존재)
--
-- 생성 데이터:
--   - wallet         : 2,000건 (유저당 1개, posted_balance 1,000,000)
--   - schedule       : 5,000건 (club 1~50 × 100개씩, status ENDED)
--   - user_schedule  : ~55,000건 (스케줄당 참여자 10명)
--   - settlement     : 5,000건 (스케줄당 1개, HOLDING 상태)
--   - user_settlement: ~50,000건 (참여자별 HOLD_ACTIVE)
--   - wallet_transaction: ~40,000건 (유저 1~2000 × 20건씩)
--
-- =============================================================

USE onlyone;

SET @old_autocommit         = @@autocommit;
SET @old_unique_checks      = @@unique_checks;
SET @old_foreign_key_checks = @@foreign_key_checks;

SET autocommit         = 0;
SET unique_checks      = 0;
SET foreign_key_checks = 0;

-- ============================================
-- 0. 기존 테스트 데이터 정리
-- ============================================
START TRANSACTION;

-- 부하 테스트로 생성된 payment/wallet_transaction 정리
DELETE FROM wallet_transaction WHERE operation_id LIKE 'seed:%';
DELETE FROM wallet_transaction WHERE payment_id IS NOT NULL AND payment_id IN (
    SELECT payment_id FROM payment WHERE toss_order_id LIKE 'order_%'
);
DELETE FROM payment WHERE toss_order_id LIKE 'order_%';

-- 기존 정산 테스트 데이터 정리
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
SELECT '기존 테스트 데이터 정리 완료' AS step;

-- ============================================
-- 1. Wallet: 유저 2,000명 × 지갑 1개
--    posted_balance = 1,000,000 (넉넉한 잔액)
-- ============================================
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
        IF batch >= 500 THEN
            COMMIT;
            START TRANSACTION;
            SET batch = 0;
        END IF;

        SET i = i + 1;
    END WHILE;

    COMMIT;
    SELECT CONCAT('Wallets: ', (SELECT COUNT(*) FROM wallet), '건') AS result;
END //
DELIMITER ;

CALL finance_insert_wallets();
DROP PROCEDURE IF EXISTS finance_insert_wallets;

-- ============================================
-- 2. Schedule: club 1~50 × 100개 = 5,000건
--    status = ENDED (정산 가능 상태)
-- ============================================
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
                10000 + (v_club_id * 100),
                20,
                'ENDED',
                v_club_id,
                NOW(), NOW()
            );

            SET batch = batch + 1;
            SET v_sched_idx = v_sched_idx + 1;
        END WHILE;

        IF batch >= 500 THEN
            COMMIT;
            START TRANSACTION;
            SET batch = 0;
        END IF;

        SET v_club_id = v_club_id + 1;
    END WHILE;

    COMMIT;
    SELECT CONCAT('Schedules (new): ', (SELECT COUNT(*) FROM schedule WHERE name LIKE '정산테스트_%'), '건') AS result;
END //
DELIMITER ;

CALL finance_insert_schedules();
DROP PROCEDURE IF EXISTS finance_insert_schedules;

-- ============================================
-- 3. UserSchedule + Settlement + UserSettlement
--    - 각 스케줄에 참여자 10명 배정
--    - 5,000 settlement, ~50,000 user_settlement
-- ============================================
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
        SELECT schedule_id, club_id FROM schedule
        WHERE name LIKE '정산테스트_%'
        ORDER BY schedule_id;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;

    START TRANSACTION;

    OPEN cur;
    read_loop: LOOP
        FETCH cur INTO v_schedule_id, v_club_id;
        IF done THEN LEAVE read_loop; END IF;

        SET v_leader_id = ((v_club_id - 1) % 50) + 1;

        INSERT IGNORE INTO user_schedule (user_id, schedule_id, role, created_at, modified_at)
        VALUES (v_leader_id, v_schedule_id, 'LEADER', NOW(), NOW());

        INSERT INTO settlement (
            schedule_id, sum, total_status, user_id, created_at, modified_at
        ) VALUES (
            v_schedule_id, 0, 'HOLDING', v_leader_id, NOW(), NOW()
        );

        SET v_settlement_id = LAST_INSERT_ID();

        SET j = 1;
        WHILE j <= 10 DO
            SET v_participant_id = ((v_club_id * 7 + v_schedule_id * 13 + j * 31) % 2000) + 1;

            IF v_participant_id != v_leader_id THEN
                INSERT IGNORE INTO user_schedule (user_id, schedule_id, role, created_at, modified_at)
                VALUES (v_participant_id, v_schedule_id, 'MEMBER', NOW(), NOW());

                INSERT INTO user_settlement (
                    status, settlement_id, user_id, created_at, modified_at
                ) VALUES (
                    'HOLD_ACTIVE', v_settlement_id, v_participant_id, NOW(), NOW()
                );
            END IF;

            SET j = j + 1;
        END WHILE;

        SET batch = batch + 1;
        IF batch >= 100 THEN
            COMMIT;
            START TRANSACTION;
            SET batch = 0;
        END IF;
    END LOOP;

    CLOSE cur;
    COMMIT;

    SELECT CONCAT('Settlements: ', (SELECT COUNT(*) FROM settlement), '건') AS result;
    SELECT CONCAT('UserSettlements: ', (SELECT COUNT(*) FROM user_settlement), '건') AS result;
    SELECT CONCAT('UserSchedules: ', (SELECT COUNT(*) FROM user_schedule), '건') AS result;
END //
DELIMITER ;

CALL finance_insert_settlement_data();
DROP PROCEDURE IF EXISTS finance_insert_settlement_data;

-- ============================================
-- 4. WalletTransaction: 대규모 충전 기록
--    유저 1~2000 × 20건씩 = 40,000건
-- ============================================
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
                    wallet_id, target_wallet_id,
                    created_at, modified_at
                ) VALUES (
                    CONCAT('seed:charge:', v_user_id, ':v', j),
                    'CHARGE',
                    5000 + (j * 500),
                    1000000,
                    'COMPLETED',
                    v_wallet_id,
                    v_wallet_id,
                    DATE_SUB(NOW(), INTERVAL (j * v_user_id % 365) DAY),
                    NOW()
                ) ON DUPLICATE KEY UPDATE modified_at = NOW();

                SET j = j + 1;
            END WHILE;
        END IF;

        SET batch = batch + 1;
        IF batch >= 100 THEN
            COMMIT;
            START TRANSACTION;
            SET batch = 0;
        END IF;

        SET v_user_id = v_user_id + 1;
    END WHILE;

    COMMIT;
    SELECT CONCAT('WalletTransactions: ', (SELECT COUNT(*) FROM wallet_transaction), '건') AS result;
END //
DELIMITER ;

CALL finance_insert_wallet_transactions();
DROP PROCEDURE IF EXISTS finance_insert_wallet_transactions;

-- ============================================
-- 5. 설정 복원
-- ============================================
SET autocommit         = @old_autocommit;
SET unique_checks      = @old_unique_checks;
SET foreign_key_checks = @old_foreign_key_checks;

-- ============================================
-- 6. 최종 검증
-- ============================================
SELECT '=== Finance Large Seed Data - Final Stats ===' AS header;

SELECT 'Wallets' AS entity, COUNT(*) AS count FROM wallet
UNION ALL SELECT 'Schedules (정산테스트)', COUNT(*) FROM schedule WHERE name LIKE '정산테스트_%'
UNION ALL SELECT 'UserSchedules', COUNT(*) FROM user_schedule
UNION ALL SELECT 'Settlements', COUNT(*) FROM settlement
UNION ALL SELECT 'UserSettlements', COUNT(*) FROM user_settlement
UNION ALL SELECT 'WalletTransactions', COUNT(*) FROM wallet_transaction;

SELECT 'Schedule ID 범위' AS check_type,
    MIN(schedule_id) AS min_id, MAX(schedule_id) AS max_id
FROM schedule WHERE name LIKE '정산테스트_%';

SELECT '=== Done ===' AS status;
