// =============================================================
// seed-mongo-highload.js
// MongoDB 알림 고부하 시드 (14M+ 데이터, MySQL 분포 미러링)
// =============================================================
//
// 실행 (Docker MongoDB):
//   docker exec -i onlyone-mongodb mongosh \
//     "mongodb://root:root@localhost:27017/onlyone?authSource=admin" \
//     < k6-tests/seed-mongo-highload.js
//
// MySQL 데이터 분포:
//   hot (1-10):       233K → 평균 23K/유저
//   normal_1 (11-100): 2.05M → 평균 22.7K/유저
//   normal_2 (101-500): 6.36M → 평균 15.9K/유저
//   normal_3 (501-1000): 5.61M → 평균 11.2K/유저
//   총: ~14.25M
//   미읽음: ~39%, 미전달: ~33%
//
// 예상 소요: 15~30분 (4 CPU, 4GB 기준)
// =============================================================

const DB_NAME = "onlyone";
const COLLECTION = "notifications";
const BATCH_SIZE = 5000;

const db = db.getSiblingDB(DB_NAME);
const coll = db.getCollection(COLLECTION);

const TYPES = ["CHAT", "SETTLEMENT", "LIKE", "COMMENT", "REFEED"];
const CLASS_NAME = "com.example.onlyone.domain.notification.entity.NotificationDocument";

function randomType() {
    return TYPES[Math.floor(Math.random() * TYPES.length)];
}

function randomPastDate(maxSecondsAgo) {
    return new Date(Date.now() - Math.floor(Math.random() * maxSecondsAgo * 1000));
}

// ============================================
// Step 0: 기존 데이터 정리 + 인덱스 생성
// ============================================

print("\n==============================================");
print("MongoDB 14M 고부하 시드 시작");
print(`시작 시각: ${new Date().toISOString()}`);
print("==============================================");

print("\n[Step 0] 기존 데이터 정리...");
const deleted = coll.deleteMany({});
print(`  삭제: ${deleted.deletedCount}건`);

// counters 컬렉션 초기화 (sequence 리셋)
db.getCollection("counters").deleteMany({});
print("  counters 컬렉션 초기화 완료");

// ============================================
// Step 1: 인덱스 먼저 생성 (데이터 삽입 전에 생성하면 빌드 시간 최소화)
// 대량 데이터에서는 삽입 후 인덱스 생성이 더 빠르지만,
// numericId unique 인덱스는 먼저 필요
// ============================================

print("\n[Step 1] 인덱스 생성...");
coll.dropIndexes();
coll.createIndex({ numericId: 1 }, { name: "idx_numericId", unique: true });
print("  idx_numericId (unique) 생성 완료 — 나머지는 삽입 후 생성");

// ============================================
// Step 2: 대량 데이터 삽입
// ============================================

// 유저 그룹별 설정 (MySQL 분포 미러링)
const USER_GROUPS = [
    { name: "hot (1-10)",        start: 1,   end: 10,   perUser: 23000, unreadRatio: 0.16, undeliveredRatio: 0.28 },
    { name: "normal_1 (11-100)", start: 11,  end: 100,  perUser: 22700, unreadRatio: 0.26, undeliveredRatio: 0.29 },
    { name: "normal_2 (101-500)",start: 101, end: 500,  perUser: 15900, unreadRatio: 0.42, undeliveredRatio: 0.37 },
    { name: "normal_3 (501-1000)",start: 501, end: 1000, perUser: 11200, unreadRatio: 0.43, undeliveredRatio: 0.30 },
];

let globalSeq = 0;
const totalStart = Date.now();

for (const group of USER_GROUPS) {
    const userCount = group.end - group.start + 1;
    const groupTotal = userCount * group.perUser;

    print(`\n[Step 2] ${group.name}: ${userCount}명 × ${group.perUser}건 = ${groupTotal.toLocaleString()}건`);
    const groupStart = Date.now();

    let batch = [];
    let groupInserted = 0;

    for (let userId = group.start; userId <= group.end; userId++) {
        for (let seq = 0; seq < group.perUser; seq++) {
            globalSeq++;
            batch.push({
                numericId: NumberLong(globalSeq),
                userId: NumberLong(userId),
                content: `고부하 알림 #${seq + 1} - ${userId}`,
                type: randomType(),
                isRead: Math.random() >= group.unreadRatio,       // unreadRatio만큼 미읽음
                delivered: Math.random() >= group.undeliveredRatio, // undeliveredRatio만큼 미전달
                createdAt: randomPastDate(86400),                  // 최근 24시간
                _class: CLASS_NAME
            });

            if (batch.length >= BATCH_SIZE) {
                coll.insertMany(batch, { ordered: false });
                groupInserted += batch.length;
                batch = [];

                if (groupInserted % 100000 === 0) {
                    const pct = ((groupInserted / groupTotal) * 100).toFixed(1);
                    const elapsed = ((Date.now() - groupStart) / 1000).toFixed(0);
                    print(`    ${groupInserted.toLocaleString()}/${groupTotal.toLocaleString()} (${pct}%) - ${elapsed}s`);
                }
            }
        }
    }

    if (batch.length > 0) {
        coll.insertMany(batch, { ordered: false });
        groupInserted += batch.length;
    }

    const groupElapsed = ((Date.now() - groupStart) / 1000).toFixed(1);
    print(`  완료: ${groupInserted.toLocaleString()}건 (${groupElapsed}s)`);
}

