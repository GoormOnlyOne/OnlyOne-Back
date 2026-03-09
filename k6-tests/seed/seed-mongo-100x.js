// =============================================================
// MongoDB 시드 데이터 (알림 + 채팅) — 100x 스케일 (MySQL 기존 데이터 매칭)
// =============================================================
// 실행: mongosh 'mongodb://root:root@localhost:27017/onlyone?authSource=admin' seed-mongo-100x.js
//
// 규모:
//   notifications:            20,000,000 (유저당 200건, 100K users)
//   messages:                 12,500,000 (채팅방당 250건, 50K rooms)
//   counters:                 2 (notification_seq, message_seq)
//   user_notification_state:  50,000 (워터마크, 50% 유저)
//   총 MongoDB 도큐먼트:      ~32,550,000
//
// 예상 소요시간: 15~30분
// 예상 디스크: ~13GB
// =============================================================

const TOTAL_USERS         = 100000;
const TOTAL_CHATROOMS     = 50000;
const NOTIF_PER_USER      = 200;
const MSG_PER_ROOM        = 250;
const TOTAL_NOTIFICATIONS = TOTAL_USERS * NOTIF_PER_USER;       // 20,000,000
const TOTAL_MESSAGES      = TOTAL_CHATROOMS * MSG_PER_ROOM;      // 12,500,000
const BATCH_SIZE          = 10000;

const NOTIF_TYPES = ["CHAT", "SETTLEMENT", "LIKE", "COMMENT", "REFEED"];
const BASE_DATE = new Date("2026-01-01T00:00:00Z");

print("========================================");
print("=== MongoDB 시드 데이터 생성 시작 (100x) ===");
print("========================================");
print(`  notifications: ${TOTAL_NOTIFICATIONS.toLocaleString()}`);
print(`  messages:      ${TOTAL_MESSAGES.toLocaleString()}`);
print("");

// ═══════════════════════════════════════════
// 0) 기존 데이터 정리
// ═══════════════════════════════════════════
print("--- [0/5] 기존 컬렉션 삭제 ---");
db.notifications.drop();
db.messages.drop();
db.counters.drop();
db.user_notification_state.drop();
print("  완료");

// ═══════════════════════════════════════════
// 1) notifications — 20M 건 (인덱스 나중에 생성하여 insert 속도 최적화)
// ═══════════════════════════════════════════
print("--- [1/5] notifications 생성 (20,000,000건) ---");
{
    let batch = [];
    let inserted = 0;
    const startTime = Date.now();

    for (let numericId = 1; numericId <= TOTAL_NOTIFICATIONS; numericId++) {
        const userId = Math.floor((numericId - 1) / NOTIF_PER_USER) + 1;
        const offsetInUser = (numericId - 1) % NOTIF_PER_USER;
        const type = NOTIF_TYPES[numericId % 5];
        const createdAt = new Date(BASE_DATE.getTime() + offsetInUser * 60000);
        const isRead = offsetInUser < (NOTIF_PER_USER - 60);
        const delivered = offsetInUser < (NOTIF_PER_USER - 40);

        batch.push({
            numericId: NumberLong(numericId),
            userId: NumberLong(userId),
            content: `testuser${(numericId % TOTAL_USERS) + 1}님이 알림을 보냈습니다`,
            type: type,
            isRead: isRead,
            delivered: delivered,
            createdAt: createdAt,
            _class: "com.example.onlyone.domain.notification.entity.NotificationDocument"
        });

        if (batch.length >= BATCH_SIZE) {
            db.notifications.insertMany(batch, { ordered: false });
            inserted += batch.length;
            batch = [];
            if (inserted % 1000000 === 0) {
                const elapsed = ((Date.now() - startTime) / 1000).toFixed(0);
                const pct = ((inserted / TOTAL_NOTIFICATIONS) * 100).toFixed(1);
                print(`  ${inserted.toLocaleString()} / ${TOTAL_NOTIFICATIONS.toLocaleString()} (${pct}%) — ${elapsed}s`);
            }
        }
    }
    if (batch.length > 0) {
        db.notifications.insertMany(batch, { ordered: false });
        inserted += batch.length;
    }
    const totalElapsed = ((Date.now() - startTime) / 1000).toFixed(0);
    print(`  notifications 완료: ${inserted.toLocaleString()}건, ${totalElapsed}s`);
}

// ═══════════════════════════════════════════
// 2) messages — 12.5M 건
// ═══════════════════════════════════════════
print("--- [2/5] messages 생성 (12,500,000건) ---");
{
    let batch = [];
    let inserted = 0;
    const startTime = Date.now();

    for (let numericId = 1; numericId <= TOTAL_MESSAGES; numericId++) {
        const roomIdx = Math.floor((numericId - 1) / MSG_PER_ROOM);
        const chatRoomId = roomIdx + 1;
        const offsetInRoom = (numericId - 1) % MSG_PER_ROOM;
        const participantIdx = offsetInRoom % 5;
        const senderId = ((roomIdx * 5 + participantIdx) % TOTAL_USERS) + 1;
        const sentAt = new Date(BASE_DATE.getTime() + offsetInRoom * 30000);

        batch.push({
            numericId: NumberLong(numericId),
            chatRoomId: NumberLong(chatRoomId),
            senderId: NumberLong(senderId),
            senderNickname: `testuser${senderId}`,
            senderProfileImage: `https://example.com/profile/${senderId}.jpg`,
            text: `채팅 메시지 #${numericId} from user${senderId}`,
            sentAt: sentAt,
            deleted: false,
            createdAt: sentAt,
            _class: "com.example.onlyone.domain.chat.entity.MessageDocument"
        });

        if (batch.length >= BATCH_SIZE) {
            db.messages.insertMany(batch, { ordered: false });
            inserted += batch.length;
            batch = [];
            if (inserted % 1000000 === 0) {
                const elapsed = ((Date.now() - startTime) / 1000).toFixed(0);
                const pct = ((inserted / TOTAL_MESSAGES) * 100).toFixed(1);
                print(`  ${inserted.toLocaleString()} / ${TOTAL_MESSAGES.toLocaleString()} (${pct}%) — ${elapsed}s`);
            }
        }
    }
    if (batch.length > 0) {
        db.messages.insertMany(batch, { ordered: false });
        inserted += batch.length;
    }
    const totalElapsed = ((Date.now() - startTime) / 1000).toFixed(0);
    print(`  messages 완료: ${inserted.toLocaleString()}건, ${totalElapsed}s`);
}

