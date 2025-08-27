-- 🔧 전송 상태 일괄 수정 (전송 테스트용)
-- 모든 알림을 미전송 상태로 변경

SELECT CONCAT('🔧 전송 상태 수정 시작: ', NOW()) as update_start;
SET @start_time = NOW();

-- 현재 상태 확인
SELECT '📊 수정 전 상태' as before_status;
SELECT 
    CONCAT('총 알림 수: ', FORMAT(COUNT(*), 0)) as total_count,
    CONCAT('SSE 전송됨: ', FORMAT(SUM(CASE WHEN sse_sent = 1 THEN 1 ELSE 0 END), 0)) as sse_sent_count,
    CONCAT('FCM 전송됨: ', FORMAT(SUM(CASE WHEN fcm_sent = 1 THEN 1 ELSE 0 END), 0)) as fcm_sent_count
FROM notification;

-- 🚀 일괄 업데이트 (모든 알림을 미전송으로)
UPDATE notification 
SET 
    sse_sent = 0,
    fcm_sent = 0,
    modified_at = NOW()
WHERE sse_sent = 1 OR fcm_sent = 1;

-- 수정 후 상태 확인
SELECT '✅ 수정 후 상태' as after_status;
SELECT 
    CONCAT('총 알림 수: ', FORMAT(COUNT(*), 0)) as total_count,
    CONCAT('SSE 전송됨: ', FORMAT(SUM(CASE WHEN sse_sent = 1 THEN 1 ELSE 0 END), 0)) as sse_sent_count,
    CONCAT('FCM 전송됨: ', FORMAT(SUM(CASE WHEN fcm_sent = 1 THEN 1 ELSE 0 END), 0)) as fcm_sent_count
FROM notification;

SELECT CONCAT('🎯 전송 상태 수정 완료: ', NOW()) as update_done;
SELECT CONCAT('⏰ 소요시간: ', TIMESTAMPDIFF(SECOND, @start_time, NOW()), '초') as duration;
SELECT '🚀 이제 전송 테스트 준비 완료!' as ready_message;