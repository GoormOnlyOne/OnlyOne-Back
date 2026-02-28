// =============================================================
// seed-data-mongo.js
// MongoDB 알림 시드 데이터 (MySQL seed-data.sql Phase 5 미러링)
// =============================================================
//
// 실행 방법 (mongosh):
//   mongosh "mongodb://localhost:27017/onlyone" k6-tests/seed-data-mongo.js
//
// Docker 실행 (MongoDB가 Docker에서 실행 중인 경우):
//   docker exec -i onlyone-mongodb mongosh "mongodb://localhost:27017/onlyone" < k6-tests/seed-data-mongo.js
//
// 데이터 구조 (NotificationDocument.java 매핑):
//   {
//     _id:        ObjectId (자동 생성)
//     userId:     Long     (1~100)
//     content:    String   ("테스트 알림 #N - userId")
//     type:       String   (CHAT | SETTLEMENT | LIKE | COMMENT | REFEED)
//     isRead:     Boolean  (false)
//     delivered:  Boolean  (true — MySQL sse_sent=1 대응)
//     createdAt:  ISODate  (NOW - random 0~3600초)
//   }
//
// 예상 결과:
//   Phase 1: 기본 5,000건 (50 seq × 100 users)
//   Phase 2: 선택적 대량 데이터 (함수 호출)
// =============================================================

const DB_NAME = "onlyone";
const COLLECTION = "notifications";
const BATCH_SIZE = 1000;

const db = db.getSiblingDB(DB_NAME);
const coll = db.getCollection(COLLECTION);

const TYPES = ["CHAT", "SETTLEMENT", "LIKE", "COMMENT", "REFEED"];

// ============================================
// 유틸리티 함수
// ============================================

function randomType() {
    return TYPES[Math.floor(Math.random() * TYPES.length)];
}

function randomPastDate(maxSecondsAgo) {
    return new Date(Date.now() - Math.floor(Math.random() * maxSecondsAgo * 1000));
}

function printProgress(label, current, total) {
    if (current % 1000 === 0 || current === total) {
        print(`  [${label}] ${current}/${total} (${((current / total) * 100).toFixed(1)}%)`);
    }
}

// ============================================
// Phase 1: 기본 알림 데이터 (5,000건)
// MySQL Phase 5와 동일: 50 seq × 100 users
// ============================================

function seedBaseNotifications() {
    print("\n========================================");
    print("Phase 1: 기본 알림 데이터 삽입 (5,000건)");
    print("========================================");

    const startTime = Date.now();
    const USER_MIN = 1;
    const USER_MAX = 100;
    const SEQ_COUNT = 50;
    const TOTAL = (USER_MAX - USER_MIN + 1) * SEQ_COUNT;

    // 기존 데이터 삭제
    const deleted = coll.deleteMany({});
    print(`  기존 데이터 삭제: ${deleted.deletedCount}건`);

    let batch = [];
    let inserted = 0;

    for (let userId = USER_MIN; userId <= USER_MAX; userId++) {
        for (let seq = 1; seq <= SEQ_COUNT; seq++) {
            batch.push({
                userId: NumberLong(userId),
                content: `테스트 알림 #${seq} - ${userId}`,
                type: randomType(),
                isRead: false,
                delivered: true,  // MySQL sse_sent=1 대응
                createdAt: randomPastDate(3600),  // 최근 1시간 이내
                _class: "com.example.onlyone.domain.notification.entity.NotificationDocument"
            });

            if (batch.length >= BATCH_SIZE) {
                coll.insertMany(batch);
                inserted += batch.length;
                printProgress("base", inserted, TOTAL);
                batch = [];
            }
        }
    }

    if (batch.length > 0) {
        coll.insertMany(batch);
        inserted += batch.length;
    }

    const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
    print(`  완료: ${inserted}건 삽입 (${elapsed}s)`);
    return inserted;
}

// ============================================
// Phase 2 (선택): 읽음/미읽음 분포 추가
// 일부 알림을 읽음 상태로 변경하여 현실적 분포 생성
// ============================================

