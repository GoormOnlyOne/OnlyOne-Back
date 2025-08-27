-- 🚀 ULTIMATE 1천만건 알림 데이터 생성 스크립트 
-- 목표: 최고 성능으로 1천만건을 5-15분 내 생성
-- 필요: 최소 50GB 디스크 공간, 8GB+ RAM

SELECT CONCAT('🔥🔥🔥 ULTIMATE 1천만건 생성 시작: ', NOW(), ' 🔥🔥🔥') as ultra_start;

-- 🎯 극한 성능 설정
SET SESSION sql_log_bin = 0;
SET SESSION foreign_key_checks = 0;
SET SESSION unique_checks = 0;
SET SESSION autocommit = 0;
SET SESSION innodb_flush_log_at_trx_commit = 0;
SET SESSION sync_binlog = 0;
SET SESSION innodb_doublewrite = 0;
SET SESSION innodb_support_xa = 0;
SET SESSION innodb_buffer_pool_dump_at_shutdown = 0;
SET SESSION innodb_adaptive_hash_index = 0;

-- 메모리 설정 극대화
SET SESSION max_heap_table_size = 2147483648; -- 2GB
SET SESSION tmp_table_size = 2147483648; -- 2GB  
SET SESSION bulk_insert_buffer_size = 536870912; -- 512MB
SET SESSION read_buffer_size = 8388608; -- 8MB
SET SESSION sort_buffer_size = 67108864; -- 64MB

-- 알림 타입 ID
SET @notification_type_id = (SELECT id FROM notification_type WHERE type = 'CHAT' LIMIT 1);
SET @start_time = NOW();

-- 🎲 랜덤 시드 테이블 생성 (성능 향상용)
CREATE TEMPORARY TABLE temp_random_seeds (
    id INT AUTO_INCREMENT PRIMARY KEY,
    user_offset INT,
    read_flag TINYINT,
    sse_flag TINYINT,
    fcm_flag TINYINT,
    time_offset INT
) ENGINE=MEMORY;

-- 랜덤 시드 데이터 생성 (1000개 패턴을 반복 사용)
INSERT INTO temp_random_seeds (user_offset, read_flag, sse_flag, fcm_flag, time_offset)
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

SELECT CONCAT('✅ 랜덤 시드 준비 완료: ', NOW()) as seed_ready;

-- 🚀 MEGA INSERT 함수
DELIMITER $$

