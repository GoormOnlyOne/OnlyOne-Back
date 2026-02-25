-- =============================================================
-- 동시성 충돌 테스트용 시드 데이터 (대량)
-- 유저 1에게 10,000,000건 알림 (sse_sent=true, is_read=false)
-- Docker 볼륨 사용 시 1회만 실행하면 영구 보존
-- =============================================================

-- 기존 테스트 알림 정리
DELETE FROM notification WHERE content LIKE '테스트 충돌%';

SET @old_autocommit = @@autocommit;
SET autocommit = 0;
SET @old_unique_checks = @@unique_checks;
SET unique_checks = 0;
SET @old_foreign_key_checks = @@foreign_key_checks;
SET foreign_key_checks = 0;

DROP PROCEDURE IF EXISTS seed_conflict_data;

DELIMITER $$
CREATE PROCEDURE seed_conflict_data()
BEGIN
    DECLARE v_batch INT DEFAULT 0;
    -- 10,000 배치 x 1,000건 = 10,000,000건

    WHILE v_batch < 10000 DO
        INSERT INTO notification (content, is_read, sse_sent, type, user_id, created_at, modified_at)
        SELECT
            CONCAT('테스트 충돌 #', (v_batch * 1000) + seq.n),
            0,
            1,
            ELT(1 + (seq.n % 5), 'CHAT', 'SETTLEMENT', 'LIKE', 'COMMENT', 'REFEED'),
            1,
            NOW() - INTERVAL ((v_batch * 1000) + seq.n) SECOND,
            NOW()
        FROM (
            SELECT a.n + b.n * 10 + c.n * 100 + 1 AS n
            FROM
                (SELECT 0 AS n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
                 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a
            CROSS JOIN
                (SELECT 0 AS n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
                 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b
            CROSS JOIN
                (SELECT 0 AS n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
                 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c
        ) seq;

        SET v_batch = v_batch + 1;

        -- 100배치마다 커밋 + 진행상황
        IF v_batch % 100 = 0 THEN
            COMMIT;
        END IF;
        IF v_batch % 1000 = 0 THEN
            SELECT CONCAT('진행: ', v_batch, '/10000 배치 완료 (', v_batch * 1000, '건)') AS progress;
        END IF;
    END WHILE;

    COMMIT;
END$$
DELIMITER ;

CALL seed_conflict_data();
DROP PROCEDURE IF EXISTS seed_conflict_data;

SET autocommit = @old_autocommit;
SET unique_checks = @old_unique_checks;
SET foreign_key_checks = @old_foreign_key_checks;

SELECT CONCAT('동시성 충돌 테스트 시드 완료: 약 10,000,000건') AS result;
SELECT COUNT(*) AS total_conflict_notifications FROM notification WHERE content LIKE '테스트 충돌%';
