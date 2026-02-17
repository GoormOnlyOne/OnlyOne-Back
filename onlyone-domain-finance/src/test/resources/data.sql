-- 테스트용 User 데이터 (리포지토리 테스트에서 getReference로 참조)
INSERT INTO "user" (user_id, kakao_id, nickname, status, role, created_at, modified_at)
VALUES (1, 1001, 'alice', 'ACTIVE', 'ROLE_USER', NOW(), NOW());
INSERT INTO "user" (user_id, kakao_id, nickname, status, role, created_at, modified_at)
VALUES (2, 1002, 'bob', 'ACTIVE', 'ROLE_USER', NOW(), NOW());
INSERT INTO "user" (user_id, kakao_id, nickname, status, role, created_at, modified_at)
VALUES (3, 1003, 'charlie', 'ACTIVE', 'ROLE_USER', NOW(), NOW());
