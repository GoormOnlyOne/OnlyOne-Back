-- 1천만건 알림 데이터 생성 스크립트
-- 실행 전 확인사항:
-- 1. 충분한 디스크 공간 확보 (최소 50GB)
-- 2. innodb_buffer_pool_size 등 MySQL 설정 최적화
-- 3. 실행 시간은 약 30-60분 소요 예상

SET SESSION sql_log_bin = 0;
SET SESSION foreign_key_checks = 0;
SET SESSION unique_checks = 0;
SET SESSION autocommit = 0;

-- 배치 크기 설정
SET @batch_size = 10000;
SET @total_notifications = 10000000;
SET @batches = @total_notifications / @batch_size;

-- 시작 시간 기록
SELECT CONCAT('시작 시간: ', NOW()) as start_time;

-- 기존 알림 데이터 개수 확인
SELECT COUNT(*) as current_notification_count FROM app_notification;

-- 임시 프로시저 생성
DELIMITER $$

CREATE PROCEDURE InsertNotifications()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE batch_num INT DEFAULT 1;
    DECLARE user_id_val BIGINT;
    DECLARE notification_type_id_val BIGINT;
    DECLARE current_timestamp DATETIME DEFAULT NOW();
    
    -- 알림 타입 ID 가져오기 (CHAT 타입)
    SELECT id INTO notification_type_id_val 
    FROM notification_type 
    WHERE type = 'CHAT' 
    LIMIT 1;
    
    IF notification_type_id_val IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'CHAT 알림 타입을 찾을 수 없습니다.';
    END IF;
    
    -- 배치별로 데이터 삽입
    WHILE batch_num <= @batches DO
        
        -- 배치 시작
        START TRANSACTION;
        
        -- 현재 배치 삽입
        SET i = 1;
        WHILE i <= @batch_size DO
            -- 랜덤 사용자 ID 선택 (100001 ~ 100500)
            SET user_id_val = 100000 + (FLOOR(RAND() * 500) + 1);
            
            INSERT INTO app_notification (
                user_id,
                notification_type_id,
                title,
                content,
                args,
                is_read,
                sse_sent,
                fcm_sent,
                created_at,
                updated_at
            ) VALUES (
                user_id_val,
                notification_type_id_val,
                CONCAT('대용량 테스트 알림 ', (batch_num - 1) * @batch_size + i),
                CONCAT('사용자 ', user_id_val, '님을 위한 성능 테스트 알림입니다. 배치: ', batch_num, ', 순번: ', i),
                CONCAT('["테스트메시지', (batch_num - 1) * @batch_size + i, '"]'),
                CASE WHEN RAND() < 0.3 THEN 1 ELSE 0 END, -- 30% 읽음 처리
                CASE WHEN RAND() < 0.8 THEN 1 ELSE 0 END, -- 80% SSE 전송 성공
                CASE WHEN RAND() < 0.7 THEN 1 ELSE 0 END, -- 70% FCM 전송 성공
                DATE_SUB(current_timestamp, INTERVAL FLOOR(RAND() * 86400) SECOND), -- 최근 24시간 내 랜덤 시간
                current_timestamp
            );
            
            SET i = i + 1;
        END WHILE;
        
        -- 배치 커밋
        COMMIT;
        
        -- 진행 상황 출력 (매 50배치마다)
        IF batch_num % 50 = 0 THEN
            SELECT CONCAT(
                '진행률: ', 
                ROUND((batch_num / @batches) * 100, 1), 
                '% (', 
                FORMAT(batch_num * @batch_size, 0), 
                '/', 
                FORMAT(@total_notifications, 0), 
                ') - ', 
                NOW()
            ) as progress;
        END IF;
        
        SET batch_num = batch_num + 1;
    END WHILE;
    
END$$

DELIMITER ;

-- 프로시저 실행
CALL InsertNotifications();

-- 프로시저 삭제
DROP PROCEDURE InsertNotifications;

-- 설정 복원
SET SESSION autocommit = 1;
SET SESSION unique_checks = 1;
SET SESSION foreign_key_checks = 1;

-- 결과 확인
SELECT COUNT(*) as total_notification_count FROM app_notification;
SELECT 
    user_id,
    COUNT(*) as notification_count
FROM app_notification 
GROUP BY user_id 
ORDER BY notification_count DESC 
LIMIT 10;

-- 읽음/안읽음 통계
SELECT 
    is_read,
    COUNT(*) as count,
    ROUND(COUNT(*) * 100.0 / (SELECT COUNT(*) FROM app_notification), 2) as percentage
FROM app_notification 
GROUP BY is_read;

-- SSE/FCM 전송 통계
SELECT 
    CONCAT('SSE 전송: ', SUM(sse_sent), '/', COUNT(*), ' (', 
           ROUND(SUM(sse_sent) * 100.0 / COUNT(*), 2), '%)') as sse_stats
FROM app_notification
UNION ALL
SELECT 
    CONCAT('FCM 전송: ', SUM(fcm_sent), '/', COUNT(*), ' (', 
           ROUND(SUM(fcm_sent) * 100.0 / COUNT(*), 2), '%)') as fcm_stats
FROM app_notification;

-- 완료 시간 기록
SELECT CONCAT('완료 시간: ', NOW()) as end_time;

-- 인덱스 최적화 (필요시)
-- ANALYZE TABLE app_notification;
-- OPTIMIZE TABLE app_notification;