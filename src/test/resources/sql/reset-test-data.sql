-- 테스트 데이터 완전 정리 및 시퀀스 초기화
DELETE FROM notification;
DELETE FROM notification_type;
DELETE FROM "user";

-- H2 데이터베이스 시퀀스 초기화
ALTER SEQUENCE IF EXISTS notification_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS notification_type_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS user_seq RESTART WITH 1;