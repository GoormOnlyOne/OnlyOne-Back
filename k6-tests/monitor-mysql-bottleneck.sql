-- =============================================================
-- MySQL 병목점 모니터링 쿼리 모음
-- 부하 테스트 중 별도 MySQL 세션에서 주기적으로 실행
--
-- 사용법:
--   mysql -u root -p onlyone < monitor-mysql-bottleneck.sql
--   또는 MySQL Workbench/DBeaver에서 개별 실행
-- =============================================================

-- ╔═══════════════════════════════════════════════╗
-- ║  1. 슬로우 쿼리 설정 & 확인                     ║
-- ╚═══════════════════════════════════════════════╝

-- 슬로우 쿼리 로그 활성화 (200ms 이상)
SET GLOBAL slow_query_log = 'ON';
SET GLOBAL long_query_time = 0.2;
SET GLOBAL log_queries_not_using_indexes = ON;

-- 현재 슬로우 쿼리 설정 확인
SELECT @@slow_query_log AS slow_log_enabled,
       @@long_query_time AS threshold_sec,
       @@slow_query_log_file AS log_file,
       @@log_queries_not_using_indexes AS log_no_index;


-- ╔═══════════════════════════════════════════════╗
-- ║  2. 실시간 프로세스 리스트 (활성 쿼리)            ║
-- ╚═══════════════════════════════════════════════╝

-- 현재 실행 중인 쿼리 (1초 이상)
SELECT id, user, host, db, command, time AS seconds,
       state, LEFT(info, 100) AS query_preview
FROM information_schema.processlist
WHERE command != 'Sleep'
  AND time > 1
ORDER BY time DESC;


-- ╔═══════════════════════════════════════════════╗
-- ║  3. InnoDB 락 모니터링                          ║
-- ╚═══════════════════════════════════════════════╝

-- 3-1. 현재 대기 중인 락 (락 대기)
SELECT
    r.trx_id AS waiting_trx_id,
    r.trx_mysql_thread_id AS waiting_thread,
    r.trx_query AS waiting_query,
    r.trx_wait_started AS wait_started,
    TIMESTAMPDIFF(SECOND, r.trx_wait_started, NOW()) AS wait_seconds,
    b.trx_id AS blocking_trx_id,
    b.trx_mysql_thread_id AS blocking_thread,
    b.trx_query AS blocking_query
FROM information_schema.innodb_trx r
JOIN information_schema.innodb_trx b
  ON r.trx_requested_lock_id IS NOT NULL
WHERE r.trx_state = 'LOCK WAIT'
ORDER BY wait_seconds DESC;

-- 3-2. InnoDB 락 대기 상세 (performance_schema)
SELECT
    rl.OBJECT_SCHEMA, rl.OBJECT_NAME,
    rl.LOCK_TYPE, rl.LOCK_MODE, rl.LOCK_STATUS,
    rl.LOCK_DATA,
    t.trx_id, t.trx_state,
    t.trx_rows_locked, t.trx_rows_modified,
    LEFT(t.trx_query, 120) AS query_preview
FROM performance_schema.data_locks rl
JOIN information_schema.innodb_trx t
  ON rl.ENGINE_TRANSACTION_ID = t.trx_id
WHERE rl.LOCK_STATUS = 'WAITING'
ORDER BY t.trx_wait_started;

-- 3-3. 데드락 히스토리 (최근 데드락)
SHOW ENGINE INNODB STATUS\G


-- ╔═══════════════════════════════════════════════╗
-- ║  4. InnoDB 상태 요약                            ║
-- ╚═══════════════════════════════════════════════╝

-- 현재 트랜잭션 상태
SELECT
    trx_state,
    COUNT(*) AS count,
    MAX(TIMESTAMPDIFF(SECOND, trx_started, NOW())) AS max_duration_sec,
    AVG(TIMESTAMPDIFF(SECOND, trx_started, NOW())) AS avg_duration_sec
FROM information_schema.innodb_trx
GROUP BY trx_state;

-- InnoDB 버퍼 풀 히트율
SELECT
    (1 - (Innodb_buffer_pool_reads / Innodb_buffer_pool_read_requests)) * 100
        AS buffer_pool_hit_rate_pct