// counters 시퀀스 값 세팅 (다음 save()부터 이어서 채번)
db.getCollection("counters").updateOne(
    { _id: "notification_seq" },
    { $set: { seq: NumberLong(globalSeq) } },
    { upsert: true }
);
print(`\n  counters.notification_seq = ${globalSeq}`);

// ============================================
// Step 3: 나머지 인덱스 생성 (데이터 삽입 후 — 빌드 속도 최적)
// ============================================

print("\n[Step 3] 복합 인덱스 생성 (데이터 삽입 후)...");
const idxStart = Date.now();

coll.createIndex({ userId: 1, numericId: -1 }, { name: "idx_user_numid_desc" });
print(`  idx_user_numid_desc 완료 (${((Date.now() - idxStart) / 1000).toFixed(1)}s)`);

const idx2Start = Date.now();
coll.createIndex({ userId: 1, isRead: 1, numericId: 1 }, { name: "idx_user_read" });
print(`  idx_user_read 완료 (${((Date.now() - idx2Start) / 1000).toFixed(1)}s)`);

const idx3Start = Date.now();
coll.createIndex({ userId: 1, delivered: 1, numericId: 1 }, { name: "idx_user_delivered" });
print(`  idx_user_delivered 완료 (${((Date.now() - idx3Start) / 1000).toFixed(1)}s)`);

// ============================================
// Step 4: 검증
// ============================================

print("\n[Step 4] 데이터 검증");

const total = coll.countDocuments({});
const unread = coll.countDocuments({ isRead: false });
const delivered = coll.countDocuments({ delivered: true });

print(`  전체:      ${total.toLocaleString()}건`);
print(`  미읽음:    ${unread.toLocaleString()}건 (${((unread / total) * 100).toFixed(1)}%)`);
print(`  전달됨:    ${delivered.toLocaleString()}건 (${((delivered / total) * 100).toFixed(1)}%)`);

// 유저 그룹별 분포
print("\n  유저 그룹별:");
for (const group of USER_GROUPS) {
    const grpTotal = coll.countDocuments({ userId: { $gte: group.start, $lte: group.end } });
    const grpUnread = coll.countDocuments({ userId: { $gte: group.start, $lte: group.end }, isRead: false });
    print(`    ${group.name}: ${grpTotal.toLocaleString()}건, 미읽음 ${grpUnread.toLocaleString()} (${((grpUnread / grpTotal) * 100).toFixed(1)}%)`);
}

// 샘플 유저
print("\n  샘플 유저:");
[1, 5, 50, 100, 500, 1000].forEach(uid => {
    const uTotal = coll.countDocuments({ userId: uid });
    const uUnread = coll.countDocuments({ userId: uid, isRead: false });
    print(`    user ${uid}: total=${uTotal.toLocaleString()}, unread=${uUnread.toLocaleString()}`);
});

// 인덱스 확인
print("\n  인덱스:");
coll.getIndexes().forEach(idx => {
    print(`    ${idx.name}: ${JSON.stringify(idx.key)}`);
});

// sequence 확인
const seqDoc = db.getCollection("counters").findOne({ _id: "notification_seq" });
print(`\n  sequence: notification_seq = ${seqDoc ? seqDoc.seq : 'N/A'}`);

const totalElapsed = ((Date.now() - totalStart) / 1000).toFixed(1);
print(`\n==============================================`);
print(`MongoDB 14M 고부하 시드 완료`);
print(`총 삽입: ${globalSeq.toLocaleString()}건`);
print(`총 소요: ${totalElapsed}s`);
print(`종료 시각: ${new Date().toISOString()}`);
print(`==============================================`);
