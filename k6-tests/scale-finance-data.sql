-- =============================================================
-- scale-finance-data.sql
-- Finance 도메인 부하 테스트 시드 데이터
-- =============================================================
--
-- 전제: scale-club-data.sql 이 먼저 실행되어야 함
--       (user 2000명, club 50000건, user_club 존재)
--
-- 생성 데이터:
--   - wallet         : 2,000건 (유저당 1개, posted_balance 100,000)
--   - schedule       : 200건   (club 1~50 × 4개씩, status ENDED)
--   - user_schedule  : ~2,000건 (스케줄당 참여자 8~12명)
--   - settlement     : 200건   (스케줄당 1개, HOLDING 상태)
--   - user_settlement: ~2,000건 (참여자별 HOLD_ACTIVE)
--   - wallet_transaction: ~600건 (일부 유저에 충전 기록)
--
-- 실행:
--   mysql -h 127.0.0.1 -P 3340 -u root -p onlyone < k6-tests/scale-finance-data.sql
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
-- 1. Wallet: 유저 2,000명 × 지갑 1개
--    posted_balance = 100,000 (충분한 잔액)
--    pending_out    = 0
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
        VALUES (i, 100000, 0, NOW(), NOW())
        ON DUPLICATE KEY UPDATE modified_at = NOW();

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
-- 2. Schedule: club 1~50 × 4개 = 200건
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
        WHILE v_sched_idx < 4 DO
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

        IF batch >= 100 THEN
            COMMIT;
            START TRANSACTION;
            SET batch = 0;
        END IF;

        SET v_club_id = v_club_id + 1;
    END WHILE;

    COMMIT;
    SELECT CONCAT('Schedules: ', (SELECT COUNT(*) FROM schedule), '건') AS result;
END //
DELIMITER ;

CALL finance_insert_schedules();
DROP PROCEDURE IF EXISTS finance_insert_schedules;

-- ============================================
-- 3. UserSchedule + Settlement + UserSettlement
--    - 각 스케줄에 참여자 10명 배정
--    - 첫 번째 참여자 = LEADER (= settlement receiver)
--    - 나머지 = MEMBER (= settlement 대상)
--    - settlement: HOLDING 상태
--    - user_settlement: HOLD_ACTIVE 상태
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
        SELECT schedule_id, club_id FROM schedule ORDER BY schedule_id;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;

    START TRANSACTION;

    OPEN cur;
    read_loop: LOOP
        FETCH cur INTO v_schedule_id, v_club_id;
        IF done THEN LEAVE read_loop; END IF;

        -- 리더: club_id 기반으로 결정 (user 1~50 순환)
        SET v_leader_id = ((v_club_id - 1) % 50) + 1;

        -- UserSchedule: 리더
        INSERT IGNORE INTO user_schedule (user_id, schedule_id, role, created_at, modified_at)
        VALUES (v_leader_id, v_schedule_id, 'LEADER', NOW(), NOW());

        -- Settlement 생성 (HOLDING 상태)
        INSERT INTO settlement (
            schedule_id, sum, total_status, user_id, created_at, modified_at
        ) VALUES (
            v_schedule_id, 0, 'HOLDING', v_leader_id, NOW(), NOW()
        );

        SET v_settlement_id = LAST_INSERT_ID();

        -- 참여자 10명 (리더 제외): user_id 결정론적 배정
        SET j = 1;
        WHILE j <= 10 DO
            SET v_participant_id = ((v_club_id * 7 + v_schedule_id * 13 + j * 31) % 2000) + 1;

            -- 리더와 겹치면 건너뜀
            IF v_participant_id != v_leader_id THEN
                -- UserSchedule: 참여자
                INSERT IGNORE INTO user_schedule (user_id, schedule_id, role, created_at, modified_at)
                VALUES (v_participant_id, v_schedule_id, 'MEMBER', NOW(), NOW());

                -- UserSettlement: HOLD_ACTIVE
                INSERT INTO user_settlement (
                    status, settlement_id, user_id, created_at, modified_at
                ) VALUES (
                    'HOLD_ACTIVE', v_settlement_id, v_participant_id, NOW(), NOW()
                );
            END IF;

            SET j = j + 1;
        END WHILE;

        SET batch = batch + 1;
        IF batch >= 50 THEN
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
-- 4. WalletTransaction: 일부 유저에 충전 기록
--    (wallet 목록 조회 테스트용)
--    유저 1~200 × 3건씩 = 600건
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

    WHILE v_user_id <= 200 DO
        -- wallet_id 조회
        SELECT wallet_id INTO v_wallet_id FROM wallet WHERE user_id = v_user_id LIMIT 1;

        IF v_wallet_id IS NOT NULL THEN
            SET j = 1;
            WHILE j <= 3 DO
                INSERT INTO wallet_transaction (
                    operation_id, type, amount, balance, status,
                    wallet_id, target_wallet_id,
                    created_at, modified_at
                ) VALUES (
                    CONCAT('seed:charge:', v_user_id, ':v', j),
                    'CHARGE',
                    10000 + (j * 1000),
                    100000,
                    'COMPLETED',
                    v_wallet_id,
                    v_wallet_id,
                    DATE_SUB(NOW(), INTERVAL (j * v_user_id % 30) DAY),
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
SELECT '=== Finance Seed Data - Final Stats ===' AS header;

SELECT 'Wallets' AS entity, COUNT(*) AS count FROM wallet
UNION ALL SELECT 'Schedules', COUNT(*) FROM schedule
UNION ALL SELECT 'UserSchedules', COUNT(*) FROM user_schedule
UNION ALL SELECT 'Settlements', COUNT(*) FROM settlement
UNION ALL SELECT 'UserSettlements', COUNT(*) FROM user_settlement
UNION ALL SELECT 'WalletTransactions', COUNT(*) FROM wallet_transaction;

-- 정산 조회 테스트 유효성 검증: schedule_id별 settlement 존재 확인
SELECT '정산 조회 테스트 데이터 검증' AS check_type,
    COUNT(*) AS settlement_with_participants
FROM settlement s
WHERE EXISTS (
    SELECT 1 FROM user_settlement us WHERE us.settlement_id = s.settlement_id
);

-- Wallet 조회 테스트 유효성 검증: 트랜잭션 있는 유저 수
SELECT 'Wallet 조회 유저 검증' AS check_type,
    COUNT(DISTINCT w.user_id) AS users_with_transactions
FROM wallet w
JOIN wallet_transaction wt ON wt.wallet_id = w.wallet_id;

SELECT '=== Done ===' AS status;
