-- 🚀 1천만건 알림 생성 (50만건씩 20배치)
-- 안전하고 확실한 배치 처리 방식

SELECT CONCAT('🔥 1천만건 배치 생성 시작: ', NOW()) as batch_start;

-- 성능 최적화 설정
SET SESSION foreign_key_checks = 0;
SET SESSION unique_checks = 0;
SET SESSION autocommit = 0;
SET SESSION max_heap_table_size = 2147483648; -- 2GB
SET SESSION tmp_table_size = 2147483648; -- 2GB
SET SESSION bulk_insert_buffer_size = 536870912; -- 512MB

-- 알림 타입 ID 확인
SET @notification_type_id = (SELECT type_id FROM notification_type WHERE type = 'CHAT' LIMIT 1);
SET @start_time = NOW();

SELECT CONCAT('📋 알림 타입 ID: ', @notification_type_id) as type_info;
SELECT CONCAT('📊 현재 알림 개수: ', FORMAT(COUNT(*), 0)) as before_count FROM notification;
SELECT '🚀 50만건씩 20배치로 1천만건 생성 시작!' as batch_info;


-- ========== 배치 1: 1-500,000 ==========
SELECT CONCAT('🚀 배치 1/20 시작 (0.0M-0.5M): ', NOW()) as batch1_start;
INSERT INTO notification (user_id, type_id, content, is_read, sse_sent, fcm_sent, created_at, modified_at)
SELECT 
    100001 + ((n + 0) % 500) as user_id,
    @notification_type_id as type_id,
    CONCAT('🔥배치알림#', n + 1, '👤', 100001 + ((n + 0) % 500)) as content,
    ((n + 0) % 10 < 3) as is_read,
    ((n + 0) % 10 < 8) as sse_sent, 
    ((n + 0) % 10 < 7) as fcm_sent,
    DATE_SUB(NOW(), INTERVAL ((n + 0) % 86400) SECOND) as created_at,
    NOW() as modified_at
FROM (
    SELECT a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 as n
    FROM 
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4) f,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) e
    WHERE a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 < 500000
) numbers;
COMMIT;
SELECT CONCAT('✅ 배치 1 완료 (0.5M): ', NOW(), ' | 진행률: 5%') as batch1_done;


-- ========== 배치 2: 500,001-1,000,000 ==========
SELECT CONCAT('🚀 배치 2/20 시작 (0.5M-1.0M): ', NOW()) as batch2_start;
INSERT INTO notification (user_id, type_id, content, is_read, sse_sent, fcm_sent, created_at, modified_at)
SELECT 
    100001 + ((n + 500000) % 500) as user_id,
    @notification_type_id as type_id,
    CONCAT('🔥배치알림#', n + 500001, '👤', 100001 + ((n + 500000) % 500)) as content,
    ((n + 500000) % 10 < 3) as is_read,
    ((n + 500000) % 10 < 8) as sse_sent, 
    ((n + 500000) % 10 < 7) as fcm_sent,
    DATE_SUB(NOW(), INTERVAL ((n + 500000) % 86400) SECOND) as created_at,
    NOW() as modified_at
FROM (
    SELECT a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 as n
    FROM 
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4) f,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) e
    WHERE a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 < 500000
) numbers;
COMMIT;
SELECT CONCAT('✅ 배치 2 완료 (1.0M): ', NOW(), ' | 진행률: 10%') as batch2_done;


-- ========== 배치 3: 1,000,001-1,500,000 ==========
SELECT CONCAT('🚀 배치 3/20 시작 (1.0M-1.5M): ', NOW()) as batch3_start;
INSERT INTO notification (user_id, type_id, content, is_read, sse_sent, fcm_sent, created_at, modified_at)
SELECT 
    100001 + ((n + 1000000) % 500) as user_id,
    @notification_type_id as type_id,
    CONCAT('🔥배치알림#', n + 1000001, '👤', 100001 + ((n + 1000000) % 500)) as content,
    ((n + 1000000) % 10 < 3) as is_read,
    ((n + 1000000) % 10 < 8) as sse_sent, 
    ((n + 1000000) % 10 < 7) as fcm_sent,
    DATE_SUB(NOW(), INTERVAL ((n + 1000000) % 86400) SECOND) as created_at,
    NOW() as modified_at
