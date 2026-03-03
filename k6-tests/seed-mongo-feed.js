// MongoDB 피드 시드 데이터 생성 — 100x 스케일
// ORIGINAL 50,000,000 + REFEED 5,000,000 = 55,000,000
//
// 실행: docker exec onlyone-mongodb mongosh -u root -p root --authenticationDatabase admin onlyone /scripts/seed-mongo-feed.js
//
// feedId 생성 패턴:
//   ORIGINAL: feedId = clubId + N * 50000  (N = 0..999)  → 50000 클럽 × 1000 = 50,000,000
//   REFEED:   feedId = 60000000 + seq                     → 5,000,000
//
// k6 테스트 호환: clubId + offset * 50000 (offset=0~19) 패턴 피드 포함
//   예: feedId 64, 50064, 100064, 150064, ... (offset=0~19 → N=0,1,2,3,...)

const BATCH_SIZE = 10000;
const TOTAL_USERS = 100000;
const TOTAL_CLUBS = 50000;
const ORIGINAL_PER_CLUB = 1000;     // 클럽당 1000개 원본
const REFEED_TOTAL = 5000000;        // 리피드 총 5,000,000개
const IMAGES_PER_FEED = 3;
const LIKES_RANGE = [0, 30];
const COMMENTS_RANGE = [0, 15];
const EMBEDDED_COMMENT_LIMIT = 10;

const HOT_CLUB_IDS = [64, 159, 381, 501, 747];

// 컬렉션 초기화
db.feed.drop();
print("=== 피드 MongoDB 시드 데이터 생성 시작 (100x) ===");
const TOTAL_ORIGINAL = TOTAL_CLUBS * ORIGINAL_PER_CLUB;
const TOTAL = TOTAL_ORIGINAL + REFEED_TOTAL;
print(`목표: ORIGINAL ${TOTAL_ORIGINAL.toLocaleString()} + REFEED ${REFEED_TOTAL.toLocaleString()} = ${TOTAL.toLocaleString()}`);

const startTime = Date.now();
let totalInserted = 0;
let batch = [];

function randInt(min, max) {
    return Math.floor(Math.random() * (max - min + 1)) + min;
}

function randomContent(feedId) {
    const templates = [
        "오늘의 활동 기록",
        "모임에서 즐거운 시간 보냈습니다",
        "새로운 멤버를 환영합니다!",
        "이번 주 일정 공유합니다",
        "함께해서 좋았어요",
    ];
    return `${templates[feedId % templates.length]} #${feedId}`;
}

function generateImageUrls(feedId) {
    const urls = [];
    for (let i = 0; i < IMAGES_PER_FEED; i++) {
        urls.push(`https://d1c3fg3ti7m8cn.cloudfront.net/feed/${feedId}/img${i + 1}.jpg`);
    }
    return urls;
}

function generateLikerUserIds() {
    const count = randInt(LIKES_RANGE[0], LIKES_RANGE[1]);
    const likers = new Set();
    while (likers.size < count) {
        likers.add(randInt(1, TOTAL_USERS));
    }
    return Array.from(likers);
}

function generateComments(feedId, count) {
    const comments = [];
    const baseDate = new Date('2026-01-15T00:00:00Z');
    for (let i = 0; i < count; i++) {
        const userId = randInt(1, TOTAL_USERS);
        comments.push({
            commentId: feedId * 100 + i,
            userId: userId,
            nickname: `테스트유저${userId}`,
            profileImage: null,
            content: `댓글 #${i + 1} on feed ${feedId}`,
            createdAt: new Date(baseDate.getTime() + i * 60000)
        });
    }
    return comments;
}

function flushBatch() {
    if (batch.length === 0) return;
    db.feed.insertMany(batch, { ordered: false });
    totalInserted += batch.length;
    batch = [];

    if (totalInserted % 500000 === 0) {
        const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
        const pct = ((totalInserted / TOTAL) * 100).toFixed(1);
        print(`  [${elapsed}s] ${totalInserted.toLocaleString()} / ${TOTAL.toLocaleString()} (${pct}%)`);
    }
}

// ── ORIGINAL 피드 생성 (50,000,000) ──
print("\n--- ORIGINAL 피드 생성 (50,000,000) ---");