function seedReadDistribution() {
    print("\n========================================");
    print("Phase 2: 읽음/미읽음 분포 적용");
    print("========================================");

    const startTime = Date.now();

    // 전체의 약 30%를 읽음 처리 (user 1~30의 모든 알림)
    const result = coll.updateMany(
        { userId: { $lte: 30 } },
        { $set: { isRead: true } }
    );
    print(`  읽음 처리: ${result.modifiedCount}건 (user 1~30)`);

    // user 31~60의 알림 중 절반을 읽음 처리 (오래된 것부터)
    const halfReadResult = coll.updateMany(
        {
            userId: { $gte: 31, $lte: 60 },
            createdAt: { $lt: new Date(Date.now() - 1800 * 1000) }  // 30분 이전
        },
        { $set: { isRead: true } }
    );
    print(`  부분 읽음 처리: ${halfReadResult.modifiedCount}건 (user 31~60, 30분 이전)`);

    // user 61~100은 전부 미읽음 유지 (기본값)
    print(`  미읽음 유지: user 61~100`);

    const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
    print(`  완료 (${elapsed}s)`);
}

// ============================================
// Phase 3 (선택): 미전달 알림 추가
// SSE 재전송 테스트용 — delivered=false 데이터
// ============================================

function seedUndeliveredNotifications() {
    print("\n========================================");
    print("Phase 3: 미전달 알림 추가 (1,000건)");
    print("========================================");

    const startTime = Date.now();
    let batch = [];
    let inserted = 0;
    const TOTAL = 1000;

    for (let userId = 1; userId <= 100; userId++) {
        for (let seq = 1; seq <= 10; seq++) {
            batch.push({
                userId: NumberLong(userId),
                content: `미전달 알림 #${seq} - ${userId}`,
                type: randomType(),
                isRead: false,
                delivered: false,  // SSE 미전달
                createdAt: randomPastDate(300),  // 최근 5분 이내
                _class: "com.example.onlyone.domain.notification.entity.NotificationDocument"
            });

            if (batch.length >= BATCH_SIZE) {
                coll.insertMany(batch);
                inserted += batch.length;
                printProgress("undelivered", inserted, TOTAL);
                batch = [];
            }
        }
    }

    if (batch.length > 0) {
        coll.insertMany(batch);
        inserted += batch.length;
    }

    const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
    print(`  완료: ${inserted}건 삽입 (${elapsed}s)`);
    return inserted;
}

// ============================================
// 선택적 대량 데이터 (MySQL 선택 프로시저 대응)
// ============================================

// seed_delivery_data() 대응 — 10M건 미전달 알림
function seedDeliveryData() {
    print("\n========================================");
    print("[대량] Delivery 테스트 데이터 (10,000,000건)");
    print("예상 소요 시간: ~30분");
    print("========================================");

    const startTime = Date.now();
    const TOTAL = 10000000;
    const USER_MAX = 1000;
    let batch = [];
    let inserted = 0;

    for (let i = 0; i < TOTAL; i++) {
        const userId = (i % USER_MAX) + 1;
        batch.push({
            userId: NumberLong(userId),
            content: `대량 알림 #${i + 1}`,
            type: TYPES[i % 5],
            isRead: false,
            delivered: false,
            createdAt: randomPastDate(86400),  // 최근 24시간
            _class: "com.example.onlyone.domain.notification.entity.NotificationDocument"
        });

        if (batch.length >= BATCH_SIZE) {
            coll.insertMany(batch, { ordered: false });
            inserted += batch.length;
            printProgress("delivery", inserted, TOTAL);
            batch = [];
        }
    }

    if (batch.length > 0) {
        coll.insertMany(batch, { ordered: false });
        inserted += batch.length;
    }

    const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
    print(`  완료: ${inserted}건 삽입 (${elapsed}s)`);
}

// seed_batch_data() 대응 — 10M건 배치 처리 테스트
function seedBatchData() {
    print("\n========================================");
    print("[대량] Batch 테스트 데이터 (10,000,000건)");
    print("예상 소요 시간: ~30분");
    print("========================================");

    const startTime = Date.now();
    const TOTAL = 10000000;
    const USER_MAX = 1000;
    let batch = [];
    let inserted = 0;
    const baseTime = Date.now();

    for (let i = 0; i < TOTAL; i++) {
        const userId = (i % USER_MAX) + 1;
        batch.push({
            userId: NumberLong(userId),
            content: `배치 알림 #${i + 1}`,
            type: TYPES[i % 5],
            isRead: false,
            delivered: true,
            createdAt: new Date(baseTime - (i * 30000)),  // 30초 간격
            _class: "com.example.onlyone.domain.notification.entity.NotificationDocument"
        });

        if (batch.length >= BATCH_SIZE) {
            coll.insertMany(batch, { ordered: false });
            inserted += batch.length;
            printProgress("batch", inserted, TOTAL);
            batch = [];
        }
    }

    if (batch.length > 0) {
        coll.insertMany(batch, { ordered: false });
        inserted += batch.length;
    }

    const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
    print(`  완료: ${inserted}건 삽입 (${elapsed}s)`);
}

