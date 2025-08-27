-- 🔥 1천만건 알림 생성 (직접 INSERT 방식)
-- 프로시저 없이 순수 SQL만으로 1천만건 생성

SELECT CONCAT('🔥🔥🔥 1천만건 알림 생성 시작: ', NOW(), ' 🔥🔥🔥') as mega_start;

-- 성능 최적화 설정
SET SESSION foreign_key_checks = 0;
SET SESSION unique_checks = 0;
SET SESSION autocommit = 0;
SET SESSION max_heap_table_size = 4294967296; -- 4GB
SET SESSION tmp_table_size = 4294967296; -- 4GB
SET SESSION bulk_insert_buffer_size = 1073741824; -- 1GB

-- 알림 타입 ID 확인
SET @notification_type_id = (SELECT type_id FROM notification_type WHERE type = 'CHAT' LIMIT 1);
SET @start_time = NOW();

SELECT CONCAT('📋 알림 타입 ID: ', @notification_type_id) as type_info;
SELECT CONCAT('📊 현재 알림 개수: ', FORMAT(COUNT(*), 0)) as before_count FROM notification;
SELECT '🚀 1천만개 레코드 생성을 시작합니다. 잠시만 기다려주세요...' as warning;

-- 🔥 1천만건 MEGA INSERT!
-- 10자리 숫자 조합으로 0부터 9,999,999까지 생성 (1천만개)
INSERT INTO notification (user_id, type_id, content, is_read, sse_sent, fcm_sent, created_at, modified_at)
SELECT 
    100001 + (n % 500) as user_id,  -- 500명 사용자에게 순환 배정
    @notification_type_id as type_id,
    CONCAT('🚀MEGA알림#', n + 1, '👤USER', 100001 + (n % 500)) as content,
    (n % 10 < 3) as is_read,      -- 30% 읽음
    (n % 10 < 8) as sse_sent,     -- 80% SSE 성공  
    (n % 10 < 7) as fcm_sent,     -- 70% FCM 성공
    DATE_SUB(NOW(), INTERVAL (n % 86400) SECOND) as created_at,  -- 최근 24시간 내 랜덤
    NOW() as modified_at
FROM (
    -- 🎯 1천만개 숫자 생성 마법! (0 ~ 9,999,999)
    SELECT 
        a.n + 
        b.n * 10 + 
        c.n * 100 + 
        d.n * 1000 + 
        e.n * 10000 + 
        f.n * 100000 + 
        g.n * 1000000 + 
        h.n * 10000000 as n
    FROM 
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) e,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) f,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) g,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) h
    WHERE a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 + g.n*1000000 + h.n*10000000 < 10000000
) numbers;

COMMIT;

-- 설정 복원
SET SESSION autocommit = 1;
SET SESSION unique_checks = 1;
SET SESSION foreign_key_checks = 1;

-- 🎉🎉🎉 VICTORY!!! 🎉🎉🎉
SELECT CONCAT('🎉🎉🎉 1천만건 생성 MEGA SUCCESS!!! 🎉🎉🎉') as mega_victory;
SELECT CONCAT('⏰ 총 소요시간: ', 
              TIMESTAMPDIFF(MINUTE, @start_time, NOW()), '분 ', 
              TIMESTAMPDIFF(SECOND, @start_time, NOW()) % 60, '초') as total_duration;

SELECT CONCAT('📊 총 알림 수: ', FORMAT(COUNT(*), 0), '개') as total_count FROM notification;
SELECT CONCAT('🚀 새로 추가: ', FORMAT(COUNT(*), 0), '개') as mega_added 
FROM notification WHERE content LIKE '🚀MEGA알림#%';

-- 🏆 CHAMPION 통계
SELECT '🏆 === 1천만건 CHAMPION 통계 === 🏆' as champion_title;

SELECT 
    '📖 읽음 상태' as category,
    CASE WHEN is_read = 1 THEN '✅읽음' ELSE '📮안읽음' END as status,
    FORMAT(COUNT(*), 0) as count,
    CONCAT(ROUND(COUNT(*) * 100.0 / 10000000, 1), '%') as percentage
FROM notification 
WHERE content LIKE '🚀MEGA알림#%'
GROUP BY is_read

UNION ALL

SELECT 
    '📡 SSE 전송' as category,
    CASE WHEN sse_sent = 1 THEN '🚀성공' ELSE '💥실패' END as status,
    FORMAT(COUNT(*), 0) as count,
    CONCAT(ROUND(COUNT(*) * 100.0 / 10000000, 1), '%') as percentage
FROM notification 
WHERE content LIKE '🚀MEGA알림#%'
GROUP BY sse_sent

UNION ALL

SELECT 
    '📱 FCM 전송' as category,
    CASE WHEN fcm_sent = 1 THEN '📲성공' ELSE '📵실패' END as status,
    FORMAT(COUNT(*), 0) as count,
    CONCAT(ROUND(COUNT(*) * 100.0 / 10000000, 1), '%') as percentage
FROM notification 
WHERE content LIKE '🚀MEGA알림#%'
GROUP BY fcm_sent;

-- 🎖️ TOP 사용자들 (알림 보유량 순위)
SELECT '🎖️ TOP 사용자들 (알림 보유량)' as top_users_title;
SELECT 
    CONCAT('🥇 ', ROW_NUMBER() OVER(ORDER BY COUNT(*) DESC), '위') as ranking,
    CONCAT('👤 사용자 ', user_id) as user_info,
    FORMAT(COUNT(*), 0) as notification_count
FROM notification 
WHERE content LIKE '🚀MEGA알림#%'
GROUP BY user_id 
ORDER BY COUNT(*) DESC 
LIMIT 10;

-- 💾 스토리지 사용량 확인
SELECT 
    '💾 스토리지 정보' as info_type,
    CONCAT(ROUND(((data_length + index_length) / 1024 / 1024 / 1024), 2), ' GB') as total_size,
    CONCAT(ROUND((data_length / 1024 / 1024 / 1024), 2), ' GB') as data_size,
    CONCAT(ROUND((index_length / 1024 / 1024 / 1024), 2), ' GB') as index_size
FROM information_schema.tables 
WHERE table_schema = 'buddkit' AND table_name = 'notification';

-- 🎯 성능 체크
SELECT CONCAT('⚡ 평균 처리속도: ', 
              FORMAT(10000000 / GREATEST(TIMESTAMPDIFF(SECOND, @start_time, NOW()), 1), 0), 
              '개/초') as processing_speed;

SELECT '🎊🎊🎊 1천만건 MEGA 데이터 생성 완료! 성능 테스트 준비 완료! 🎊🎊🎊' as final_celebration;