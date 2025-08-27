#!/usr/bin/env python3
"""
🔥 1천만건 배치 SQL 생성기
50만건씩 20개 배치 SQL 스크립트를 자동 생성
"""

def generate_batch_sql():
    sql_parts = []
    
    # 헤더
    sql_parts.append("""-- 🚀 1천만건 알림 생성 (50만건씩 20배치)
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
""")
    
    # 20개 배치 생성
    for batch in range(1, 21):
        start_num = (batch - 1) * 500000
        end_num = batch * 500000
        progress = batch * 5  # 5%씩 증가
        
        batch_sql = f"""
-- ========== 배치 {batch}: {start_num + 1:,}-{end_num:,} ==========
SELECT CONCAT('🚀 배치 {batch}/20 시작 ({start_num/1000000:.1f}M-{end_num/1000000:.1f}M): ', NOW()) as batch{batch}_start;
INSERT INTO notification (user_id, type_id, content, is_read, sse_sent, fcm_sent, created_at, modified_at)
SELECT 
    100001 + ((n + {start_num}) % 500) as user_id,
    @notification_type_id as type_id,
    CONCAT('🔥배치알림#', n + {start_num + 1}, '👤', 100001 + ((n + {start_num}) % 500)) as content,
    ((n + {start_num}) % 10 < 3) as is_read,
    ((n + {start_num}) % 10 < 8) as sse_sent, 
    ((n + {start_num}) % 10 < 7) as fcm_sent,
    DATE_SUB(NOW(), INTERVAL ((n + {start_num}) % 86400) SECOND) as created_at,
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
SELECT CONCAT('✅ 배치 {batch} 완료 ({end_num/1000000:.1f}M): ', NOW(), ' | 진행률: {progress}%') as batch{batch}_done;
"""
        sql_parts.append(batch_sql)
    
    # 푸터
    sql_parts.append("""
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
""")
    
    return '\n'.join(sql_parts)

if __name__ == "__main__":
    print("🔥 1천만건 배치 SQL 생성 중...")
    sql_content = generate_batch_sql()
    
    with open('insert_10million_full_batch.sql', 'w', encoding='utf-8') as f:
        f.write(sql_content)
    
    print("✅ insert_10million_full_batch.sql 생성 완료!")
    print("📊 총 배치 수: 20개 (50만건씩)")
    print("🎯 실행 명령어:")
    print("mysql -h172.16.24.224 -uonlyone -ppassword buddkit < scripts/insert_10million_full_batch.sql")