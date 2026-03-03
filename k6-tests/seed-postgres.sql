-- =============================================================
-- 전체 도메인 통합 시드 데이터 (PostgreSQL)
-- =============================================================
-- 실행: psql -h localhost -U onlyone -d onlyone -f k6-tests/seed-postgres.sql
--
-- seed-all-domains.sql 기반 PostgreSQL 방언 변환:
--   AUTO_INCREMENT       → GENERATED ALWAYS AS IDENTITY
--   DATETIME(6)          → TIMESTAMP(6)
--   ENUM(...)            → VARCHAR(50) + CHECK 제약
--   INSERT IGNORE        → INSERT ... ON CONFLICT DO NOTHING
--   ELT(), IF()          → CASE WHEN, array 인덱싱
--   DELIMITER/PROCEDURE  → DO $$ ... $$ (PL/pgSQL 익명 블록)
--
-- 규모: seed-all-domains.sql과 동일
--   user: 1,000  |  club: 1,000  |  feed: 50,000
--   settlement: 500  |  notification: 100,000
-- =============================================================

\timing on
\echo '========================================'
\echo '=== 전체 도메인 시드 데이터 생성 시작 (PostgreSQL) ==='
\echo '========================================'

-- ═══════════════════════════════════════════
-- 스키마 생성 (테이블이 없을 경우)
-- ═══════════════════════════════════════════