// seed_conflict_data() 대응 — 단일 유저 10M건 (동시성 테스트)
function seedConflictData() {
    print("\n========================================");
    print("[대량] Conflict 테스트 데이터 (10,000,000건, user 1)");
    print("예상 소요 시간: ~30분");
    print("========================================");

    const startTime = Date.now();
    const TOTAL = 10000000;
    let batch = [];
    let inserted = 0;

    for (let i = 0; i < TOTAL; i++) {
        batch.push({
            userId: NumberLong(1),  // 단일 유저
            content: `충돌 알림 #${i + 1}`,
            type: TYPES[i % 5],
            isRead: false,
            delivered: true,
            createdAt: randomPastDate(86400),
            _class: "com.example.onlyone.domain.notification.entity.NotificationDocument"
        });

        if (batch.length >= BATCH_SIZE) {
            coll.insertMany(batch, { ordered: false });
            inserted += batch.length;
            printProgress("conflict", inserted, TOTAL);
            batch = [];
        }
    }

    if (batch.length > 0) {
        coll.insertMany(batch, { ordered: false });
        inserted += batch.length;
    }

    const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
    print(`  완료: ${inserted}건 삽입 (${elapsed}s)`);
}

// ============================================
// 인덱스 생성 (NotificationDocument @CompoundIndex 대응)
// ============================================

function ensureIndexes() {
    print("\n========================================");
    print("인덱스 생성/확인");
    print("========================================");

    coll.createIndex({ userId: 1, _id: -1 }, { name: "idx_user_id_desc" });
    coll.createIndex({ userId: 1, isRead: 1, _id: 1 }, { name: "idx_user_read" });
    coll.createIndex({ userId: 1, delivered: 1, _id: 1 }, { name: "idx_user_delivered" });

    print("  idx_user_id_desc:    {userId: 1, _id: -1}");
    print("  idx_user_read:       {userId: 1, isRead: 1, _id: 1}");
    print("  idx_user_delivered:  {userId: 1, delivered: 1, _id: 1}");
    print("  완료");
}

// ============================================
// 데이터 검증
// ============================================

function verifyData() {
    print("\n========================================");
    print("데이터 검증");
    print("========================================");

    const total = coll.countDocuments({});
    const unread = coll.countDocuments({ isRead: false });
    const read = coll.countDocuments({ isRead: true });
    const delivered = coll.countDocuments({ delivered: true });
    const undelivered = coll.countDocuments({ delivered: false });

    print(`  전체:     ${total}건`);
    print(`  미읽음:   ${unread}건`);
    print(`  읽음:     ${read}건`);
    print(`  전달됨:   ${delivered}건`);
    print(`  미전달:   ${undelivered}건`);

    // 타입별 분포
    print("\n  타입별 분포:");
    TYPES.forEach(type => {
        const count = coll.countDocuments({ type: type });
        print(`    ${type}: ${count}건 (${((count / total) * 100).toFixed(1)}%)`);
    });

    // 유저별 샘플 (user 1, 50, 100)
    print("\n  유저별 샘플:");
    [1, 50, 100].forEach(uid => {
        const userTotal = coll.countDocuments({ userId: uid });
        const userUnread = coll.countDocuments({ userId: uid, isRead: false });
        print(`    user ${uid}: total=${userTotal}, unread=${userUnread}`);
    });

    // 인덱스 확인
    print("\n  인덱스:");
    coll.getIndexes().forEach(idx => {
        print(`    ${idx.name}: ${JSON.stringify(idx.key)}`);
    });
}

// ============================================
// 메인 실행
// ============================================

print("==============================================");
print("MongoDB 알림 시드 데이터 생성");
print(`Database: ${DB_NAME}`);
print(`Collection: ${COLLECTION}`);
print(`시작 시각: ${new Date().toISOString()}`);
print("==============================================");

// 기본 시드 (항상 실행)
ensureIndexes();
seedBaseNotifications();
seedReadDistribution();
seedUndeliveredNotifications();
verifyData();

print("\n==============================================");
print("기본 시드 완료!");
print(`종료 시각: ${new Date().toISOString()}`);
print("==============================================");
print("\n선택적 대량 데이터는 mongosh에서 직접 호출:");
print("  seedDeliveryData()   — 10M건 미전달 알림");
print("  seedBatchData()      — 10M건 배치 테스트");
print("  seedConflictData()   — 10M건 단일 유저 동시성");
