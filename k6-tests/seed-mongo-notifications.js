// =============================================================
// MongoDB 알림 시드 데이터 생성 — 100x 스케일
// =============================================================
// 실행: docker exec onlyone-mongodb mongosh -u root -p root --authenticationDatabase admin onlyone /scripts/seed-mongo-notifications.js
//
// 규모: 유저 100,000명 × 유저당 500건 = 50,000,000 알림
// 유저당 읽음 30%, 전송됨 70%
// =============================================================

const BATCH_SIZE = 10000;
const TOTAL_USERS = 100000;
const NOTIFICATIONS_PER_USER = 500;
const TOTAL = TOTAL_USERS * NOTIFICATIONS_PER_USER;

const TYPES = ['FEED', 'CHAT', 'SCHEDULE', 'CLUB', 'SETTLEMENT'];

db.notifications.drop();
db.counters.drop();

print("=== 알림 MongoDB 시드 데이터 생성 시작 (100x) ===");
print(`목표: ${TOTAL.toLocaleString()} 알림 (${TOTAL_USERS.toLocaleString()} 유저 × ${NOTIFICATIONS_PER_USER})`);

const startTime = Date.now();
let totalInserted = 0;
let batch = [];
let numericId = 1;

function flushBatch() {
    if (batch.length === 0) return;
    db.notifications.insertMany(batch, { ordered: false });
    totalInserted += batch.length;
    batch = [];

    if (totalInserted % 500000 === 0) {
        const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
        const pct = ((totalInserted / TOTAL) * 100).toFixed(1);
        print(`  [${elapsed}s] ${totalInserted.toLocaleString()} / ${TOTAL.toLocaleString()} (${pct}%)`);
    }
}

for (let userId = 1; userId <= TOTAL_USERS; userId++) {
    for (let n = 0; n < NOTIFICATIONS_PER_USER; n++) {
        const type = TYPES[n % TYPES.length];
        const isRead = Math.random() < 0.3;
        const delivered = Math.random() < 0.7;
        const baseDate = new Date('2026-01-01T00:00:00Z');
        const createdAt = new Date(baseDate.getTime() + numericId * 100);

        batch.push({
            numericId: numericId,
            userId: userId,
            content: `알림 #${numericId} - ${type} 관련 알림입니다. 확인해주세요.`,
            type: type,
            isRead: isRead,
            delivered: delivered,
            createdAt: createdAt
        });

        numericId++;

        if (batch.length >= BATCH_SIZE) flushBatch();
    }
}
flushBatch();

const elapsed1 = ((Date.now() - startTime) / 1000).toFixed(1);
print(`\n삽입 완료: ${totalInserted.toLocaleString()} in ${elapsed1}s`);

// 인덱스 생성
print('\n--- 인덱스 생성 ---');
db.notifications.createIndex({ numericId: 1 }, { unique: true, name: "idx_numericId" });
db.notifications.createIndex({ userId: 1, numericId: -1 }, { name: "idx_user_numid_desc" });
db.notifications.createIndex({ userId: 1, isRead: 1, numericId: 1 }, { name: "idx_user_read" });
db.notifications.createIndex({ userId: 1, delivered: 1, numericId: 1 }, { name: "idx_user_delivered" });
print('인덱스 4개 생성 완료');

// 카운터 초기화 (segment 기반 ID 생성용)
db.counters.insertOne({
    _id: "notification_seq",
    seq: numericId
});
print(`카운터 초기화: notification_seq = ${numericId}`);

// 검증
print('\n--- 확인 ---');
print(`총 알림: ${db.notifications.countDocuments()}`);
print(`유저 1 알림 수: ${db.notifications.countDocuments({ userId: 1 })}`);
print(`유저 1 읽지않은 수: ${db.notifications.countDocuments({ userId: 1, isRead: false })}`);
print(`유저 1 미전송 수: ${db.notifications.countDocuments({ userId: 1, delivered: false })}`);

// 타입별 분포
for (const t of TYPES) {
    print(`  ${t}: ${db.notifications.countDocuments({ type: t })}`);
}

const totalElapsed = ((Date.now() - startTime) / 1000).toFixed(1);
print(`\n=== 전체 완료: ${totalElapsed}s ===`);
