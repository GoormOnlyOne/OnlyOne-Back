-- =============================================================
-- 검색 부하 테스트용 시드 데이터 (100x 스케일)
-- =============================================================
--
-- 데이터 규모:
--   club: 200,000개 (카테고리당 25,000개, 지역 10곳 분산)
--   user_interest: user 1~100000에 복수 관심사 할당
--   user_club: user 1~100000에 3~5개 클럽 가입
--
-- 사전 조건:
--   - user 테이블에 userId 1~100000 존재
--   - interest 테이블에 1~8번 존재 (없으면 아래에서 생성)
--
-- 실행:
--   docker exec -i onlyone-mysql mysql -uroot -proot onlyone < k6-tests/seed-search.sql
--
-- ES 모드 시 추가 작업:
--   curl -X POST http://localhost:8080/api/v1/admin/search/reindex
-- =============================================================

SET @START_TIME = NOW();
SELECT '=== 검색 시드 데이터 생성 시작 (100x) ===' AS msg;

SET FOREIGN_KEY_CHECKS = 0;
SET UNIQUE_CHECKS = 0;
SET autocommit = 0;
SET SESSION cte_max_recursion_depth = 20000000;
SET SESSION bulk_insert_buffer_size = 256 * 1024 * 1024;

-- Interest 데이터 (없으면 삽입)
INSERT IGNORE INTO interest (interest_id, category, created_at, modified_at)
VALUES
    (1, 'CULTURE', NOW(), NOW()),
    (2, 'EXERCISE', NOW(), NOW()),
    (3, 'TRAVEL', NOW(), NOW()),
    (4, 'MUSIC', NOW(), NOW()),
    (5, 'CRAFT', NOW(), NOW()),
    (6, 'SOCIAL', NOW(), NOW()),
    (7, 'LANGUAGE', NOW(), NOW()),
    (8, 'FINANCE', NOW(), NOW());
COMMIT;

-- 헬퍼 테이블
DROP TABLE IF EXISTS _digits;
CREATE TABLE _digits (d INT NOT NULL) ENGINE=MEMORY;
INSERT INTO _digits VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);

-- =============================================================
-- Club 대량 생성 (200,000개)
-- 카테고리 8개 × 패턴 5개 × 지역 10곳 × 반복 500회 = 200,000
-- 배치: 10,000건 × 20 = 200,000
-- =============================================================
SELECT '--- 클럽 200,000개 생성 ---' AS msg;

DROP TABLE IF EXISTS _seq10k;
CREATE TABLE _seq10k (n INT NOT NULL, PRIMARY KEY(n)) ENGINE=MEMORY;
INSERT INTO _seq10k
SELECT d4.d*1000 + d3.d*100 + d2.d*10 + d1.d
FROM _digits d1, _digits d2, _digits d3, _digits d4;

DELIMITER //
DROP PROCEDURE IF EXISTS seed_search_clubs //
CREATE PROCEDURE seed_search_clubs()
BEGIN
    DECLARE batch INT DEFAULT 0;
    DECLARE v_offset INT;
    WHILE batch < 20 DO
        SET v_offset = batch * 10000;

        INSERT INTO club (name, user_limit, description, city, district, member_count, interest_id, created_at, modified_at)
        SELECT
            CONCAT(
                CASE ((v_offset + s.n) % 8) + 1
                    WHEN 1 THEN ELT(((v_offset + s.n) % 5) + 1, '독서모임 북클럽', '영화 감상 시네마클럽', '전시회 탐방 아트워커', '뮤지컬 관람 모임', '카페 투어 문화탐방')
                    WHEN 2 THEN ELT(((v_offset + s.n) % 5) + 1, '축구 동호회 FC유나이티드', '농구 클럽 슬램덩크', '테니스 레슨 동호회', '러닝 크루 달려라', '요가 필라테스 힐링')
                    WHEN 3 THEN ELT(((v_offset + s.n) % 5) + 1, '등산 모임 산타즈', '캠핑 클럽 별밤캠프', '해외여행 동행 모임', '국내여행 맛집투어', '수영 다이빙 클럽')
                    WHEN 4 THEN ELT(((v_offset + s.n) % 5) + 1, '기타 동아리 스트링', '피아노 연주 모임', '밴드 합주 록스타', '노래방 싱어즈', '드럼 비트메이커')
                    WHEN 5 THEN ELT(((v_offset + s.n) % 5) + 1, '뜨개질 니팅클럽', '도자기 공방 흙놀이', '목공 DIY 나무꾼', '캘리그라피 글꽃', '요리 베이킹 쿡스')
                    WHEN 6 THEN ELT(((v_offset + s.n) % 5) + 1, '보드게임 모임 주사위', '와인 시음 클럽', '커피 동호회 바리스타', '맛집 탐방 미식가', '네트워킹 소셜클럽')
                    WHEN 7 THEN ELT(((v_offset + s.n) % 5) + 1, '영어 회화 잉글리시', '일본어 스터디', '중국어 학습 모임', '스페인어 올라', '프랑스어 봉주르')
                    WHEN 8 THEN ELT(((v_offset + s.n) % 5) + 1, '주식 투자 스터디', '부동산 스터디', '코인 암호화폐 클럽', '재테크 머니클럽', '경제 토론 모임')
                END,
                ' ', v_offset + s.n
            ),
            50,
            CONCAT('테스트 클럽 ', v_offset + s.n, '의 설명입니다. 함께 활동하며 즐거운 시간을 보내세요. 다양한 분야의 사람들과 교류하고 배울 수 있는 좋은 기회입니다.'),
            ELT(((v_offset + s.n) % 10) + 1, '서울', '서울', '서울', '서울', '부산', '부산', '부산', '대구', '인천', '광주'),
            ELT(((v_offset + s.n) % 10) + 1, '강남구', '서구', '남구', '중구', '해운대구', '사하구', '북구', '서구', '서구', '남구'),
            FLOOR(RAND() * 980) + 20,
            ((v_offset + s.n) % 8) + 1,
            NOW() - INTERVAL FLOOR(RAND() * 365) DAY,
            NOW()
        FROM _seq10k s;

        COMMIT;
        IF (batch + 1) % 5 = 0 THEN
            SELECT CONCAT('    클럽 진행: ', (batch + 1) * 10000, ' / 200,000') AS '';
        END IF;
        SET batch = batch + 1;
    END WHILE;