// feedId = clubId + N * 50000 (N=0~999)
for (let n = 0; n < ORIGINAL_PER_CLUB; n++) {
    for (let clubId = 1; clubId <= TOTAL_CLUBS; clubId++) {
        const feedId = clubId + n * TOTAL_CLUBS;
        const userId = ((feedId - 1) % TOTAL_USERS) + 1;
        const baseDate = new Date('2026-01-01T00:00:00Z');
        const createdAt = new Date(baseDate.getTime() + totalInserted * 2);

        const likerUserIds = generateLikerUserIds();
        const totalComments = randInt(COMMENTS_RANGE[0], COMMENTS_RANGE[1]);
        const embeddedComments = generateComments(feedId, Math.min(totalComments, EMBEDDED_COMMENT_LIMIT));

        let extraLikes = HOT_CLUB_IDS.includes(clubId) ? randInt(10, 50) : 0;

        batch.push({
            feedId: feedId,
            content: randomContent(feedId),
            feedType: "ORIGINAL",
            clubId: clubId,
            clubName: `테스트모임${clubId}`,
            userId: userId,
            nickname: `테스트유저${userId}`,
            profileImage: null,
            parentFeedId: null,
            rootFeedId: null,
            likeCount: likerUserIds.length + extraLikes,
            commentCount: totalComments,
            imageUrls: generateImageUrls(feedId),
            likerUserIds: likerUserIds,
            recentComments: embeddedComments,
            deleted: false,
            createdAt: createdAt,
            modifiedAt: createdAt,
            deletedAt: null
        });

        if (batch.length >= BATCH_SIZE) flushBatch();
    }
}
flushBatch();
print(`  ORIGINAL 완료: ${totalInserted.toLocaleString()}`);

// ── REFEED 피드 생성 (5,000,000) ──
print("\n--- REFEED 피드 생성 (5,000,000) ---");

const refeedBaseId = 60000000;
for (let i = 0; i < REFEED_TOTAL; i++) {
    const feedId = refeedBaseId + i;
    const clubId = (i % TOTAL_CLUBS) + 1;
    const userId = randInt(1, TOTAL_USERS);
    const baseDate = new Date('2026-02-01T00:00:00Z');
    const createdAt = new Date(baseDate.getTime() + i * 2);

    // 해당 클럽의 원본 피드를 랜덤 참조
    const parentN = randInt(0, Math.min(ORIGINAL_PER_CLUB - 1, 19));
    const parentFeedId = clubId + parentN * TOTAL_CLUBS;

    batch.push({
        feedId: feedId,
        content: `리피드: ${randomContent(parentFeedId)}`,
        feedType: "REFEED",
        clubId: clubId,
        clubName: `테스트모임${clubId}`,
        userId: userId,
        nickname: `테스트유저${userId}`,
        profileImage: null,
        parentFeedId: parentFeedId,
        rootFeedId: parentFeedId,
        likeCount: randInt(0, 5),
        commentCount: 0,
        imageUrls: [],
        likerUserIds: [],
        recentComments: [],
        deleted: false,
        createdAt: createdAt,
        modifiedAt: createdAt,
        deletedAt: null
    });

    if (batch.length >= BATCH_SIZE) flushBatch();
}
flushBatch();

const elapsed1 = ((Date.now() - startTime) / 1000).toFixed(1);
print(`\n=== 삽입 완료: ${totalInserted.toLocaleString()} feeds in ${elapsed1}s ===`);

// ── 인덱스 생성 ──
print('\n--- 인덱스 생성 ---');
db.feed.createIndex({ feedId: 1 }, { unique: true, name: "idx_feedId" });
db.feed.createIndex({ clubId: 1, deleted: 1, createdAt: -1 }, { name: "idx_club_deleted_created" });
db.feed.createIndex({ clubId: 1, deleted: 1, parentFeedId: 1, createdAt: -1 }, { name: "idx_club_deleted_parent" });
db.feed.createIndex({ parentFeedId: 1, deleted: 1 }, { name: "idx_parent_deleted" });
db.feed.createIndex({ deleted: 1, clubId: 1, createdAt: -1, likeCount: 1, commentCount: 1 }, { name: "idx_deleted_created_score" });
db.feed.createIndex({ "likerUserIds": 1, feedId: 1 }, { name: "idx_likers_feedId" });
db.feed.createIndex({ deleted: 1, createdAt: -1, clubId: 1 }, { name: "idx_personal_feed" });
print('인덱스 7개 생성 완료');

// ── 확인 ──
print('\n--- 확인 ---');
print(`feed count: ${db.feed.countDocuments()}`);
print(`ORIGINAL: ${db.feed.countDocuments({ feedType: "ORIGINAL" })}`);
print(`REFEED: ${db.feed.countDocuments({ feedType: "REFEED" })}`);

// k6 테스트에서 사용하는 핫피드 존재 확인
const hotFeeds = [64, 159, 381, 501, 747, 50064, 100064, 50159];
for (const fid of hotFeeds) {
    const exists = db.feed.countDocuments({ feedId: fid });
    print(`  feedId=${fid}: ${exists > 0 ? 'OK' : 'MISSING'}`);
}

print(`\nsample (feedId=64): ${JSON.stringify(db.feed.findOne({ feedId: 64 }), null, 2)}`);

const totalElapsed = ((Date.now() - startTime) / 1000).toFixed(1);
print(`\n=== 전체 완료: ${totalElapsed}s ===`);
