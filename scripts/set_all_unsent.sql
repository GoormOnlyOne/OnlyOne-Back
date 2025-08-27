-- 모든 알림을 미전송 상태로 변경
UPDATE notification 
SET 
    sse_sent = 0,
    fcm_sent = 0,
    modified_at = NOW();

-- 결과 확인
SELECT 
    COUNT(*) as total_notifications,
    SUM(CASE WHEN sse_sent = 1 THEN 1 ELSE 0 END) as sse_sent_count,
    SUM(CASE WHEN fcm_sent = 1 THEN 1 ELSE 0 END) as fcm_sent_count
FROM notification;