FROM (
    SELECT a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 as n
    FROM 
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4) f,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) e
    WHERE a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 < 500000
) numbers;
COMMIT;
SELECT CONCAT('✅ 배치 3 완료 (1.5M): ', NOW(), ' | 진행률: 15%') as batch3_done;


-- ========== 배치 4: 1,500,001-2,000,000 ==========
SELECT CONCAT('🚀 배치 4/20 시작 (1.5M-2.0M): ', NOW()) as batch4_start;
INSERT INTO notification (user_id, type_id, content, is_read, sse_sent, fcm_sent, created_at, modified_at)
SELECT 
    100001 + ((n + 1500000) % 500) as user_id,
    @notification_type_id as type_id,
    CONCAT('🔥배치알림#', n + 1500001, '👤', 100001 + ((n + 1500000) % 500)) as content,
    ((n + 1500000) % 10 < 3) as is_read,
    ((n + 1500000) % 10 < 8) as sse_sent, 
    ((n + 1500000) % 10 < 7) as fcm_sent,
    DATE_SUB(NOW(), INTERVAL ((n + 1500000) % 86400) SECOND) as created_at,
    NOW() as modified_at
FROM (
    SELECT a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 as n
    FROM 
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4) f,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) e
    WHERE a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 < 500000
) numbers;
COMMIT;
SELECT CONCAT('✅ 배치 4 완료 (2.0M): ', NOW(), ' | 진행률: 20%') as batch4_done;


-- ========== 배치 5: 2,000,001-2,500,000 ==========
SELECT CONCAT('🚀 배치 5/20 시작 (2.0M-2.5M): ', NOW()) as batch5_start;
INSERT INTO notification (user_id, type_id, content, is_read, sse_sent, fcm_sent, created_at, modified_at)
SELECT 
    100001 + ((n + 2000000) % 500) as user_id,
    @notification_type_id as type_id,
    CONCAT('🔥배치알림#', n + 2000001, '👤', 100001 + ((n + 2000000) % 500)) as content,
    ((n + 2000000) % 10 < 3) as is_read,
    ((n + 2000000) % 10 < 8) as sse_sent, 
    ((n + 2000000) % 10 < 7) as fcm_sent,
    DATE_SUB(NOW(), INTERVAL ((n + 2000000) % 86400) SECOND) as created_at,
    NOW() as modified_at
FROM (
    SELECT a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 as n
    FROM 
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4) f,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) e
    WHERE a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 < 500000
) numbers;
COMMIT;
SELECT CONCAT('✅ 배치 5 완료 (2.5M): ', NOW(), ' | 진행률: 25%') as batch5_done;


-- ========== 배치 6: 2,500,001-3,000,000 ==========
SELECT CONCAT('🚀 배치 6/20 시작 (2.5M-3.0M): ', NOW()) as batch6_start;
INSERT INTO notification (user_id, type_id, content, is_read, sse_sent, fcm_sent, created_at, modified_at)
SELECT 
    100001 + ((n + 2500000) % 500) as user_id,
    @notification_type_id as type_id,
    CONCAT('🔥배치알림#', n + 2500001, '👤', 100001 + ((n + 2500000) % 500)) as content,
    ((n + 2500000) % 10 < 3) as is_read,
    ((n + 2500000) % 10 < 8) as sse_sent, 
    ((n + 2500000) % 10 < 7) as fcm_sent,
    DATE_SUB(NOW(), INTERVAL ((n + 2500000) % 86400) SECOND) as created_at,
    NOW() as modified_at
FROM (
    SELECT a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 as n
    FROM 
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4) f,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) e
    WHERE a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 < 500000
) numbers;
COMMIT;
SELECT CONCAT('✅ 배치 6 완료 (3.0M): ', NOW(), ' | 진행률: 30%') as batch6_done;