CREATE TABLE IF NOT EXISTS "user" (
    user_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kakao_id BIGINT NOT NULL UNIQUE,
    nickname VARCHAR(100),
    birth DATE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    profile_image VARCHAR(500),
    gender VARCHAR(10),
    city VARCHAR(50),
    district VARCHAR(50),
    role VARCHAR(20) NOT NULL DEFAULT 'ROLE_USER',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS interest (
    interest_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    category VARCHAR(50) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS user_interest (
    user_interest_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    interest_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, interest_id)
);

CREATE TABLE IF NOT EXISTS club (
    club_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    user_limit INT NOT NULL DEFAULT 50,
    description TEXT,
    city VARCHAR(50),
    district VARCHAR(50),
    member_count INT NOT NULL DEFAULT 0,
    interest_id BIGINT,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS user_club (
    user_club_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    club_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'MEMBER',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, club_id)
);

CREATE TABLE IF NOT EXISTS feed (
    feed_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    content TEXT,
    club_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    type VARCHAR(20) NOT NULL DEFAULT 'ORIGINAL',
    parent_feed_id BIGINT,
    root_feed_id BIGINT,
    like_count INT NOT NULL DEFAULT 0,
    comment_count INT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS feed_comment (
    feed_comment_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    content TEXT NOT NULL,
    feed_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS feed_like (
    feed_like_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    feed_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(feed_id, user_id)
);

CREATE TABLE IF NOT EXISTS feed_image (
    feed_image_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    feed_image VARCHAR(500) NOT NULL,
    feed_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS schedule (
    schedule_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    schedule_time TIMESTAMP(6),
    name VARCHAR(200) NOT NULL,
    location VARCHAR(200),
    cost INT NOT NULL DEFAULT 0,
    user_limit INT NOT NULL DEFAULT 20,
    status VARCHAR(20) NOT NULL DEFAULT 'READY',
    club_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS user_schedule (
    user_schedule_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    schedule_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'MEMBER',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, schedule_id)
);

CREATE TABLE IF NOT EXISTS chat_room (
    chat_room_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    club_id BIGINT,
    schedule_id BIGINT,
    type VARCHAR(20) NOT NULL DEFAULT 'CLUB',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS user_chat_room (
    user_chat_room_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    chat_room_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'MEMBER',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(chat_room_id, user_id)
);

CREATE TABLE IF NOT EXISTS message (
    message_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    chat_room_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    text TEXT,
    sent_at TIMESTAMP(6),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS wallet (
    wallet_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    posted_balance BIGINT NOT NULL DEFAULT 0,
    pending_out BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS payment (
    payment_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    toss_order_id VARCHAR(200),
    payment_key VARCHAR(200),
    total_amount BIGINT,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    user_id BIGINT NOT NULL,
    schedule_id BIGINT,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS settlement (
    settlement_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    schedule_id BIGINT NOT NULL,
    sum BIGINT NOT NULL DEFAULT 0,
    total_status VARCHAR(20) NOT NULL DEFAULT 'HOLDING',
    user_id BIGINT NOT NULL,
    completed_time TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS user_settlement (
    user_settlement_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    settlement_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'HOLD_ACTIVE',
    completed_time TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS outbox_event (
    outbox_event_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_type VARCHAR(100),
    aggregate_id VARCHAR(100),
    event_type VARCHAR(100),
    payload TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS notification (
    notification_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    content VARCHAR(500) NOT NULL,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    type VARCHAR(50) NOT NULL DEFAULT 'FEED',
    user_id BIGINT NOT NULL,
    sse_sent BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS fcm_token (
    fcm_token_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token VARCHAR(500) NOT NULL,
    device_type VARCHAR(20),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    modified_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 인덱스 생성
CREATE INDEX IF NOT EXISTS idx_feed_club ON feed(club_id);
CREATE INDEX IF NOT EXISTS idx_feed_user ON feed(user_id);
CREATE INDEX IF NOT EXISTS idx_feed_comment_feed ON feed_comment(feed_id);
CREATE INDEX IF NOT EXISTS idx_feed_like_feed ON feed_like(feed_id);
CREATE INDEX IF NOT EXISTS idx_feed_image_feed ON feed_image(feed_id);
CREATE INDEX IF NOT EXISTS idx_notification_user_read ON notification(user_id, is_read);
CREATE INDEX IF NOT EXISTS idx_settlement_status ON settlement(total_status);
CREATE INDEX IF NOT EXISTS idx_user_settlement_sid ON user_settlement(settlement_id);
CREATE INDEX IF NOT EXISTS idx_schedule_club ON schedule(club_id);
CREATE INDEX IF NOT EXISTS idx_message_chatroom ON message(chat_room_id);
CREATE INDEX IF NOT EXISTS idx_wallet_user ON wallet(user_id);

-- ═══════════════════════════════════════════
-- 1) 유저 1,000명
-- ═══════════════════════════════════════════
\echo '--- [1/14] 유저 생성 (1,000명) ---'

DO $$
DECLARE
    i INT;
    cities TEXT[] := ARRAY['서울','부산','대구','인천','광주'];
    districts TEXT[] := ARRAY['강남구','서초구','마포구','중구','해운대구','사하구','북구','서구','남구','동구'];
BEGIN
    FOR i IN 1..1000 LOOP
        INSERT INTO "user" (kakao_id, nickname, birth, status, profile_image, gender, city, district, role, created_at, modified_at)
        VALUES (
            1000000 + i,
            '테스트유저' || i,
            '2000-01-01'::DATE - ((i % 3650) || ' days')::INTERVAL,
            'ACTIVE',
            NULL,
            CASE WHEN i % 2 = 0 THEN 'MALE' ELSE 'FEMALE' END,
            cities[(i % 5) + 1],
            districts[(i % 10) + 1],
            'ROLE_USER',
            NOW() - ((1000 - i) || ' days')::INTERVAL,
            NOW()
        )
        ON CONFLICT (kakao_id) DO UPDATE SET nickname = EXCLUDED.nickname, modified_at = NOW();
    END LOOP;
END $$;

SELECT '  유저: ' || COUNT(*) AS msg FROM "user" WHERE kakao_id BETWEEN 1000001 AND 1001000;

-- ═══════════════════════════════════════════
-- 2) 관심사 8개
-- ═══════════════════════════════════════════
\echo '--- [2/14] 관심사 생성 ---'

INSERT INTO interest (category, created_at, modified_at)
VALUES
    ('CULTURE', NOW(), NOW()),
    ('EXERCISE', NOW(), NOW()),
    ('TRAVEL', NOW(), NOW()),
    ('MUSIC', NOW(), NOW()),
    ('CRAFT', NOW(), NOW()),
    ('SOCIAL', NOW(), NOW()),
    ('LANGUAGE', NOW(), NOW()),
    ('FINANCE', NOW(), NOW())
ON CONFLICT DO NOTHING;

-- 유저 관심사 매핑 (유저당 2개)
INSERT INTO user_interest (user_id, interest_id, created_at, modified_at)
SELECT u.user_id, (u.user_id % 8) + 1, NOW(), NOW()
FROM "user" u WHERE u.user_id BETWEEN 1 AND 1000
ON CONFLICT DO NOTHING;

INSERT INTO user_interest (user_id, interest_id, created_at, modified_at)
SELECT u.user_id, ((u.user_id + 3) % 8) + 1, NOW(), NOW()
FROM "user" u WHERE u.user_id BETWEEN 1 AND 1000
ON CONFLICT DO NOTHING;

-- ═══════════════════════════════════════════
-- 3) 클럽 1,000개
-- ═══════════════════════════════════════════
\echo '--- [3/14] 클럽 생성 (1,000개) ---'

DO $$
DECLARE
    i INT;
    club_interest INT;
    club_city VARCHAR(50);
    club_district VARCHAR(50);
    names TEXT[] := ARRAY['독서모임','축구동호회','등산모임','기타동아리','뜨개질클럽','보드게임','영어회화','주식스터디','영화감상','러닝크루'];
BEGIN
    FOR i IN 0..999 LOOP
        club_interest := (i % 8) + 1;

        CASE (i % 5)
            WHEN 0 THEN club_city := '서울'; club_district := '강남구';
            WHEN 1 THEN club_city := '서울'; club_district := '마포구';
            WHEN 2 THEN club_city := '부산'; club_district := '해운대구';
            WHEN 3 THEN club_city := '대구'; club_district := '서구';
            WHEN 4 THEN club_city := '인천'; club_district := '서구';
        END CASE;

        INSERT INTO club (name, user_limit, description, city, district, member_count, interest_id, created_at, modified_at)
        VALUES (
            names[(i % 10) + 1] || ' ' || i,
            50,
            '테스트 클럽 ' || i || '의 설명입니다. 함께 활동하며 즐거운 시간을 보내세요.',
            club_city, club_district,
            FLOOR(RANDOM() * 45) + 5,
            club_interest,
            NOW() - (FLOOR(RANDOM() * 365) || ' days')::INTERVAL,
            NOW()
        );
    END LOOP;
END $$;

SELECT '  클럽: ' || COUNT(*) AS msg FROM club;

-- ═══════════════════════════════════════════
-- 4) 유저-클럽 가입 (유저당 3~5개)
-- ═══════════════════════════════════════════
\echo '--- [4/14] 클럽 가입 ---'

DO $$
DECLARE
    min_club BIGINT;
BEGIN
    SELECT MIN(club_id) INTO min_club FROM club;

    -- 3개 기본 가입
    INSERT INTO user_club (user_id, club_id, role, created_at, modified_at)
    SELECT u.user_id, min_club + (u.user_id % 1000), 'MEMBER', NOW(), NOW()
    FROM "user" u WHERE u.user_id BETWEEN 1 AND 1000
    ON CONFLICT DO NOTHING;

    INSERT INTO user_club (user_id, club_id, role, created_at, modified_at)
    SELECT u.user_id, min_club + ((u.user_id + 333) % 1000), 'MEMBER', NOW(), NOW()
    FROM "user" u WHERE u.user_id BETWEEN 1 AND 1000
    ON CONFLICT DO NOTHING;

    INSERT INTO user_club (user_id, club_id, role, created_at, modified_at)
    SELECT u.user_id, min_club + ((u.user_id + 666) % 1000), 'MEMBER', NOW(), NOW()
    FROM "user" u WHERE u.user_id BETWEEN 1 AND 1000
    ON CONFLICT DO NOTHING;

    -- 500명 추가 1개
    INSERT INTO user_club (user_id, club_id, role, created_at, modified_at)
    SELECT u.user_id, min_club + ((u.user_id * 7) % 1000), 'MEMBER', NOW(), NOW()
    FROM "user" u WHERE u.user_id BETWEEN 1 AND 500
    ON CONFLICT DO NOTHING;

    -- 200명 추가 1개 더
    INSERT INTO user_club (user_id, club_id, role, created_at, modified_at)
    SELECT u.user_id, min_club + ((u.user_id * 13) % 1000), 'MEMBER', NOW(), NOW()
    FROM "user" u WHERE u.user_id BETWEEN 1 AND 200
    ON CONFLICT DO NOTHING;

    -- 각 클럽 첫 번째 가입자를 LEADER로
    UPDATE user_club SET role = 'LEADER'
    WHERE user_club_id IN (
        SELECT DISTINCT ON (club_id) user_club_id
        FROM user_club ORDER BY club_id, user_club_id
    );
END $$;

SELECT '  유저-클럽: ' || COUNT(*) AS msg FROM user_club WHERE user_id BETWEEN 1 AND 1000;

-- ═══════════════════════════════════════════
-- 5) 피드 50,000개
-- ═══════════════════════════════════════════
\echo '--- [5/14] 피드 생성 (50,000개) ---'

DO $$
DECLARE
    i INT;
    min_club BIGINT;
    v_type VARCHAR(20);
    v_parent BIGINT;
BEGIN
    SELECT MIN(club_id) INTO min_club FROM club;

    FOR i IN 0..49999 LOOP
        IF i < 40000 THEN
            v_type := 'ORIGINAL';
            v_parent := NULL;
        ELSE
            v_type := 'REFEED';
            v_parent := (i % 40000) + 1;
        END IF;

        INSERT INTO feed (content, club_id, user_id, type, parent_feed_id, root_feed_id,
                         like_count, comment_count, deleted, created_at, modified_at)
        VALUES (
            '테스트 피드 내용 #' || i || ' - 오늘의 활동 기록입니다.',
            min_club + (i % 1000), (i % 1000) + 1, v_type, v_parent, v_parent,
            FLOOR(RANDOM() * 30), FLOOR(RANDOM() * 15),
            FALSE,
            NOW() - ((50000 - i) || ' minutes')::INTERVAL,
            NOW()
        );

        IF i % 5000 = 0 AND i > 0 THEN
            RAISE NOTICE '    피드 진행: % / 50,000', i;
        END IF;
    END LOOP;
END $$;

SELECT '  피드: ' || COUNT(*) AS msg FROM feed;

-- ═══════════════════════════════════════════
-- 6) 피드 댓글 150,000개
-- ═══════════════════════════════════════════
\echo '--- [6/14] 피드 댓글 생성 (150,000개) ---'

DO $$
DECLARE
    i INT;
    min_feed BIGINT;
BEGIN
    SELECT MIN(feed_id) INTO min_feed FROM feed;

    FOR i IN 0..149999 LOOP
        INSERT INTO feed_comment (content, feed_id, user_id, created_at, modified_at)
        VALUES (
            '댓글 #' || i || ' - 좋은 글이네요! 함께해서 좋았습니다.',
            min_feed + (i % 50000),
            (i % 1000) + 1,
            NOW() - ((150000 - i) * 30 || ' seconds')::INTERVAL,
            NOW()
        );

        IF i % 10000 = 0 AND i > 0 THEN
            RAISE NOTICE '    댓글 진행: % / 150,000', i;
        END IF;
    END LOOP;
END $$;

SELECT '  댓글: ' || COUNT(*) AS msg FROM feed_comment;

-- ═══════════════════════════════════════════
-- 7) 피드 좋아요 100,000개
-- ═══════════════════════════════════════════
\echo '--- [7/14] 피드 좋아요 생성 (100,000개) ---'

DO $$
DECLARE
    i INT;
    min_feed BIGINT;
    v_feed_id BIGINT;
    v_user_id BIGINT;
BEGIN
    SELECT MIN(feed_id) INTO min_feed FROM feed;

    FOR i IN 0..99999 LOOP
        v_feed_id := min_feed + (i % 50000);
        v_user_id := ((i / 50000) * 500 + (i % 500)) + 1;

        INSERT INTO feed_like (feed_id, user_id, created_at, modified_at)
        VALUES (v_feed_id, v_user_id, NOW() - ((100000 - i) || ' minutes')::INTERVAL, NOW())
        ON CONFLICT DO NOTHING;

        IF i % 10000 = 0 AND i > 0 THEN
            RAISE NOTICE '    좋아요 진행: % / 100,000', i;
        END IF;
    END LOOP;
END $$;

SELECT '  좋아요: ' || COUNT(*) AS msg FROM feed_like;

-- ═══════════════════════════════════════════
-- 8) 피드 이미지 100,000개 (피드당 2개)
-- ═══════════════════════════════════════════
\echo '--- [8/14] 피드 이미지 생성 (100,000개) ---'

DO $$
DECLARE
    i INT;
    min_feed BIGINT;
BEGIN
    SELECT MIN(feed_id) INTO min_feed FROM feed;

    FOR i IN 0..99999 LOOP
        INSERT INTO feed_image (feed_image, feed_id, created_at, modified_at)
        VALUES (
            'https://d1c3fg3ti7m8cn.cloudfront.net/feed/' || (min_feed + (i / 2)) || '/img' || ((i % 2) + 1) || '.jpg',
            min_feed + (i / 2),
            NOW(),
            NOW()
        );

        IF i % 10000 = 0 AND i > 0 THEN
            RAISE NOTICE '    이미지 진행: % / 100,000', i;
        END IF;
    END LOOP;
END $$;

SELECT '  이미지: ' || COUNT(*) AS msg FROM feed_image;

-- ═══════════════════════════════════════════
-- 9) 스케줄 2,000개 + 유저스케줄 10,000개
-- ═══════════════════════════════════════════
\echo '--- [9/14] 스케줄 생성 (2,000개) ---'

DO $$
DECLARE
    i INT;
    min_club BIGINT;
    v_status VARCHAR(20);
BEGIN
    SELECT MIN(club_id) INTO min_club FROM club;

    FOR i IN 0..1999 LOOP
        CASE (i % 4)
            WHEN 0 THEN v_status := 'READY';
            WHEN 1 THEN v_status := 'ENDED';
            WHEN 2 THEN v_status := 'SETTLING';
            WHEN 3 THEN v_status := 'CLOSED';
        END CASE;

        INSERT INTO schedule (schedule_time, name, location, cost, user_limit, status, club_id, created_at, modified_at)
        VALUES (
            NOW() + ((i - 1000) || ' hours')::INTERVAL,
            '모임일정 ' || i,
            '장소 ' || ((i % 10) + 1),
            (FLOOR(RANDOM() * 10) + 1) * 1000,
            20,
            v_status,
            min_club + (i % 1000),
            NOW() - ((2000 - i) || ' hours')::INTERVAL,
            NOW()
        );
    END LOOP;
END $$;

-- 유저 스케줄 참여 (스케줄당 5명)
\echo '--- 유저 스케줄 참여 (10,000건) ---'

DO $$
DECLARE
    s INT;
    p INT;
    min_schedule BIGINT;
    v_role VARCHAR(20);
BEGIN
    SELECT MIN(schedule_id) INTO min_schedule FROM schedule;

    FOR s IN 0..1999 LOOP
        FOR p IN 0..4 LOOP
            IF p = 0 THEN v_role := 'LEADER'; ELSE v_role := 'MEMBER'; END IF;

            INSERT INTO user_schedule (user_id, schedule_id, role, created_at, modified_at)
            VALUES (
                ((s * 5 + p) % 1000) + 1,
                min_schedule + s,
                v_role,
                NOW(),
                NOW()
            )
            ON CONFLICT DO NOTHING;
        END LOOP;
    END LOOP;
END $$;

SELECT '  스케줄: ' || COUNT(*) AS msg FROM schedule;
SELECT '  유저스케줄: ' || COUNT(*) AS msg FROM user_schedule;

-- ═══════════════════════════════════════════
-- 10) 채팅방 500개 + 참여자 + 메시지 50,000개
-- ═══════════════════════════════════════════
\echo '--- [10/14] 채팅방 생성 (500개) ---'

DO $$
DECLARE
    i INT;
    min_club BIGINT;
    min_schedule BIGINT;
    v_type VARCHAR(20);
    v_schedule_id BIGINT;
BEGIN
    SELECT MIN(club_id) INTO min_club FROM club;
    SELECT MIN(schedule_id) INTO min_schedule FROM schedule;

    FOR i IN 0..499 LOOP
        IF i % 3 = 0 THEN
            v_type := 'SCHEDULE';
            v_schedule_id := min_schedule + (i % 2000);
        ELSE
            v_type := 'CLUB';
            v_schedule_id := NULL;
        END IF;

        INSERT INTO chat_room (club_id, schedule_id, type, created_at, modified_at)
        VALUES (
            min_club + (i % 1000),
            v_schedule_id,
            v_type,
            NOW() - ((500 - i) || ' days')::INTERVAL,
            NOW()
        );
    END LOOP;
END $$;

-- 채팅방 참여자 (방당 5명)
\echo '--- 채팅 참여자 (2,500건) ---'

DO $$
DECLARE
    r INT;
    p INT;
    min_chatroom BIGINT;
    v_role VARCHAR(20);
BEGIN
    SELECT MIN(chat_room_id) INTO min_chatroom FROM chat_room;

    FOR r IN 0..499 LOOP
        FOR p IN 0..4 LOOP
            IF p = 0 THEN v_role := 'LEADER'; ELSE v_role := 'MEMBER'; END IF;

            INSERT INTO user_chat_room (chat_room_id, user_id, role, created_at, modified_at)
            VALUES (
                min_chatroom + r,
                ((r * 5 + p) % 1000) + 1,
                v_role,
                NOW(),
                NOW()
            )
            ON CONFLICT DO NOTHING;
        END LOOP;
    END LOOP;
END $$;

-- 메시지 50,000개
\echo '--- 채팅 메시지 (50,000건) ---'

DO $$
DECLARE
    i INT;
    min_chatroom BIGINT;
BEGIN
    SELECT MIN(chat_room_id) INTO min_chatroom FROM chat_room;

    FOR i IN 0..49999 LOOP
        INSERT INTO message (chat_room_id, user_id, text, sent_at, deleted, created_at, modified_at)
        VALUES (
            min_chatroom + (i % 500),
            (i % 1000) + 1,
            '채팅 메시지 #' || i || ' - 안녕하세요! 오늘 모임 어떠셨나요?',
            NOW() - ((50000 - i) * 30 || ' seconds')::INTERVAL,
            FALSE,
            NOW() - ((50000 - i) * 30 || ' seconds')::INTERVAL,
            NOW()
        );

        IF i % 10000 = 0 AND i > 0 THEN
            RAISE NOTICE '    메시지 진행: % / 50,000', i;
        END IF;
    END LOOP;
END $$;

SELECT '  채팅방: ' || COUNT(*) AS msg FROM chat_room;
SELECT '  참여자: ' || COUNT(*) AS msg FROM user_chat_room;
SELECT '  메시지: ' || COUNT(*) AS msg FROM message;

-- ═══════════════════════════════════════════
-- 11) 지갑 1,000개
-- ═══════════════════════════════════════════
\echo '--- [11/14] 지갑 생성 (1,000개) ---'

INSERT INTO wallet (user_id, posted_balance, pending_out, created_at, modified_at)
SELECT u.user_id, 100000, 0, NOW(), NOW()
FROM "user" u WHERE u.user_id BETWEEN 1 AND 1000
ON CONFLICT (user_id) DO UPDATE SET posted_balance = 100000, pending_out = 0, modified_at = NOW();

SELECT '  지갑: ' || COUNT(*) AS msg FROM wallet WHERE user_id BETWEEN 1 AND 1000;

-- ═══════════════════════════════════════════
-- 12) 정산 500건 + 유저정산 5,000건
-- ═══════════════════════════════════════════
\echo '--- [12/14] 정산 생성 (500건) ---'

-- 테스트 전용 스케줄 삽입 (schedule_id는 IDENTITY이므로 50001~50500 범위에 직접 삽입 불가)
-- 대신 별도 정산용 스케줄 생성
DO $$
DECLARE
    i INT;
    min_club BIGINT;
    v_schedule_id BIGINT;
BEGIN
    SELECT MIN(club_id) INTO min_club FROM club;

    -- 정산용 스케줄 500개 생성
    FOR i IN 1..500 LOOP
        INSERT INTO schedule (schedule_time, name, location, cost, user_limit, status, club_id, created_at, modified_at)
        VALUES (
            NOW() - INTERVAL '1 day',
            '정산테스트 스케줄 ' || i,
            'LoadTest Location',
            1000,
            20,
            'ENDED',
            min_club,
            NOW(),
            NOW()
        );
    END LOOP;

    -- 생성된 정산용 스케줄에 대해 정산 생성
    FOR v_schedule_id IN (SELECT schedule_id FROM schedule WHERE name LIKE '정산테스트 스케줄%' ORDER BY schedule_id LIMIT 500)
    LOOP
        INSERT INTO settlement (schedule_id, sum, total_status, user_id, created_at, modified_at)
        VALUES (v_schedule_id, 0, 'HOLDING', 1, NOW(), NOW());
    END LOOP;

    -- 유저정산 (각 정산당 10명)
    INSERT INTO user_settlement (settlement_id, user_id, status, created_at, modified_at)
    SELECT s.settlement_id, p.user_id, 'HOLD_ACTIVE', NOW(), NOW()
    FROM settlement s
    CROSS JOIN (SELECT user_id FROM "user" WHERE user_id BETWEEN 2 AND 11) p
    WHERE s.total_status = 'HOLDING'
    AND s.schedule_id IN (SELECT schedule_id FROM schedule WHERE name LIKE '정산테스트 스케줄%');
END $$;

SELECT '  정산: ' || COUNT(*) AS msg FROM settlement WHERE total_status = 'HOLDING';
SELECT '  유저정산: ' || COUNT(*) AS msg FROM user_settlement;

-- ═══════════════════════════════════════════
-- 13) 알림 100,000건
-- ═══════════════════════════════════════════
\echo '--- [13/14] 알림 생성 (100,000건) ---'

