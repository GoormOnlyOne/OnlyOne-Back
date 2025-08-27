-- 더 빠른 100만건 알림 데이터 생성 스크립트 (대용량 INSERT 최적화)
-- 예상 실행 시간: 2-5분

-- 성능 최적화 설정
SET SESSION sql_log_bin = 0;
SET SESSION foreign_key_checks = 0;
SET SESSION unique_checks = 0;
SET SESSION autocommit = 0;
SET SESSION innodb_flush_log_at_trx_commit = 0;
SET SESSION sync_binlog = 0;

SELECT CONCAT('시작: ', NOW()) as start_time;

-- 알림 타입 ID 확인
SET @notification_type_id = (SELECT id FROM notification_type WHERE type = 'CHAT' LIMIT 1);

-- 대용량 INSERT를 위한 VALUES 구문 생성 및 실행
-- 10만개씩 10번 실행하여 총 100만개 생성

-- 배치 1: 1-100,000
INSERT INTO app_notification (user_id, notification_type_id, title, content, args, is_read, sse_sent, fcm_sent, created_at, updated_at)
SELECT 
    100001 + (seq.n % 500) as user_id,
    @notification_type_id,
    CONCAT('대용량 테스트 알림 ', seq.n) as title,
    CONCAT('성능 테스트용 알림 메시지 #', seq.n) as content,
    CONCAT('["테스트메시지', seq.n, '"]') as args,
    (seq.n % 10 < 3) as is_read,  -- 30% 읽음
    (seq.n % 10 < 8) as sse_sent, -- 80% SSE 성공  
    (seq.n % 10 < 7) as fcm_sent, -- 70% FCM 성공
    DATE_SUB(NOW(), INTERVAL (seq.n % 86400) SECOND) as created_at,
    NOW() as updated_at
FROM (
    SELECT a.n + b.n*1000 + c.n*10000 + d.n*100000 as n
    FROM 
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a
        CROSS JOIN (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b  
        CROSS JOIN (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c
        CROSS JOIN (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d
    WHERE a.n + b.n*1000 + c.n*10000 + d.n*100000 < 100000
) seq;

COMMIT;
SELECT CONCAT('배치 1 완료 (100K): ', NOW()) as batch1_done;

-- 배치 2: 100,001-200,000  
INSERT INTO app_notification (user_id, notification_type_id, title, content, args, is_read, sse_sent, fcm_sent, created_at, updated_at)
SELECT 
    100001 + ((seq.n + 100000) % 500) as user_id,
    @notification_type_id,
    CONCAT('대용량 테스트 알림 ', seq.n + 100000) as title,
    CONCAT('성능 테스트용 알림 메시지 #', seq.n + 100000) as content,
    CONCAT('["테스트메시지', seq.n + 100000, '"]') as args,
    ((seq.n + 100000) % 10 < 3) as is_read,
    ((seq.n + 100000) % 10 < 8) as sse_sent,
    ((seq.n + 100000) % 10 < 7) as fcm_sent,
    DATE_SUB(NOW(), INTERVAL ((seq.n + 100000) % 86400) SECOND) as created_at,
    NOW() as updated_at
FROM (
    SELECT a.n + b.n*1000 + c.n*10000 + d.n*100000 as n
    FROM 
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a
        CROSS JOIN (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b  
        CROSS JOIN (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c
        CROSS JOIN (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d
    WHERE a.n + b.n*1000 + c.n*10000 + d.n*100000 < 100000
) seq;

COMMIT;
SELECT CONCAT('배치 2 완료 (200K): ', NOW()) as batch2_done;

-- 나머지 8개 배치도 동일하게 생성...
-- (간단히 하기 위해 반복문 사용)

DELIMITER $$

CREATE PROCEDURE InsertRemainingBatches()
BEGIN
    DECLARE batch_num INT DEFAULT 3;
    DECLARE offset_val INT;
    
    WHILE batch_num <= 10 DO
        SET offset_val = (batch_num - 1) * 100000;
        
        SET @sql = CONCAT('
        INSERT INTO app_notification (user_id, notification_type_id, title, content, args, is_read, sse_sent, fcm_sent, created_at, updated_at)
        SELECT 
            100001 + ((seq.n + ', offset_val, ') % 500) as user_id,
            ', @notification_type_id, ',
            CONCAT("대용량 테스트 알림 ", seq.n + ', offset_val, ') as title,
            CONCAT("성능 테스트용 알림 메시지 #", seq.n + ', offset_val, ') as content,
            CONCAT(''["테스트메시지'', seq.n + ', offset_val, ', '']'') as args,
            ((seq.n + ', offset_val, ') % 10 < 3) as is_read,
            ((seq.n + ', offset_val, ') % 10 < 8) as sse_sent,
            ((seq.n + ', offset_val, ') % 10 < 7) as fcm_sent,
            DATE_SUB(NOW(), INTERVAL ((seq.n + ', offset_val, ') % 86400) SECOND) as created_at,
            NOW() as updated_at
        FROM (
            SELECT a.n + b.n*1000 + c.n*10000 + d.n*100000 as n
            FROM 
                (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a
                CROSS JOIN (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b  
                CROSS JOIN (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c
                CROSS JOIN (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d
            WHERE a.n + b.n*1000 + c.n*10000 + d.n*100000 < 100000
        ) seq;
        ');
        
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
        
        COMMIT;
        
        SELECT CONCAT('배치 ', batch_num, ' 완료 (', batch_num * 100, 'K): ', NOW()) as progress;
        
        SET batch_num = batch_num + 1;
    END WHILE;
END$$

DELIMITER ;

CALL InsertRemainingBatches();
DROP PROCEDURE InsertRemainingBatches;

-- 설정 복원
SET SESSION innodb_flush_log_at_trx_commit = 1;
SET SESSION sync_binlog = 1;
SET SESSION autocommit = 1;
SET SESSION unique_checks = 1;
SET SESSION foreign_key_checks = 1;

-- 결과 확인
SELECT COUNT(*) as total_notifications FROM app_notification;
SELECT CONCAT('완료: ', NOW()) as end_time;

-- 통계
SELECT 
    '읽음 여부' as category,
    CASE WHEN is_read = 1 THEN '읽음' ELSE '안읽음' END as status,
    COUNT(*) as count,
    ROUND(COUNT(*) * 100.0 / (SELECT COUNT(*) FROM app_notification), 2) as percentage
FROM app_notification 
GROUP BY is_read
UNION ALL
SELECT 
    'SSE 전송' as category,
    CASE WHEN sse_sent = 1 THEN '성공' ELSE '실패' END as status,
    COUNT(*) as count,
    ROUND(COUNT(*) * 100.0 / (SELECT COUNT(*) FROM app_notification), 2) as percentage
FROM app_notification 
GROUP BY sse_sent
UNION ALL
SELECT 
    'FCM 전송' as category,
    CASE WHEN fcm_sent = 1 THEN '성공' ELSE '실패' END as status,
    COUNT(*) as count,
    ROUND(COUNT(*) * 100.0 / (SELECT COUNT(*) FROM app_notification), 2) as percentage
FROM app_notification 
GROUP BY fcm_sent;