-- ========== 배치 7: 3,000,001-3,500,000 ==========
SELECT CONCAT('🚀 배치 7/20 시작 (3.0M-3.5M): ', NOW()) as batch7_start;
INSERT INTO notification (user_id, type_id, content, is_read, sse_sent, fcm_sent, created_at, modified_at)
SELECT 
    100001 + ((n + 3000000) % 500) as user_id,
    @notification_type_id as type_id,
    CONCAT('🔥배치알림#', n + 3000001, '👤', 100001 + ((n + 3000000) % 500)) as content,
    ((n + 3000000) % 10 < 3) as is_read,
    ((n + 3000000) % 10 < 8) as sse_sent, 
    ((n + 3000000) % 10 < 7) as fcm_sent,
    DATE_SUB(NOW(), INTERVAL ((n + 3000000) % 86400) SECOND) as created_at,
    NOW() as modified_at
FROM (
    SELECT a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 as n
    FROM 
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4) f,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) e
    WHERE a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 < 500000
) numbers;
COMMIT;
SELECT CONCAT('✅ 배치 7 완료 (3.5M): ', NOW(), ' | 진행률: 35%') as batch7_done;


-- ========== 배치 8: 3,500,001-4,000,000 ==========
SELECT CONCAT('🚀 배치 8/20 시작 (3.5M-4.0M): ', NOW()) as batch8_start;
INSERT INTO notification (user_id, type_id, content, is_read, sse_sent, fcm_sent, created_at, modified_at)
SELECT 
    100001 + ((n + 3500000) % 500) as user_id,
    @notification_type_id as type_id,
    CONCAT('🔥배치알림#', n + 3500001, '👤', 100001 + ((n + 3500000) % 500)) as content,
    ((n + 3500000) % 10 < 3) as is_read,
    ((n + 3500000) % 10 < 8) as sse_sent, 
    ((n + 3500000) % 10 < 7) as fcm_sent,
    DATE_SUB(NOW(), INTERVAL ((n + 3500000) % 86400) SECOND) as created_at,
    NOW() as modified_at
FROM (
    SELECT a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 as n
    FROM 
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4) f,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) e
    WHERE a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 < 500000
) numbers;
COMMIT;
SELECT CONCAT('✅ 배치 8 완료 (4.0M): ', NOW(), ' | 진행률: 40%') as batch8_done;


-- ========== 배치 9: 4,000,001-4,500,000 ==========
SELECT CONCAT('🚀 배치 9/20 시작 (4.0M-4.5M): ', NOW()) as batch9_start;
INSERT INTO notification (user_id, type_id, content, is_read, sse_sent, fcm_sent, created_at, modified_at)
SELECT 
    100001 + ((n + 4000000) % 500) as user_id,
    @notification_type_id as type_id,
    CONCAT('🔥배치알림#', n + 4000001, '👤', 100001 + ((n + 4000000) % 500)) as content,
    ((n + 4000000) % 10 < 3) as is_read,
    ((n + 4000000) % 10 < 8) as sse_sent, 
    ((n + 4000000) % 10 < 7) as fcm_sent,
    DATE_SUB(NOW(), INTERVAL ((n + 4000000) % 86400) SECOND) as created_at,
    NOW() as modified_at
FROM (
    SELECT a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 as n
    FROM 
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4) f,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) e
    WHERE a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 < 500000
) numbers;
COMMIT;
SELECT CONCAT('✅ 배치 9 완료 (4.5M): ', NOW(), ' | 진행률: 45%') as batch9_done;


-- ========== 배치 10: 4,500,001-5,000,000 ==========
SELECT CONCAT('🚀 배치 10/20 시작 (4.5M-5.0M): ', NOW()) as batch10_start;
INSERT INTO notification (user_id, type_id, content, is_read, sse_sent, fcm_sent, created_at, modified_at)
SELECT 
    100001 + ((n + 4500000) % 500) as user_id,
    @notification_type_id as type_id,
    CONCAT('🔥배치알림#', n + 4500001, '👤', 100001 + ((n + 4500000) % 500)) as content,
    ((n + 4500000) % 10 < 3) as is_read,
    ((n + 4500000) % 10 < 8) as sse_sent, 
    ((n + 4500000) % 10 < 7) as fcm_sent,
    DATE_SUB(NOW(), INTERVAL ((n + 4500000) % 86400) SECOND) as created_at,
    NOW() as modified_at
