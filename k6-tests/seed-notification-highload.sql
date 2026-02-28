-- =============================================================
-- seed-notification-highload.sql
-- 알림 고부하 테스트용 시드 데이터 (100,000건)
-- =============================================================
--
-- 실행 방법:
--   mysql -h 127.0.0.1 -P 3340 -u root -p onlyone < k6-tests/seed-notification-highload.sql
--
-- 기존 seed-data.sql Phase 5 (5,000건) 대신 사용
-- 유저 1~1000 × 100건 = 100,000건
-- 읽음/미읽음 비율: 30% 읽음, 70% 미읽음
-- SSE 전달 상태: 90% 전달됨, 10% 미전달
-- =============================================================

USE onlyone;

SET @old_autocommit         = @@autocommit;
SET @old_unique_checks      = @@unique_checks;
SET @old_foreign_key_checks = @@foreign_key_checks;

SET autocommit         = 0;
SET unique_checks      = 0;
SET foreign_key_checks = 0;

SELECT '========== 고부하 알림 시드: 기존 데이터 정리 ==========' AS phase;

-- 기존 테스트 알림 삭제
DELETE FROM notification WHERE content LIKE '테스트 알림%' OR content LIKE '고부하 알림%';
COMMIT;
SELECT CONCAT('기존 데이터 삭제 완료: ', ROW_COUNT(), '건') AS result;

-- ============================================
-- 100건 시퀀스 생성 (10 × 10)
-- ============================================
SELECT '========== 고부하 알림 시드: 100,000건 삽입 시작 ==========' AS phase;

-- 유저 1~100: 각 100건 = 10,000건 (1차 배치)
INSERT INTO notification (content, is_read, sse_sent, type, user_id, created_at, modified_at)
SELECT
    CONCAT('고부하 알림 #', seq.n, ' - ', u.user_id),
    CASE WHEN RAND() < 0.3 THEN 1 ELSE 0 END,    -- 30% 읽음
    CASE WHEN RAND() < 0.9 THEN 1 ELSE 0 END,    -- 90% 전달됨
    ELT(1 + FLOOR(RAND() * 5), 'CHAT', 'SETTLEMENT', 'LIKE', 'COMMENT', 'REFEED'),
    u.user_id,
    NOW() - INTERVAL FLOOR(RAND() * 86400) SECOND,  -- 최근 24시간
    NOW()