// ═══════════════════════════════════════════
// 3) 인덱스 생성 (데이터 삽입 후 — bulk insert 최적화)
// ═══════════════════════════════════════════
print("--- [3/5] 인덱스 생성 ---");
{
    const startTime = Date.now();

    db.notifications.createIndex({ numericId: 1 }, { unique: true, name: "idx_numericId" });
    db.notifications.createIndex({ userId: 1, numericId: -1 }, { name: "idx_user_numid_desc" });
    db.notifications.createIndex({ userId: 1, isRead: 1, numericId: 1 }, { name: "idx_user_read" });
    db.notifications.createIndex({ userId: 1, delivered: 1, numericId: 1 }, { name: "idx_user_delivered" });
    print("  notifications 인덱스 4개");

    db.messages.createIndex({ numericId: 1 }, { unique: true, name: "idx_numericId" });
    db.messages.createIndex({ chatRoomId: 1, sentAt: -1, numericId: -1 }, { name: "idx_room_sentat_numid_desc" });
    db.messages.createIndex({ chatRoomId: 1, deleted: 1, numericId: -1 }, { name: "idx_room_deleted_numid_desc" });
    print("  messages 인덱스 3개");

    const totalElapsed = ((Date.now() - startTime) / 1000).toFixed(0);
    print(`  인덱스 생성 완료 (${totalElapsed}s)`);
}

// ═══════════════════════════════════════════
// 4) counters — 시퀀스 초기화
// ═══════════════════════════════════════════
print("--- [4/5] counters 시퀀스 초기화 ---");
db.counters.insertMany([
    { _id: "notification_seq", seq: NumberLong(TOTAL_NOTIFICATIONS) },
    { _id: "message_seq", seq: NumberLong(TOTAL_MESSAGES) }
]);
print(`  notification_seq = ${TOTAL_NOTIFICATIONS.toLocaleString()}`);
print(`  message_seq = ${TOTAL_MESSAGES.toLocaleString()}`);

// ═══════════════════════════════════════════
// 5) user_notification_state — 워터마크 (50% 유저)
// ═══════════════════════════════════════════
print("--- [5/5] user_notification_state 워터마크 (50,000건) ---");
{
    let batch = [];
    let inserted = 0;
    const startTime = Date.now();

    for (let userId = 2; userId <= TOTAL_USERS; userId += 2) {
        const firstNotifId = (userId - 1) * NOTIF_PER_USER + 1;
        const watermark = firstNotifId + 139;

        batch.push({
            _id: NumberLong(userId),
            readAllUptoId: NumberLong(watermark),
            updatedAt: new Date()
        });

        if (batch.length >= BATCH_SIZE) {
            db.user_notification_state.insertMany(batch, { ordered: false });
            inserted += batch.length;
            batch = [];
        }
    }
    if (batch.length > 0) {
        db.user_notification_state.insertMany(batch, { ordered: false });
        inserted += batch.length;
    }
    const totalElapsed = ((Date.now() - startTime) / 1000).toFixed(0);
    print(`  워터마크 완료: ${inserted.toLocaleString()}건, ${totalElapsed}s`);
}

// ═══════════════════════════════════════════
// 검증
// ═══════════════════════════════════════════
print("");
print("========================================");
print("=== 검증 ===");
print("========================================");
const notiCount = db.notifications.countDocuments();
const msgCount = db.messages.countDocuments();
const wmCount = db.user_notification_state.countDocuments();
print(`  notifications:           ${notiCount.toLocaleString()} (expected: ${TOTAL_NOTIFICATIONS.toLocaleString()})`);
print(`  messages:                ${msgCount.toLocaleString()} (expected: ${TOTAL_MESSAGES.toLocaleString()})`);
print(`  counters:                ${db.counters.countDocuments()}`);
print(`  user_notification_state: ${wmCount.toLocaleString()}`);

if (notiCount !== TOTAL_NOTIFICATIONS) {
    print("  ERROR: notifications 건수 불일치!");
}
if (msgCount !== TOTAL_MESSAGES) {
    print("  ERROR: messages 건수 불일치!");
}

print("");
print("--- 샘플: notification (user 1, 최신 3건) ---");
printjson(db.notifications.find({ userId: NumberLong(1) }).sort({ numericId: -1 }).limit(3).toArray());

print("--- 샘플: message (room 1, 최신 3건) ---");
printjson(db.messages.find({ chatRoomId: NumberLong(1) }).sort({ numericId: -1 }).limit(3).toArray());

print("--- 샘플: counters ---");
printjson(db.counters.find().toArray());

print("");
print("========================================");
print("=== MongoDB 시드 완료 (100x) ===");
print("========================================");
