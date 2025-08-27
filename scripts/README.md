# 🚀 대용량 알림 데이터 생성 스크립트

## 개요
성능 테스트를 위한 **1천만건**의 알림 데이터를 MySQL 데이터베이스에 생성하는 스크립트입니다.

## 파일 설명

### 1. insert_million_notifications.sql
- **용도**: 안전한 배치 처리 방식으로 1천만건 생성
- **특징**: 1만건씩 배치 처리, 진행 상황 모니터링
- **실행 시간**: 약 30-60분
- **장점**: 메모리 사용량 최적화, 안전한 처리
- **단점**: 상대적으로 느림

### 2. insert_10million_notifications.sql  
- **용도**: 고성능 대용량 INSERT로 1천만건 생성
- **특징**: 10만건씩 100번 배치 처리
- **실행 시간**: 약 10-20분
- **장점**: 빠른 처리 속도, 진행률 모니터링
- **단점**: 높은 메모리 사용량

### 3. insert_10million_ultimate.sql 🔥
- **용도**: ULTIMATE 성능으로 1천만건 생성 
- **특징**: 극한 최적화, 10만건씩 대용량 배치
- **실행 시간**: 약 5-15분
- **장점**: 최고 성능, 실시간 통계
- **단점**: 매우 높은 리소스 사용

## 실행 전 준비사항

### 1. MySQL 설정 최적화 (권장)
```sql
-- my.cnf 또는 MySQL Workbench에서 설정
SET GLOBAL innodb_buffer_pool_size = 2G;
SET GLOBAL innodb_log_file_size = 512M;
SET GLOBAL innodb_flush_log_at_trx_commit = 0;
SET GLOBAL sync_binlog = 0;
```

### 2. 디스크 공간 확보
- **최소 50GB 이상**의 여유 공간 필요
- 알림 데이터 약 20-30GB + 인덱스 공간 20GB

### 3. 기존 데이터 백업 (선택)
```sql
-- 기존 알림 데이터 백업
CREATE TABLE app_notification_backup AS SELECT * FROM app_notification;
```

## 실행 방법

### 방법 1: MySQL Workbench
1. MySQL Workbench에서 스크립트 파일 열기
2. 전체 선택 후 실행 (Ctrl+Shift+Enter)

### 방법 2: 명령줄
```bash
# 안전한 배치 처리 방식 (30-60분)
mysql -h172.16.24.224 -uonlyone -ppassword buddkit < scripts/insert_million_notifications.sql

# 고성능 방식 (10-20분)
mysql -h172.16.24.224 -uonlyone -ppassword buddkit < scripts/insert_10million_notifications.sql

# 🔥 ULTIMATE 최고 성능 (5-15분)
mysql -h172.16.24.224 -uonlyone -ppassword buddkit < scripts/insert_10million_ultimate.sql
```

## 생성되는 데이터 특징

### 데이터 분포
- **사용자**: 500명의 실제 사용자에게 균등 분배
- **읽음률**: 30% (실제 사용 패턴 모방)
- **SSE 전송 성공률**: 80%
- **FCM 전송 성공률**: 70%

### 데이터 형태
```sql
title: '대용량 알림 #1', '메가알림#2', 'ULTRA메시지 3', ...
content: '성능 테스트용 메시지 번호 1 - 사용자 100001', ...
args: '["msg1"]', '["ultra2"]', '["테스트메시지3"]', ...
created_at: 최근 24시간 내 랜덤 시간
```

### 🎯 1천만건 규모
- **총 레코드**: 10,000,000개
- **사용자당 평균**: ~20,000개 알림
- **예상 DB 크기**: 30-50GB

## 성능 테스트 활용

### 1. 조회 성능 테스트
```sql
-- 사용자별 미읽음 개수 조회
SELECT user_id, COUNT(*) 
FROM app_notification 
WHERE is_read = 0 
GROUP BY user_id;

-- 최근 알림 조회 (페이징)
SELECT * FROM app_notification 
WHERE user_id = 100001 
ORDER BY created_at DESC 
LIMIT 20 OFFSET 0;
```

### 2. 인덱스 성능 확인
```sql
-- 실행 계획 확인
EXPLAIN SELECT * FROM app_notification 
WHERE user_id = 100001 AND is_read = 0 
ORDER BY created_at DESC;
```

### 3. 응용 성능 테스트
- NotificationService의 getNotifications() 메서드 테스트
- 페이징 처리 성능 측정  
- 캐시 효과 검증

## 정리 스크립트

```sql
-- 테스트 데이터만 삭제 (제목에 '대용량 테스트' 포함)
DELETE FROM app_notification 
WHERE title LIKE '대용량 테스트 알림%';

-- 또는 전체 알림 데이터 삭제
TRUNCATE TABLE app_notification;
```

## 주의사항

1. **운영 환경 주의**: 운영 DB에서는 절대 실행하지 마세요
2. **리소스 모니터링**: 실행 중 CPU/메모리 사용량 확인
3. **동시 접근 제한**: 실행 중에는 다른 DB 작업 자제
4. **시간대 고려**: 서비스 사용량이 적은 시간대에 실행

## 트러블슈팅

### 메모리 부족 에러
```sql
-- 배치 크기 줄이기
SET @batch_size = 1000;
```

### 타임아웃 에러  
```sql
-- 타임아웃 늘리기
SET SESSION wait_timeout = 3600;
SET SESSION interactive_timeout = 3600;
```

### 디스크 공간 부족
```sql
-- 기존 데이터 정리 후 재실행
DELETE FROM app_notification WHERE created_at < DATE_SUB(NOW(), INTERVAL 30 DAY);
```