FROM (
    SELECT a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 as n
    FROM 
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4) f,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) e
    WHERE a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 < 500000
) numbers;
COMMIT;
SELECT CONCAT('✅ 배치 10 완료 (5.0M): ', NOW(), ' | 진행률: 50%') as batch10_done;


-- ========== 배치 11: 5,000,001-5,500,000 ==========
SELECT CONCAT('🚀 배치 11/20 시작 (5.0M-5.5M): ', NOW()) as batch11_start;
INSERT INTO notification (user_id, type_id, content, is_read, sse_sent, fcm_sent, created_at, modified_at)
SELECT 
    100001 + ((n + 5000000) % 500) as user_id,
    @notification_type_id as type_id,
    CONCAT('🔥배치알림#', n + 5000001, '👤', 100001 + ((n + 5000000) % 500)) as content,
    ((n + 5000000) % 10 < 3) as is_read,
    ((n + 5000000) % 10 < 8) as sse_sent, 
    ((n + 5000000) % 10 < 7) as fcm_sent,
    DATE_SUB(NOW(), INTERVAL ((n + 5000000) % 86400) SECOND) as created_at,
    NOW() as modified_at
FROM (
    SELECT a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 as n
    FROM 
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4) f,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) e
    WHERE a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 < 500000
) numbers;
COMMIT;
SELECT CONCAT('✅ 배치 11 완료 (5.5M): ', NOW(), ' | 진행률: 55%') as batch11_done;


-- ========== 배치 12: 5,500,001-6,000,000 ==========
SELECT CONCAT('🚀 배치 12/20 시작 (5.5M-6.0M): ', NOW()) as batch12_start;
INSERT INTO notification (user_id, type_id, content, is_read, sse_sent, fcm_sent, created_at, modified_at)
SELECT 
    100001 + ((n + 5500000) % 500) as user_id,
    @notification_type_id as type_id,
    CONCAT('🔥배치알림#', n + 5500001, '👤', 100001 + ((n + 5500000) % 500)) as content,
    ((n + 5500000) % 10 < 3) as is_read,
    ((n + 5500000) % 10 < 8) as sse_sent, 
    ((n + 5500000) % 10 < 7) as fcm_sent,
    DATE_SUB(NOW(), INTERVAL ((n + 5500000) % 86400) SECOND) as created_at,
    NOW() as modified_at
FROM (
    SELECT a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 as n
    FROM 
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4) f,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) e
    WHERE a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 < 500000
) numbers;
COMMIT;
SELECT CONCAT('✅ 배치 12 완료 (6.0M): ', NOW(), ' | 진행률: 60%') as batch12_done;


-- ========== 배치 13: 6,000,001-6,500,000 ==========
SELECT CONCAT('🚀 배치 13/20 시작 (6.0M-6.5M): ', NOW()) as batch13_start;
INSERT INTO notification (user_id, type_id, content, is_read, sse_sent, fcm_sent, created_at, modified_at)
SELECT 
    100001 + ((n + 6000000) % 500) as user_id,
    @notification_type_id as type_id,
    CONCAT('🔥배치알림#', n + 6000001, '👤', 100001 + ((n + 6000000) % 500)) as content,
    ((n + 6000000) % 10 < 3) as is_read,
    ((n + 6000000) % 10 < 8) as sse_sent, 
    ((n + 6000000) % 10 < 7) as fcm_sent,
    DATE_SUB(NOW(), INTERVAL ((n + 6000000) % 86400) SECOND) as created_at,
    NOW() as modified_at
FROM (
    SELECT a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 as n
    FROM 
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4) f,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) e
    WHERE a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 < 500000
) numbers;
COMMIT;
SELECT CONCAT('✅ 배치 13 완료 (6.5M): ', NOW(), ' | 진행률: 65%') as batch13_done;