CREATE PROCEDURE UltimateInsert()
BEGIN
    DECLARE batch_counter INT DEFAULT 0;
    DECLARE mega_batch INT DEFAULT 0;
    DECLARE current_time DATETIME DEFAULT NOW();
    
    -- 100개의 메가배치 (각 메가배치당 10만개)
    WHILE mega_batch < 100 DO
        
        -- 🔥 TURBO INSERT: 10만개를 한 번에!
        INSERT INTO app_notification (
            user_id, notification_type_id, title, content, args, 
            is_read, sse_sent, fcm_sent, created_at, updated_at
        )
        SELECT 
            100001 + seeds.user_offset as user_id,
            @notification_type_id,
            CONCAT('메가알림#', mega_batch * 100000 + nums.n + 1) as title,
            CONCAT('ULTRA메시지 ', mega_batch * 100000 + nums.n + 1, ' 👤USER:', 100001 + seeds.user_offset) as content,
            CONCAT('["ultra', mega_batch * 100000 + nums.n + 1, '"]') as args,
            seeds.read_flag,
            seeds.sse_flag,
            seeds.fcm_flag,
            DATE_SUB(current_time, INTERVAL seeds.time_offset SECOND) as created_at,
            current_time as updated_at
        FROM (
            -- 10만개 숫자 생성 (0-99999)
            SELECT a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 as n
            FROM 
                (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a
                CROSS JOIN (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b  
                CROSS JOIN (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c
                CROSS JOIN (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d
                CROSS JOIN (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) e
        ) nums
        INNER JOIN temp_random_seeds seeds ON seeds.id = (nums.n % 1000) + 1;
        
        COMMIT;
        SET mega_batch = mega_batch + 1;
        
        -- 🎯 진행률 체크 (5%마다)
        IF mega_batch % 5 = 0 THEN
            SELECT CONCAT(
                '⚡ ULTRA진행률: ', mega_batch, '% (',
                FORMAT(mega_batch * 100000, 0), ' / 10,000,000) | ',
                '⏱️ 경과: ', TIMESTAMPDIFF(MINUTE, @start_time, NOW()), 'min | ',
                '🎯 예상완료: ', DATE_ADD(NOW(), INTERVAL ((100-mega_batch)*0.3) MINUTE)
            ) as ultra_progress;
        END IF;
        
    END WHILE;
    
END$$

DELIMITER ;

-- 🚀 메인 실행
SELECT '🔥 ULTIMATE 알고리즘 가동! 잠시만 기다리세요... 🔥' as launching;
CALL UltimateInsert();

-- 정리
DROP PROCEDURE UltimateInsert;
DROP TEMPORARY TABLE temp_random_seeds;

-- 설정 복원
SET SESSION innodb_adaptive_hash_index = 1;
SET SESSION innodb_buffer_pool_dump_at_shutdown = 1;
SET SESSION innodb_doublewrite = 1;
SET SESSION innodb_support_xa = 1;
SET SESSION innodb_flush_log_at_trx_commit = 1;
SET SESSION sync_binlog = 1;
SET SESSION autocommit = 1;
SET SESSION unique_checks = 1;
SET SESSION foreign_key_checks = 1;

-- 🎉🎉🎉 VICTORY!!! 🎉🎉🎉
SELECT CONCAT('🎉🎉🎉 ULTIMATE SUCCESS!!! 🎉🎉🎉') as victory_title;
SELECT CONCAT('⏰ 총 소요시간: ', TIMESTAMPDIFF(MINUTE, @start_time, NOW()), '분 ', TIMESTAMPDIFF(SECOND, @start_time, NOW()) % 60, '초') as total_time;
SELECT CONCAT('📊 최종 레코드 수: ', FORMAT(COUNT(*), 0), '개') as final_count FROM app_notification;
SELECT CONCAT('⚡ 평균 처리속도: ', FORMAT(COUNT(*)/(TIMESTAMPDIFF(SECOND, @start_time, NOW())), 0), '개/초') as avg_speed FROM app_notification;

-- 🏆 CHAMPION 통계
SELECT '🏆 === CHAMPION STATISTICS === 🏆' as champion_stats;

SELECT 
    '🎯 카테고리' as category,
    '📊 상태' as status,
    '🔢 개수' as count_col,
    '📈 비율' as percentage_col
UNION ALL
SELECT 
    '📖 읽음상태',
    CASE WHEN is_read = 1 THEN '✅읽음' ELSE '📮안읽음' END,
    FORMAT(COUNT(*), 0),
    CONCAT(ROUND(COUNT(*) * 100.0 / 10000000, 1), '%')
FROM app_notification GROUP BY is_read
UNION ALL
SELECT 
    '📡 SSE전송',
    CASE WHEN sse_sent = 1 THEN '🚀성공' ELSE '💥실패' END,
    FORMAT(COUNT(*), 0),
    CONCAT(ROUND(COUNT(*) * 100.0 / 10000000, 1), '%')
FROM app_notification GROUP BY sse_sent
UNION ALL
SELECT 
    '📱 FCM전송',
    CASE WHEN fcm_sent = 1 THEN '📲성공' ELSE '📵실패' END,
    FORMAT(COUNT(*), 0),
    CONCAT(ROUND(COUNT(*) * 100.0 / 10000000, 1), '%')
FROM app_notification GROUP BY fcm_sent;

-- 🎖️ TOP 사용자 (알림 많이 받은 순)
SELECT '🎖️ TOP 사용자들 (알림 보유량)' as top_users_title;
SELECT 
    CONCAT('🥇 ', ROW_NUMBER() OVER(ORDER BY COUNT(*) DESC), '위') as ranking,
    CONCAT('👤 사용자', user_id) as user_name,
    FORMAT(COUNT(*), 0) as notification_count
FROM app_notification 
GROUP BY user_id 
ORDER BY COUNT(*) DESC 
LIMIT 5;

-- 💾 스토리지 사용량
SELECT 
    '💾 스토리지 정보' as storage_info,
    CONCAT(ROUND(((data_length + index_length) / 1024 / 1024 / 1024), 2), ' GB') as total_size,
    CONCAT(ROUND((data_length / 1024 / 1024 / 1024), 2), ' GB') as data_size,
    CONCAT(ROUND((index_length / 1024 / 1024 / 1024), 2), ' GB') as index_size
FROM information_schema.tables 
WHERE table_schema = DATABASE() AND table_name = 'app_notification';

SELECT '🎊 1천만건 ULTIMATE 생성 미션 완료! 축하합니다! 🎊' as final_celebration;