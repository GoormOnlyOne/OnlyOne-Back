-- 1천만건 알림 데이터 생성 스크립트 (Ultra High Performance)
-- 예상 실행 시간: 10-20분
-- 필요 디스크 공간: 최소 50GB

-- 극한 성능 최적화 설정
SET SESSION sql_log_bin = 0;
SET SESSION foreign_key_checks = 0;
SET SESSION unique_checks = 0;
SET SESSION autocommit = 0;
SET SESSION innodb_flush_log_at_trx_commit = 0;
SET SESSION sync_binlog = 0;
SET SESSION innodb_doublewrite = 0;
SET SESSION innodb_support_xa = 0;

-- 대용량 처리를 위한 세션 설정
SET SESSION max_heap_table_size = 1073741824; -- 1GB
SET SESSION tmp_table_size = 1073741824; -- 1GB
SET SESSION bulk_insert_buffer_size = 268435456; -- 256MB

SELECT CONCAT('🚀 1천만건 알림 데이터 생성 시작: ', NOW()) as start_message;

-- 알림 타입 ID 확인
SET @notification_type_id = (SELECT id FROM notification_type WHERE type = 'CHAT' LIMIT 1);

-- 임시 테이블 생성 (성능 향상을 위해)
CREATE TEMPORARY TABLE temp_numbers (n INT PRIMARY KEY);

-- 숫자 시퀀스 생성 (0-99,999)
INSERT INTO temp_numbers
SELECT a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 as n
FROM 
    (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a
    CROSS JOIN (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b  
    CROSS JOIN (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c
    CROSS JOIN (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d
    CROSS JOIN (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) e;

SELECT CONCAT('✅ 숫자 시퀀스 생성 완료 (', (SELECT COUNT(*) FROM temp_numbers), '개): ', NOW()) as seq_done;

-- 100만개씩 10번 배치로 1천만개 생성
DELIMITER $$

CREATE PROCEDURE InsertTenMillionNotifications()
BEGIN
    DECLARE batch_num INT DEFAULT 0;
    DECLARE offset_val INT DEFAULT 0;
    
    WHILE batch_num < 100 DO
        SET offset_val = batch_num * 100000;
        
        -- 10만개씩 배치 삽입
        INSERT INTO app_notification (user_id, notification_type_id, title, content, args, is_read, sse_sent, fcm_sent, created_at, updated_at)
        SELECT 
            100001 + ((n + offset_val) % 500) as user_id,
            @notification_type_id,
            CONCAT('대용량 알림 #', n + offset_val + 1) as title,
            CONCAT('성능 테스트용 메시지 번호 ', n + offset_val + 1, ' - 사용자 ', 100001 + ((n + offset_val) % 500)) as content,
            CONCAT('["msg', n + offset_val + 1, '"]') as args,
            ((n + offset_val + 1) % 10 < 3) as is_read,  -- 30% 읽음
            ((n + offset_val + 1) % 10 < 8) as sse_sent, -- 80% SSE 성공  
            ((n + offset_val + 1) % 10 < 7) as fcm_sent, -- 70% FCM 성공
            DATE_SUB(NOW(), INTERVAL ((n + offset_val) % 86400) SECOND) as created_at,
            NOW() as updated_at
        FROM temp_numbers
        WHERE n < 100000;
        
        COMMIT;
        
        SET batch_num = batch_num + 1;
        
        -- 진행 상황 출력 (매 10배치마다)
        IF batch_num % 10 = 0 THEN
            SELECT CONCAT(
                '🔥 진행률: ', 
                batch_num, 
                '% (', 
                FORMAT(batch_num * 100000, 0), 
                ' / 10,000,000) - ', 
                NOW(),
                ' - 예상 완료: ',
                DATE_ADD(NOW(), INTERVAL ((100 - batch_num) * 2) MINUTE)
            ) as mega_progress;
        END IF;
        
    END WHILE;
    
END$$

DELIMITER ;

-- 메인 프로시저 실행
CALL InsertTenMillionNotifications();

-- 정리
DROP PROCEDURE InsertTenMillionNotifications;
DROP TEMPORARY TABLE temp_numbers;

-- 성능 설정 복원
SET SESSION innodb_doublewrite = 1;
SET SESSION innodb_support_xa = 1;
SET SESSION innodb_flush_log_at_trx_commit = 1;
SET SESSION sync_binlog = 1;
SET SESSION autocommit = 1;
SET SESSION unique_checks = 1;
SET SESSION foreign_key_checks = 1;

-- 🎉 최종 결과
SELECT CONCAT('🎉 1천만건 생성 완료!!! 완료 시간: ', NOW()) as final_result;

SELECT 
    CONCAT('📊 총 알림 수: ', FORMAT(COUNT(*), 0)) as total_count
FROM app_notification;

-- 사용자별 분포 (상위 10명)
SELECT 
    CONCAT('👤 사용자 ', user_id, ': ', FORMAT(COUNT(*), 0), '개') as user_distribution
FROM app_notification 
GROUP BY user_id 
ORDER BY COUNT(*) DESC 
LIMIT 10;

-- 상세 통계
SELECT '📈 상세 통계' as stats_title;

SELECT 
    '📖 읽음 상태' as category,
    CASE WHEN is_read = 1 THEN '✅ 읽음' ELSE '📬 안읽음' END as status,
    FORMAT(COUNT(*), 0) as count,
    CONCAT(ROUND(COUNT(*) * 100.0 / (SELECT COUNT(*) FROM app_notification), 1), '%') as percentage
FROM app_notification 
GROUP BY is_read

UNION ALL

SELECT 
    '📡 SSE 전송' as category,
    CASE WHEN sse_sent = 1 THEN '✅ 성공' ELSE '❌ 실패' END as status,
    FORMAT(COUNT(*), 0) as count,
    CONCAT(ROUND(COUNT(*) * 100.0 / (SELECT COUNT(*) FROM app_notification), 1), '%') as percentage
FROM app_notification 
GROUP BY sse_sent

UNION ALL

SELECT 
    '📱 FCM 전송' as category,
    CASE WHEN fcm_sent = 1 THEN '✅ 성공' ELSE '❌ 실패' END as status,
    FORMAT(COUNT(*), 0) as count,
    CONCAT(ROUND(COUNT(*) * 100.0 / (SELECT COUNT(*) FROM app_notification), 1), '%') as percentage
FROM app_notification 
GROUP BY fcm_sent;

-- 테이블 크기 확인
SELECT 
    CONCAT('💾 테이블 크기: ', 
           ROUND(((data_length + index_length) / 1024 / 1024 / 1024), 2), 
           ' GB') as table_size,
    CONCAT('📄 데이터: ', 
           ROUND((data_length / 1024 / 1024 / 1024), 2), 
           ' GB') as data_size,
    CONCAT('🔍 인덱스: ', 
           ROUND((index_length / 1024 / 1024 / 1024), 2), 
           ' GB') as index_size
FROM information_schema.tables 
WHERE table_schema = 'buddkit' 
AND table_name = 'app_notification';

SELECT '🎯 1천만건 대용량 데이터 생성이 성공적으로 완료되었습니다!' as success_message;