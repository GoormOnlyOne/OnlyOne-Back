-- =============================================================
-- Finance 모듈 테스트 시드 데이터
-- H2 인메모리 DB에 자동 삽입 (spring.sql.init.mode=always)
-- 결제/정산 테스트에서 User FK 참조용
-- =============================================================
INSERT INTO "user" (user_id, kakao_id, nickname, status, role, created_at, modified_at)
VALUES (1, 1001, 'alice', 'ACTIVE', 'ROLE_USER', NOW(), NOW());
INSERT INTO "user" (user_id, kakao_id, nickname, status, role, created_at, modified_at)
VALUES (2, 1002, 'bob', 'ACTIVE', 'ROLE_USER', NOW(), NOW());
INSERT INTO "user" (user_id, kakao_id, nickname, status, role, created_at, modified_at)
VALUES (3, 1003, 'charlie', 'ACTIVE', 'ROLE_USER', NOW(), NOW());