FROM
    (SELECT a.n + b.n * 10 + 1 AS n FROM
        (SELECT 0 AS n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
         UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a
        CROSS JOIN
        (SELECT 0 AS n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
         UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b
    ) seq
    CROSS JOIN (SELECT user_id FROM `user` WHERE user_id <= 100) u;
COMMIT;
SELECT CONCAT('배치 1 완료 (user 1~100): ', ROW_COUNT(), '건') AS result;

-- 유저 101~200: 각 100건 = 10,000건 (2차 배치)
INSERT INTO notification (content, is_read, sse_sent, type, user_id, created_at, modified_at)
SELECT
    CONCAT('고부하 알림 #', seq.n, ' - ', u.user_id),
    CASE WHEN RAND() < 0.3 THEN 1 ELSE 0 END,
    CASE WHEN RAND() < 0.9 THEN 1 ELSE 0 END,
    ELT(1 + FLOOR(RAND() * 5), 'CHAT', 'SETTLEMENT', 'LIKE', 'COMMENT', 'REFEED'),
    u.user_id,
    NOW() - INTERVAL FLOOR(RAND() * 86400) SECOND,
    NOW()
FROM
    (SELECT a.n + b.n * 10 + 1 AS n FROM
        (SELECT 0 AS n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
         UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a
        CROSS JOIN
        (SELECT 0 AS n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
         UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b
    ) seq
    CROSS JOIN (SELECT user_id FROM `user` WHERE user_id BETWEEN 101 AND 200) u;
COMMIT;
SELECT CONCAT('배치 2 완료 (user 101~200): ', ROW_COUNT(), '건') AS result;

-- 유저 201~400: 각 100건 = 20,000건 (3차 배치)
INSERT INTO notification (content, is_read, sse_sent, type, user_id, created_at, modified_at)
SELECT
    CONCAT('고부하 알림 #', seq.n, ' - ', u.user_id),
    CASE WHEN RAND() < 0.3 THEN 1 ELSE 0 END,
    CASE WHEN RAND() < 0.9 THEN 1 ELSE 0 END,
    ELT(1 + FLOOR(RAND() * 5), 'CHAT', 'SETTLEMENT', 'LIKE', 'COMMENT', 'REFEED'),
    u.user_id,
    NOW() - INTERVAL FLOOR(RAND() * 86400) SECOND,
    NOW()
FROM
    (SELECT a.n + b.n * 10 + 1 AS n FROM
        (SELECT 0 AS n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
         UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a
        CROSS JOIN
        (SELECT 0 AS n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
         UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b
    ) seq
    CROSS JOIN (SELECT user_id FROM `user` WHERE user_id BETWEEN 201 AND 400) u;
COMMIT;
SELECT CONCAT('배치 3 완료 (user 201~400): ', ROW_COUNT(), '건') AS result;

-- 유저 401~700: 각 100건 = 30,000건 (4차 배치)
INSERT INTO notification (content, is_read, sse_sent, type, user_id, created_at, modified_at)
SELECT
    CONCAT('고부하 알림 #', seq.n, ' - ', u.user_id),
    CASE WHEN RAND() < 0.3 THEN 1 ELSE 0 END,
    CASE WHEN RAND() < 0.9 THEN 1 ELSE 0 END,
    ELT(1 + FLOOR(RAND() * 5), 'CHAT', 'SETTLEMENT', 'LIKE', 'COMMENT', 'REFEED'),
    u.user_id,
    NOW() - INTERVAL FLOOR(RAND() * 86400) SECOND,
    NOW()
FROM
    (SELECT a.n + b.n * 10 + 1 AS n FROM
        (SELECT 0 AS n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
         UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a
        CROSS JOIN
        (SELECT 0 AS n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
         UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b
    ) seq
    CROSS JOIN (SELECT user_id FROM `user` WHERE user_id BETWEEN 401 AND 700) u;
COMMIT;
SELECT CONCAT('배치 4 완료 (user 401~700): ', ROW_COUNT(), '건') AS result;

-- 유저 701~1000: 각 100건 = 30,000건 (5차 배치)
INSERT INTO notification (content, is_read, sse_sent, type, user_id, created_at, modified_at)
SELECT
    CONCAT('고부하 알림 #', seq.n, ' - ', u.user_id),
    CASE WHEN RAND() < 0.3 THEN 1 ELSE 0 END,
    CASE WHEN RAND() < 0.9 THEN 1 ELSE 0 END,
    ELT(1 + FLOOR(RAND() * 5), 'CHAT', 'SETTLEMENT', 'LIKE', 'COMMENT', 'REFEED'),
    u.user_id,
    NOW() - INTERVAL FLOOR(RAND() * 86400) SECOND,
    NOW()
FROM
    (SELECT a.n + b.n * 10 + 1 AS n FROM
        (SELECT 0 AS n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
         UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a
        CROSS JOIN
        (SELECT 0 AS n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
         UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b
    ) seq
    CROSS JOIN (SELECT user_id FROM `user` WHERE user_id BETWEEN 701 AND 1000) u;
COMMIT;
SELECT CONCAT('배치 5 완료 (user 701~1000): ', ROW_COUNT(), '건') AS result;

-- ============================================
-- 핫 유저: user 1~10에 추가 1,000건씩 (총 1,100건/유저)
-- 대량 데이터 유저에서의 페이지네이션 성능 측정용
-- ============================================
SELECT '========== 핫 유저 추가 데이터 (user 1~10 × 1,000건) ==========' AS phase;

INSERT INTO notification (content, is_read, sse_sent, type, user_id, created_at, modified_at)
SELECT
    CONCAT('핫유저 알림 #', seq.n, ' - ', u.user_id),
    CASE WHEN RAND() < 0.2 THEN 1 ELSE 0 END,   -- 20% 읽음 (미읽음 많음)
    1,
    ELT(1 + FLOOR(RAND() * 5), 'CHAT', 'SETTLEMENT', 'LIKE', 'COMMENT', 'REFEED'),
    u.user_id,
    NOW() - INTERVAL FLOOR(RAND() * 172800) SECOND,  -- 최근 48시간
    NOW()
FROM
    (SELECT a.n + b.n * 10 + c.n * 100 + 1 AS n FROM
        (SELECT 0 AS n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
         UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a
        CROSS JOIN
        (SELECT 0 AS n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
         UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b
        CROSS JOIN
        (SELECT 0 AS n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
         UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c
    ) seq
    CROSS JOIN (SELECT user_id FROM `user` WHERE user_id <= 10) u;
COMMIT;
SELECT CONCAT('핫 유저 추가: ', ROW_COUNT(), '건') AS result;

-- ============================================
-- 데이터 검증
-- ============================================
SELECT '========== 데이터 검증 ==========' AS phase;
SELECT COUNT(*) AS total_notifications FROM notification;
SELECT
    CASE WHEN is_read = 1 THEN '읽음' ELSE '미읽음' END AS status,
    COUNT(*) AS cnt
FROM notification GROUP BY is_read;
SELECT
    CASE WHEN sse_sent = 1 THEN '전달됨' ELSE '미전달' END AS sse_status,
    COUNT(*) AS cnt
FROM notification GROUP BY sse_sent;
SELECT type, COUNT(*) AS cnt FROM notification GROUP BY type ORDER BY cnt DESC;
SELECT
    CASE
        WHEN user_id <= 10 THEN 'hot_users (1~10)'
        WHEN user_id <= 100 THEN 'normal_1 (11~100)'
        WHEN user_id <= 500 THEN 'normal_2 (101~500)'
        ELSE 'normal_3 (501~1000)'
    END AS user_group,
    COUNT(*) AS total,
    SUM(CASE WHEN is_read = 0 THEN 1 ELSE 0 END) AS unread
FROM notification GROUP BY 1 ORDER BY 1;

-- ============================================
-- 설정 복원
-- ============================================
SET autocommit         = @old_autocommit;
SET unique_checks      = @old_unique_checks;
SET foreign_key_checks = @old_foreign_key_checks;

SELECT '========== 고부하 시드 완료 ==========' AS done;