DO $$
DECLARE
    i INT;
    v_type VARCHAR(50);
    v_read BOOLEAN;
    v_sent BOOLEAN;
    types TEXT[] := ARRAY['FEED','CHAT','SCHEDULE','CLUB','SETTLEMENT'];
BEGIN
    FOR i IN 0..99999 LOOP
        v_type := types[(i % 5) + 1];
        v_read := RANDOM() < 0.3;
        v_sent := RANDOM() < 0.7;

        INSERT INTO notification (content, is_read, type, user_id, sse_sent, created_at, modified_at)
        VALUES (
            '알림 #' || i || ' - ' || v_type || ' 관련 알림입니다.',
            v_read, v_type,
            (i % 1000) + 1,
            v_sent,
            NOW() - ((100000 - i) * 30 || ' seconds')::INTERVAL,
            NOW()
        );

        IF i % 10000 = 0 AND i > 0 THEN
            RAISE NOTICE '    알림 진행: % / 100,000', i;
        END IF;
    END LOOP;
END $$;

SELECT '  알림: ' || COUNT(*) AS msg FROM notification;

-- ═══════════════════════════════════════════
-- 14) FCM 토큰 1,000개
-- ═══════════════════════════════════════════
\echo '--- [14/14] FCM 토큰 생성 (1,000개) ---'