-- ========== 배치 14: 6,500,001-7,000,000 ==========
SELECT CONCAT('🚀 배치 14/20 시작 (6.5M-7.0M): ', NOW()) as batch14_start;
INSERT INTO notification (user_id, type_id, content, is_read, sse_sent, fcm_sent, created_at, modified_at)
SELECT 
    100001 + ((n + 6500000) % 500) as user_id,
    @notification_type_id as type_id,
    CONCAT('🔥배치알림#', n + 6500001, '👤', 100001 + ((n + 6500000) % 500)) as content,
    ((n + 6500000) % 10 < 3) as is_read,
    ((n + 6500000) % 10 < 8) as sse_sent, 
    ((n + 6500000) % 10 < 7) as fcm_sent,
    DATE_SUB(NOW(), INTERVAL ((n + 6500000) % 86400) SECOND) as created_at,
    NOW() as modified_at
FROM (
    SELECT a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 as n
    FROM 
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4) f,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) e
    WHERE a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 < 500000
) numbers;
COMMIT;
SELECT CONCAT('✅ 배치 14 완료 (7.0M): ', NOW(), ' | 진행률: 70%') as batch14_done;


-- ========== 배치 15: 7,000,001-7,500,000 ==========
SELECT CONCAT('🚀 배치 15/20 시작 (7.0M-7.5M): ', NOW()) as batch15_start;
INSERT INTO notification (user_id, type_id, content, is_read, sse_sent, fcm_sent, created_at, modified_at)
SELECT 
    100001 + ((n + 7000000) % 500) as user_id,
    @notification_type_id as type_id,
    CONCAT('🔥배치알림#', n + 7000001, '👤', 100001 + ((n + 7000000) % 500)) as content,
    ((n + 7000000) % 10 < 3) as is_read,
    ((n + 7000000) % 10 < 8) as sse_sent, 
    ((n + 7000000) % 10 < 7) as fcm_sent,
    DATE_SUB(NOW(), INTERVAL ((n + 7000000) % 86400) SECOND) as created_at,
    NOW() as modified_at
FROM (
    SELECT a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 as n
    FROM 
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4) f,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) e
    WHERE a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 < 500000
) numbers;
COMMIT;
SELECT CONCAT('✅ 배치 15 완료 (7.5M): ', NOW(), ' | 진행률: 75%') as batch15_done;


-- ========== 배치 16: 7,500,001-8,000,000 ==========
SELECT CONCAT('🚀 배치 16/20 시작 (7.5M-8.0M): ', NOW()) as batch16_start;
INSERT INTO notification (user_id, type_id, content, is_read, sse_sent, fcm_sent, created_at, modified_at)
SELECT 
    100001 + ((n + 7500000) % 500) as user_id,
    @notification_type_id as type_id,
    CONCAT('🔥배치알림#', n + 7500001, '👤', 100001 + ((n + 7500000) % 500)) as content,
    ((n + 7500000) % 10 < 3) as is_read,
    ((n + 7500000) % 10 < 8) as sse_sent, 
    ((n + 7500000) % 10 < 7) as fcm_sent,
    DATE_SUB(NOW(), INTERVAL ((n + 7500000) % 86400) SECOND) as created_at,
    NOW() as modified_at
FROM (
    SELECT a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 as n
    FROM 
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4) f,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) e
    WHERE a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 < 500000
) numbers;
COMMIT;
SELECT CONCAT('✅ 배치 16 완료 (8.0M): ', NOW(), ' | 진행률: 80%') as batch16_done;