END //
DELIMITER ;

CALL seed_search_clubs();
DROP PROCEDURE IF EXISTS seed_search_clubs;

-- =============================================================
-- user_interest: 유저당 2개 관심사 할당 (1~100000)
-- =============================================================
INSERT IGNORE INTO user_interest (user_id, interest_id, created_at, modified_at)
SELECT user_id, ((user_id % 8) + 1), NOW(), NOW()
FROM user WHERE user_id BETWEEN 1 AND 100000;

INSERT IGNORE INTO user_interest (user_id, interest_id, created_at, modified_at)
SELECT user_id, (((user_id + 3) % 8) + 1), NOW(), NOW()
FROM user WHERE user_id BETWEEN 1 AND 100000;
COMMIT;

-- =============================================================
-- user_club: 유저당 3~5개 클럽 가입
-- =============================================================
SET @min_club_search = (SELECT MIN(c.club_id) FROM club c);
SET @max_club_search = (SELECT MAX(c.club_id) FROM club c);
SET @club_range = @max_club_search - @min_club_search + 1;

-- 가입 1 (전원)
INSERT IGNORE INTO user_club (user_id, club_id, role, created_at, modified_at)
SELECT u.user_id,
       @min_club_search + (u.user_id % @club_range),
       'MEMBER', NOW(), NOW()
FROM user u WHERE u.user_id BETWEEN 1 AND 100000;
COMMIT;

-- 가입 2 (전원)
INSERT IGNORE INTO user_club (user_id, club_id, role, created_at, modified_at)
SELECT u.user_id,
       @min_club_search + ((u.user_id + 33333) % @club_range),
       'MEMBER', NOW(), NOW()
FROM user u WHERE u.user_id BETWEEN 1 AND 100000;
COMMIT;

-- 가입 3 (전원)
INSERT IGNORE INTO user_club (user_id, club_id, role, created_at, modified_at)
SELECT u.user_id,
       @min_club_search + ((u.user_id + 66666) % @club_range),
       'MEMBER', NOW(), NOW()
FROM user u WHERE u.user_id BETWEEN 1 AND 100000;
COMMIT;

-- 가입 4 (절반)
INSERT IGNORE INTO user_club (user_id, club_id, role, created_at, modified_at)
SELECT u.user_id,
       @min_club_search + ((u.user_id * 7) % @club_range),
       'MEMBER', NOW(), NOW()
FROM user u WHERE u.user_id BETWEEN 1 AND 50000;
COMMIT;

-- 가입 5 (20000명)
INSERT IGNORE INTO user_club (user_id, club_id, role, created_at, modified_at)
SELECT u.user_id,
       @min_club_search + ((u.user_id * 13) % @club_range),
       'MEMBER', NOW(), NOW()
FROM user u WHERE u.user_id BETWEEN 1 AND 20000;
COMMIT;

-- 정리
DROP TABLE IF EXISTS _seq10k;
DROP TABLE IF EXISTS _digits;

SET FOREIGN_KEY_CHECKS = 1;
SET UNIQUE_CHECKS = 1;
SET autocommit = 1;

-- =============================================================
-- 결과 확인
-- =============================================================
SELECT '--- Seed Summary ---' AS '';
SELECT CONCAT('clubs: ', COUNT(*)) AS result FROM club;
SELECT CONCAT('user_interest: ', COUNT(*)) AS result FROM user_interest WHERE user_id BETWEEN 1 AND 100000;
SELECT CONCAT('user_club: ', COUNT(*)) AS result FROM user_club WHERE user_id BETWEEN 1 AND 100000;
SELECT CONCAT('interest distribution:') AS '';
SELECT i.category, COUNT(c.club_id) AS club_count
FROM interest i LEFT JOIN club c ON c.interest_id = i.interest_id
GROUP BY i.category ORDER BY i.interest_id;
SELECT CONCAT('location distribution:') AS '';
SELECT city, district, COUNT(*) AS cnt
FROM club GROUP BY city, district ORDER BY cnt DESC LIMIT 10;
SELECT TIMEDIFF(NOW(), @START_TIME) AS elapsed;
SELECT '=== 검색 시드 완료 ===' AS msg;