INSERT INTO fcm_token (user_id, token, device_type, created_at, modified_at)
SELECT
    u.user_id,
    'fcm-token-loadtest-' || u.user_id || '-' || gen_random_uuid(),
    CASE WHEN u.user_id % 2 = 0 THEN 'ANDROID' ELSE 'IOS' END,
    NOW(), NOW()
FROM "user" u WHERE u.user_id BETWEEN 1 AND 1000
ON CONFLICT DO NOTHING;

SELECT '  FCM 토큰: ' || COUNT(*) AS msg FROM fcm_token WHERE user_id BETWEEN 1 AND 1000;

-- ═══════════════════════════════════════════
-- 카운트 동기화
-- ═══════════════════════════════════════════
\echo '--- 피드 카운트 동기화 ---'

UPDATE feed f SET
    like_count = (SELECT COUNT(*) FROM feed_like fl WHERE fl.feed_id = f.feed_id),
    comment_count = (SELECT COUNT(*) FROM feed_comment fc WHERE fc.feed_id = f.feed_id);

-- 클럽 멤버 카운트 동기화
UPDATE club c SET
    member_count = (SELECT COUNT(*) FROM user_club uc WHERE uc.club_id = c.club_id);

-- ═══════════════════════════════════════════
-- 최종 결과
-- ═══════════════════════════════════════════
\echo '========================================'
\echo '=== 시드 데이터 최종 결과 ==='
\echo '========================================'