-- ========== 배치 17: 8,000,001-8,500,000 ==========
SELECT CONCAT('🚀 배치 17/20 시작 (8.0M-8.5M): ', NOW()) as batch17_start;
INSERT INTO notification (user_id, type_id, content, is_read, sse_sent, fcm_sent, created_at, modified_at)
SELECT 
    100001 + ((n + 8000000) % 500) as user_id,
    @notification_type_id as type_id,
    CONCAT('🔥배치알림#', n + 8000001, '👤', 100001 + ((n + 8000000) % 500)) as content,
    ((n + 8000000) % 10 < 3) as is_read,
    ((n + 8000000) % 10 < 8) as sse_sent, 
    ((n + 8000000) % 10 < 7) as fcm_sent,
    DATE_SUB(NOW(), INTERVAL ((n + 8000000) % 86400) SECOND) as created_at,
    NOW() as modified_at
FROM (
    SELECT a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 as n
    FROM 
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4) f,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) e
    WHERE a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 < 500000
) numbers;
COMMIT;
SELECT CONCAT('✅ 배치 17 완료 (8.5M): ', NOW(), ' | 진행률: 85%') as batch17_done;


-- ========== 배치 18: 8,500,001-9,000,000 ==========
SELECT CONCAT('🚀 배치 18/20 시작 (8.5M-9.0M): ', NOW()) as batch18_start;
INSERT INTO notification (user_id, type_id, content, is_read, sse_sent, fcm_sent, created_at, modified_at)
SELECT 
    100001 + ((n + 8500000) % 500) as user_id,
    @notification_type_id as type_id,
    CONCAT('🔥배치알림#', n + 8500001, '👤', 100001 + ((n + 8500000) % 500)) as content,
    ((n + 8500000) % 10 < 3) as is_read,
    ((n + 8500000) % 10 < 8) as sse_sent, 
    ((n + 8500000) % 10 < 7) as fcm_sent,
    DATE_SUB(NOW(), INTERVAL ((n + 8500000) % 86400) SECOND) as created_at,
    NOW() as modified_at
FROM (
    SELECT a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 as n
    FROM 
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4) f,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) e
    WHERE a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 < 500000
) numbers;
COMMIT;
SELECT CONCAT('✅ 배치 18 완료 (9.0M): ', NOW(), ' | 진행률: 90%') as batch18_done;


-- ========== 배치 19: 9,000,001-9,500,000 ==========
SELECT CONCAT('🚀 배치 19/20 시작 (9.0M-9.5M): ', NOW()) as batch19_start;
INSERT INTO notification (user_id, type_id, content, is_read, sse_sent, fcm_sent, created_at, modified_at)
SELECT 
    100001 + ((n + 9000000) % 500) as user_id,
    @notification_type_id as type_id,
    CONCAT('🔥배치알림#', n + 9000001, '👤', 100001 + ((n + 9000000) % 500)) as content,
    ((n + 9000000) % 10 < 3) as is_read,
    ((n + 9000000) % 10 < 8) as sse_sent, 
    ((n + 9000000) % 10 < 7) as fcm_sent,
    DATE_SUB(NOW(), INTERVAL ((n + 9000000) % 86400) SECOND) as created_at,
    NOW() as modified_at
FROM (
    SELECT a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 as n
    FROM 
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4) f,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) e
    WHERE a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 < 500000
) numbers;
COMMIT;
SELECT CONCAT('✅ 배치 19 완료 (9.5M): ', NOW(), ' | 진행률: 95%') as batch19_done;


-- ========== 배치 20: 9,500,001-10,000,000 ==========
SELECT CONCAT('🚀 배치 20/20 시작 (9.5M-10.0M): ', NOW()) as batch20_start;
INSERT INTO notification (user_id, type_id, content, is_read, sse_sent, fcm_sent, created_at, modified_at)
SELECT 
    100001 + ((n + 9500000) % 500) as user_id,
    @notification_type_id as type_id,
    CONCAT('🔥배치알림#', n + 9500001, '👤', 100001 + ((n + 9500000) % 500)) as content,
    ((n + 9500000) % 10 < 3) as is_read,
    ((n + 9500000) % 10 < 8) as sse_sent, 
    ((n + 9500000) % 10 < 7) as fcm_sent,
    DATE_SUB(NOW(), INTERVAL ((n + 9500000) % 86400) SECOND) as created_at,
    NOW() as modified_at
