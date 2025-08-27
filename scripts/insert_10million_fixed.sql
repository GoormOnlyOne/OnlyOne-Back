-- 🚀 1천만건 알림 데이터 생성 스크립트 (수정된 버전)
-- 실제 테이블 구조에 맞춰 수정

SELECT CONCAT('🔥🔥🔥 1천만건 알림 생성 시작: ', NOW(), ' 🔥🔥🔥') as ultra_start;

-- 기본 성능 최적화 설정 (세션 레벨만)
SET SESSION foreign_key_checks = 0;
SET SESSION unique_checks = 0;
SET SESSION autocommit = 0;
SET SESSION max_heap_table_size = 2147483648; -- 2GB
SET SESSION tmp_table_size = 2147483648; -- 2GB  
SET SESSION bulk_insert_buffer_size = 536870912; -- 512MB

-- 알림 타입 ID 확인 (실제 컬럼명 사용)
SET @notification_type_id = (SELECT type_id FROM notification_type WHERE type = 'CHAT' LIMIT 1);
SET @start_time = NOW();

SELECT CONCAT('📋 알림 타입 ID: ', @notification_type_id) as type_info;

-- 현재 데이터 개수 확인
SELECT CONCAT('📊 현재 알림 개수: ', COUNT(*)) as current_count FROM notification;

-- 🎲 랜덤 패턴 테이블 생성
CREATE TEMPORARY TABLE temp_patterns (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_offset INT,
    read_flag BIT(1),
    sse_flag BIT(1), 
    fcm_flag BIT(1),
    time_offset INT
) ENGINE=MEMORY;

-- 1000개 패턴 생성
INSERT INTO temp_patterns (user_offset, read_flag, sse_flag, fcm_flag, time_offset)
SELECT 
    n % 500 as user_offset,
    (n % 10 < 3) as read_flag,
    (n % 10 < 8) as sse_flag,
    (n % 10 < 7) as fcm_flag,
    n % 86400 as time_offset
FROM (
    SELECT a.n + b.n*10 + c.n*100 as n
    FROM 
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a
        CROSS JOIN (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b  
        CROSS JOIN (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c
    WHERE a.n + b.n*10 + c.n*100 < 1000
) seq;

SELECT CONCAT('✅ 랜덤 패턴 준비 완료: ', NOW()) as pattern_ready;

-- 메가 삽입 프로시저
DELIMITER $$

CREATE PROCEDURE MegaInsert()
BEGIN
    DECLARE batch_num INT DEFAULT 0;
    DECLARE current_time DATETIME DEFAULT NOW();
    
    -- 100번의 배치 (각 배치당 10만개)
    WHILE batch_num < 100 DO
        
        -- 10만개 삽입
        INSERT INTO notification (
            user_id, type_id, content, is_read, sse_sent, fcm_sent, 
            created_at, modified_at
        )
        SELECT 
            100001 + patterns.user_offset as user_id,
            @notification_type_id as type_id,
            CONCAT('대용량 테스트 알림 #', batch_num * 100000 + nums.n + 1, ' - 사용자 ', 100001 + patterns.user_offset) as content,
            patterns.read_flag,
            patterns.sse_flag,
            patterns.fcm_flag,
            DATE_SUB(current_time, INTERVAL patterns.time_offset SECOND) as created_at,
            current_time as modified_at
        FROM (
            -- 10만개 숫자 생성
            SELECT a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 as n
            FROM 
                (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a
                CROSS JOIN (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b  
                CROSS JOIN (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c
                CROSS JOIN (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d
                CROSS JOIN (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) e
        ) nums
        INNER JOIN temp_patterns patterns ON patterns.id = (nums.n % 1000) + 1;
        
        COMMIT;
        SET batch_num = batch_num + 1;
        
        -- 진행 상황 출력 (5%마다)
        IF batch_num % 5 = 0 THEN
            SELECT CONCAT(
                '🚀 진행률: ', batch_num, '% (',
                FORMAT(batch_num * 100000, 0), ' / 10,000,000) | ',
                '⏱️ 경과: ', TIMESTAMPDIFF(MINUTE, @start_time, NOW()), 'min | ',
                '🎯 예상완료: ', DATE_ADD(NOW(), INTERVAL ((100-batch_num)*0.5) MINUTE)
            ) as progress;
        END IF;
        
    END WHILE;
    
END$$

DELIMITER ;

-- 🚀 실행
SELECT '🔥 메가 삽입 시작! 잠시만 기다리세요... 🔥' as start_insert;
CALL MegaInsert();

-- 정리
DROP PROCEDURE MegaInsert;
DROP TEMPORARY TABLE temp_patterns;

-- 설정 복원
SET SESSION autocommit = 1;
SET SESSION unique_checks = 1;
SET SESSION foreign_key_checks = 1;

-- 🎉 결과 확인
SELECT CONCAT('🎉🎉🎉 1천만건 생성 완료!!! 🎉🎉🎉') as success;
SELECT CONCAT('⏰ 총 소요시간: ', TIMESTAMPDIFF(MINUTE, @start_time, NOW()), '분 ', TIMESTAMPDIFF(SECOND, @start_time, NOW()) % 60, '초') as total_time;
SELECT CONCAT('📊 총 알림 수: ', FORMAT(COUNT(*), 0), '개') as final_count FROM notification;

-- 🏆 통계
SELECT '🏆 === 최종 통계 === 🏆' as stats_title;

SELECT 
    '카테고리' as category,
    '상태' as status,
    '개수' as count_display,
    '비율' as percentage_display
UNION ALL
SELECT 
    '📖 읽음상태',
    CASE WHEN is_read = 1 THEN '✅읽음' ELSE '📮안읽음' END,
    FORMAT(COUNT(*), 0),
    CONCAT(ROUND(COUNT(*) * 100.0 / (SELECT COUNT(*) FROM notification), 1), '%')
FROM notification GROUP BY is_read
UNION ALL
SELECT 
    '📡 SSE전송',
    CASE WHEN sse_sent = 1 THEN '🚀성공' ELSE '💥실패' END,
    FORMAT(COUNT(*), 0),
    CONCAT(ROUND(COUNT(*) * 100.0 / (SELECT COUNT(*) FROM notification), 1), '%')
FROM notification GROUP BY sse_sent
UNION ALL
SELECT 
    '📱 FCM전송',
    CASE WHEN fcm_sent = 1 THEN '📲성공' ELSE '📵실패' END,
    FORMAT(COUNT(*), 0),
    CONCAT(ROUND(COUNT(*) * 100.0 / (SELECT COUNT(*) FROM notification), 1), '%')
FROM notification GROUP BY fcm_sent;

-- 사용자별 알림 개수 TOP 10
SELECT 
    CONCAT('👤 사용자 ', user_id) as user_info,
    FORMAT(COUNT(*), 0) as notification_count
FROM notification 
GROUP BY user_id 
ORDER BY COUNT(*) DESC 
LIMIT 10;

SELECT '🎊 1천만건 대용량 데이터 생성 완료! 🎊' as celebration;