SELECT 'user:            ' || COUNT(*) AS result FROM "user";
SELECT 'interest:        ' || COUNT(*) AS result FROM interest;
SELECT 'user_interest:   ' || COUNT(*) AS result FROM user_interest;
SELECT 'club:            ' || COUNT(*) AS result FROM club;
SELECT 'user_club:       ' || COUNT(*) AS result FROM user_club;
SELECT 'feed:            ' || COUNT(*) AS result FROM feed;
SELECT 'feed_comment:    ' || COUNT(*) AS result FROM feed_comment;
SELECT 'feed_like:       ' || COUNT(*) AS result FROM feed_like;
SELECT 'feed_image:      ' || COUNT(*) AS result FROM feed_image;
SELECT 'schedule:        ' || COUNT(*) AS result FROM schedule;
SELECT 'user_schedule:   ' || COUNT(*) AS result FROM user_schedule;
SELECT 'chat_room:       ' || COUNT(*) AS result FROM chat_room;
SELECT 'user_chat_room:  ' || COUNT(*) AS result FROM user_chat_room;
SELECT 'message:         ' || COUNT(*) AS result FROM message;
SELECT 'wallet:          ' || COUNT(*) AS result FROM wallet;
SELECT 'settlement:      ' || COUNT(*) AS result FROM settlement;
SELECT 'user_settlement: ' || COUNT(*) AS result FROM user_settlement;
SELECT 'notification:    ' || COUNT(*) AS result FROM notification;
SELECT 'fcm_token:       ' || COUNT(*) AS result FROM fcm_token;

\echo '=== 완료 ==='