FROM (
    SELECT a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 as n
    FROM 
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4) f,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d,
        (SELECT 0 as n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) e
    WHERE a.n + b.n*10 + c.n*100 + d.n*1000 + e.n*10000 + f.n*100000 < 500000
) numbers;
COMMIT;
SELECT CONCAT('✅ 배치 20 완료 (10.0M): ', NOW(), ' | 진행률: 100%') as batch20_done;


-- 설정 복원
SET SESSION autocommit = 1;
SET SESSION unique_checks = 1;
SET SESSION foreign_key_checks = 1;

-- 🎉 최종 결과
SELECT CONCAT('🎉🎉🎉 1천만건 배치 생성 완료!!! 🎉🎉🎉') as batch_victory;
SELECT CONCAT('⏰ 총 소요시간: ', TIMESTAMPDIFF(MINUTE, @start_time, NOW()), '분 ', 
              TIMESTAMPDIFF(SECOND, @start_time, NOW()) % 60, '초') as total_time;
SELECT CONCAT('📊 총 알림 수: ', FORMAT(COUNT(*), 0), '개') as final_count FROM notification;
SELECT CONCAT('🔥 배치 알림: ', FORMAT(COUNT(*), 0), '개') as batch_count 
FROM notification WHERE content LIKE '🔥배치알림#%';

-- 📊 상세 통계
SELECT '🏆 === 1천만건 배치 완성 통계 === 🏆' as stats_title;

SELECT 
    '📖 읽음 상태' as category,
    CASE WHEN is_read = 1 THEN '✅읽음' ELSE '📮안읽음' END as status,
    FORMAT(COUNT(*), 0) as count,
    CONCAT(ROUND(COUNT(*) * 100.0 / 10000000, 1), '%') as percentage
FROM notification 
WHERE content LIKE '🔥배치알림#%'
GROUP BY is_read

UNION ALL

SELECT 
    '📡 SSE 전송' as category,
    CASE WHEN sse_sent = 1 THEN '🚀성공' ELSE '💥실패' END as status,
    FORMAT(COUNT(*), 0) as count,
    CONCAT(ROUND(COUNT(*) * 100.0 / 10000000, 1), '%') as percentage
FROM notification 
WHERE content LIKE '🔥배치알림#%'
GROUP BY sse_sent

UNION ALL

SELECT 
    '📱 FCM 전송' as category,
    CASE WHEN fcm_sent = 1 THEN '📲성공' ELSE '📵실패' END as status,
    FORMAT(COUNT(*), 0) as count,
    CONCAT(ROUND(COUNT(*) * 100.0 / 10000000, 1), '%') as percentage
FROM notification 
WHERE content LIKE '🔥배치알림#%'
GROUP BY fcm_sent;

-- 🎖️ TOP 사용자 (알림 보유량)
SELECT '🎖️ TOP 10 사용자 (알림 보유량)' as top_users;
SELECT 
    CONCAT('👤 사용자 ', user_id) as user_info,
    FORMAT(COUNT(*), 0) as notification_count
FROM notification 
WHERE content LIKE '🔥배치알림#%'
GROUP BY user_id 
ORDER BY COUNT(*) DESC 
LIMIT 10;

-- 💾 스토리지 정보
SELECT 
    '💾 테이블 크기 정보' as storage_title,
    CONCAT(ROUND(((data_length + index_length) / 1024 / 1024 / 1024), 2), ' GB') as total_size,
    CONCAT(ROUND((data_length / 1024 / 1024 / 1024), 2), ' GB') as data_size,
    CONCAT(ROUND((index_length / 1024 / 1024 / 1024), 2), ' GB') as index_size
FROM information_schema.tables 
WHERE table_schema = 'buddkit' AND table_name = 'notification';

SELECT CONCAT('⚡ 평균 처리속도: ', 
              FORMAT(10000000 / GREATEST(TIMESTAMPDIFF(SECOND, @start_time, NOW()), 1), 0), 
              '개/초') as processing_speed;

SELECT '🎊🎊🎊 1천만건 안전 배치 처리 대성공! 🎊🎊🎊' as final_celebration;
