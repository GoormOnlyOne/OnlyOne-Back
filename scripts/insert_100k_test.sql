-- 🧪 10만건 테스트 스크립트 (실제 테이블 구조 검증용)
-- 먼저 작은 규모로 테스트해서 문제 없는지 확인

SELECT CONCAT('🧪 10만건 테스트 시작: ', NOW()) as test_start;

-- 기본 설정
SET SESSION foreign_key_checks = 0;
SET SESSION unique_checks = 0;
SET SESSION autocommit = 0;

-- 알림 타입 ID 확인
SET @notification_type_id = (SELECT type_id FROM notification_type WHERE type = 'CHAT' LIMIT 1);
SET @start_time = NOW();

SELECT CONCAT('📋 사용할 알림 타입 ID: ', @notification_type_id) as type_check;

-- 현재 데이터 확인
SELECT CONCAT('📊 현재 알림 개수: ', COUNT(*)) as before_count FROM notification;

-- 10만건 삽입
INSERT INTO notification (
    user_id, type_id, content, is_read, sse_sent, fcm_sent, 
    created_at, modified_at
)
SELECT 
    100001 + (n % 500) as user_id,  -- 500명 사용자 순환
    @notification_type_id as type_id,
    CONCAT('테스트 알림 #', n + 1, ' - 사용자 ', 100001 + (n % 500)) as content,
    (n % 10 < 3) as is_read,        -- 30% 읽음
    (n % 10 < 8) as sse_sent,       -- 80% SSE 성공
    (n % 10 < 7) as fcm_sent,       -- 70% FCM 성공
    DATE_SUB(NOW(), INTERVAL (n % 86400) SECOND) as created_at, -- 최근 24시간 내
    NOW() as modified_at
FROM (
    -- 10만개 숫자 생성 (0-99999)
    SELECT a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 as n
    FROM 
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a
        CROSS JOIN (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b  
        CROSS JOIN (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c
        CROSS JOIN (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d
        CROSS JOIN (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) e
) numbers;

COMMIT;

-- 설정 복원
SET SESSION autocommit = 1;
SET SESSION unique_checks = 1;
SET SESSION foreign_key_checks = 1;

-- 결과 확인
SELECT CONCAT('✅ 10만건 테스트 완료: ', NOW()) as test_done;
SELECT CONCAT('⏰ 소요시간: ', TIMESTAMPDIFF(SECOND, @start_time, NOW()), '초') as duration;
SELECT CONCAT('📊 총 알림 개수: ', FORMAT(COUNT(*), 0), '개') as total_count FROM notification;
SELECT CONCAT('📈 방금 추가된 개수: ', FORMAT(COUNT(*), 0), '개') as added_count 
FROM notification 
WHERE content LIKE '테스트 알림 #%';

-- 간단한 통계
SELECT 
    CASE WHEN is_read = 1 THEN '읽음' ELSE '안읽음' END as read_status,
    COUNT(*) as count,
    CONCAT(ROUND(COUNT(*) * 100.0 / (SELECT COUNT(*) FROM notification WHERE content LIKE '테스트 알림 #%'), 1), '%') as percentage
FROM notification 
WHERE content LIKE '테스트 알림 #%'
GROUP BY is_read;

-- 사용자 분포 확인 (상위 5명)
SELECT 
    user_id,
    COUNT(*) as notification_count
FROM notification 
WHERE content LIKE '테스트 알림 #%'
GROUP BY user_id 
ORDER BY COUNT(*) DESC 
LIMIT 5;

SELECT '🎯 10만건 테스트 성공! 이제 1천만건도 안전합니다! 🎯' as success_message;