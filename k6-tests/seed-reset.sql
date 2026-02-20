-- =============================================================
-- k6 테스트 데이터 리셋 스크립트
-- 매 테스트 실행 전 호출하여 일관된 테스트 환경 보장
-- =============================================================

-- 1) 읽음 상태 초기화 (테스트 유저 1~1000만 대상, 빠른 실행)
UPDATE notification SET is_read = 0
WHERE user_id <= 1000 AND is_read = 1;

-- 2) 삭제 테스트용 더미 알림 생성
--    user_id 1~100 에게 각 50건씩 = 5,000건
INSERT INTO notification (content, is_read, sse_sent, type, user_id, created_at, modified_at)
SELECT
    CONCAT('테스트 알림 #', seq.n, ' - ', u.user_id),
    0,
    1,
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
    CROSS JOIN (SELECT user_id FROM user WHERE user_id <= 100) u;

SELECT CONCAT('리셋 완료: 삭제용 알림 ', ROW_COUNT(), '건 생성') AS result;