FROM (
    SELECT
        (SELECT VARIABLE_VALUE FROM performance_schema.global_status
         WHERE VARIABLE_NAME = 'Innodb_buffer_pool_reads') AS Innodb_buffer_pool_reads,
        (SELECT VARIABLE_VALUE FROM performance_schema.global_status
         WHERE VARIABLE_NAME = 'Innodb_buffer_pool_read_requests') AS Innodb_buffer_pool_read_requests
) t;

-- InnoDB 행 잠금 통계
SELECT
    (SELECT VARIABLE_VALUE FROM performance_schema.global_status
     WHERE VARIABLE_NAME = 'Innodb_row_lock_current_waits') AS current_waits,
    (SELECT VARIABLE_VALUE FROM performance_schema.global_status
     WHERE VARIABLE_NAME = 'Innodb_row_lock_waits') AS total_waits,
    (SELECT VARIABLE_VALUE FROM performance_schema.global_status
     WHERE VARIABLE_NAME = 'Innodb_row_lock_time_avg') AS avg_wait_ms,
    (SELECT VARIABLE_VALUE FROM performance_schema.global_status
     WHERE VARIABLE_NAME = 'Innodb_row_lock_time_max') AS max_wait_ms;


-- ╔═══════════════════════════════════════════════╗
-- ║  5. HikariCP / 커넥션 풀 모니터링               ║
-- ╚═══════════════════════════════════════════════╝

-- 현재 MySQL 커넥션 수
SELECT
    (SELECT VARIABLE_VALUE FROM performance_schema.global_status
     WHERE VARIABLE_NAME = 'Threads_connected') AS threads_connected,
    (SELECT VARIABLE_VALUE FROM performance_schema.global_status
     WHERE VARIABLE_NAME = 'Threads_running') AS threads_running,
    (SELECT VARIABLE_VALUE FROM performance_schema.global_status
     WHERE VARIABLE_NAME = 'Max_used_connections') AS max_used_connections,
    @@max_connections AS max_connections;

-- 커넥션 대기 (Aborted/Too many connections)
SELECT
    (SELECT VARIABLE_VALUE FROM performance_schema.global_status
     WHERE VARIABLE_NAME = 'Aborted_connects') AS aborted_connects,
    (SELECT VARIABLE_VALUE FROM performance_schema.global_status
     WHERE VARIABLE_NAME = 'Connection_errors_max_connections') AS errors_max_conn;


-- ╔═══════════════════════════════════════════════╗
-- ║  6. notification 테이블 인덱스 분석              ║
-- ╚═══════════════════════════════════════════════╝

-- 인덱스 목록
SHOW INDEX FROM notification;

-- 주요 쿼리 실행 계획 확인

-- 6-1. 알림 목록 (커서 기반 페이징)
EXPLAIN SELECT n.id, n.content, n.type, n.is_read, n.created_at
FROM notification n
WHERE n.user_id = 1
  AND n.id < 999999999
ORDER BY n.id DESC
LIMIT 21;

-- 6-2. 안읽음 카운트
EXPLAIN SELECT COUNT(*)
FROM notification n
WHERE n.user_id = 1
  AND n.is_read = 0;

-- 6-3. 전체 읽음 (벌크 UPDATE) — 락 경합 핵심
EXPLAIN UPDATE notification
SET is_read = 1
WHERE user_id = 1
  AND is_read = 0;

-- 6-4. 미전송 알림 복구 쿼리 (SSE 재연결 시)
EXPLAIN SELECT n.id, n.content, n.type, n.is_read, n.created_at
FROM notification n
WHERE n.user_id = 1
  AND n.sse_sent = 0
ORDER BY n.id ASC
LIMIT 50;

-- 6-5. SSE 전송 완료 마킹 (배치)
EXPLAIN UPDATE notification
SET sse_sent = 1
WHERE id IN (1, 2, 3, 4, 5);


-- ╔═══════════════════════════════════════════════╗
-- ║  7. 테이블 통계                                ║
-- ╚═══════════════════════════════════════════════╝

SELECT
    TABLE_NAME, TABLE_ROWS, DATA_LENGTH, INDEX_LENGTH,
    ROUND((DATA_LENGTH + INDEX_LENGTH) / 1024 / 1024, 2) AS total_size_mb
FROM information_schema.tables
WHERE TABLE_SCHEMA = 'onlyone'
  AND TABLE_NAME = 'notification';
