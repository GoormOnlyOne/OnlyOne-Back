-- 🚀 100만건 알림 생성 (검증된 단순 버전)
-- 프로시저 없이 직접 INSERT로 안전하게 처리

SELECT CONCAT('🔥 100만건 알림 생성 시작: ', NOW()) as start_message;

-- 성능 최적화 설정
SET SESSION foreign_key_checks = 0;
SET SESSION unique_checks = 0;
SET SESSION autocommit = 0;

-- 알림 타입 ID 확인
SET @notification_type_id = (SELECT type_id FROM notification_type WHERE type = 'CHAT' LIMIT 1);
SET @start_time = NOW();

SELECT CONCAT('📋 알림 타입 ID: ', @notification_type_id) as type_info;
SELECT CONCAT('📊 현재 알림 개수: ', FORMAT(COUNT(*), 0)) as before_count FROM notification;

-- 🚀 100만건 한번에 INSERT
INSERT INTO notification (user_id, type_id, content, is_read, sse_sent, fcm_sent, created_at, modified_at)
SELECT 
    100001 + (n % 500) as user_id,  -- 500명 사용자에게 순환 배정
    @notification_type_id as type_id,
    CONCAT('🚀메가알림#', n + 1, '👤', 100001 + (n % 500)) as content,
    (n % 10 < 3) as is_read,      -- 30% 읽음
    (n % 10 < 8) as sse_sent,     -- 80% SSE 성공
    (n % 10 < 7) as fcm_sent,     -- 70% FCM 성공
    DATE_SUB(NOW(), INTERVAL (n % 86400) SECOND) as created_at,  -- 최근 24시간 내 랜덤
    NOW() as modified_at
FROM (
    -- 100만개 숫자 생성 (0-999999)
    SELECT 
        a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 as n
    FROM 
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) e,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) f
    WHERE a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 < 1000000
) numbers;

COMMIT;

-- 설정 복원
SET SESSION autocommit = 1;
SET SESSION unique_checks = 1;
SET SESSION foreign_key_checks = 1;

-- 🎉 결과 확인
SELECT CONCAT('🎉 100만건 생성 완료: ', NOW()) as success;
SELECT CONCAT('⏰ 소요시간: ', TIMESTAMPDIFF(MINUTE, @start_time, NOW()), '분 ', 
              TIMESTAMPDIFF(SECOND, @start_time, NOW()) % 60, '초') as duration;
SELECT CONCAT('📊 총 알림 수: ', FORMAT(COUNT(*), 0), '개') as total_count FROM notification;
SELECT CONCAT('📈 새로 추가: ', FORMAT(COUNT(*), 0), '개') as added_count 
FROM notification WHERE content LIKE '🚀메가알림#%';

-- 📊 통계
SELECT '🏆 === 생성 통계 === 🏆' as stats_title;

SELECT 
    '읽음 상태' as category,
    CASE WHEN is_read = 1 THEN '✅읽음' ELSE '📮안읽음' END as status,
    FORMAT(COUNT(*), 0) as count,
    CONCAT(ROUND(COUNT(*) * 100.0 / (SELECT COUNT(*) FROM notification WHERE content LIKE '🚀메가알림#%'), 1), '%') as percentage
FROM notification 
WHERE content LIKE '🚀메가알림#%'
GROUP BY is_read
UNION ALL
SELECT 
    'SSE 전송' as category,
    CASE WHEN sse_sent = 1 THEN '🚀성공' ELSE '💥실패' END as status,
    FORMAT(COUNT(*), 0) as count,
    CONCAT(ROUND(COUNT(*) * 100.0 / (SELECT COUNT(*) FROM notification WHERE content LIKE '🚀메가알림#%'), 1), '%') as percentage
FROM notification 
WHERE content LIKE '🚀메가알림#%'
GROUP BY sse_sent
UNION ALL
SELECT 
    'FCM 전송' as category,
    CASE WHEN fcm_sent = 1 THEN '📲성공' ELSE '📵실패' END as status,
    FORMAT(COUNT(*), 0) as count,
    CONCAT(ROUND(COUNT(*) * 100.0 / (SELECT COUNT(*) FROM notification WHERE content LIKE '🚀메가알림#%'), 1), '%') as percentage
FROM notification 
WHERE content LIKE '🚀메가알림#%'
GROUP BY fcm_sent;

-- 👥 사용자별 분포 (상위 10명)
SELECT 
    CONCAT('👤 사용자 ', user_id) as user_info,
    FORMAT(COUNT(*), 0) as notification_count
FROM notification 
WHERE content LIKE '🚀메가알림#%'
GROUP BY user_id 
ORDER BY COUNT(*) DESC 
LIMIT 10;

SELECT '🎊 100만건 대용량 데이터 생성 완료! 성능 테스트 준비 끝! 🎊' as celebration;