// =============================================================
// MongoDB 시드 데이터 (알림 + 채팅) — 500x 스케일
// =============================================================
// 실행: mongosh onlyone < k6-tests/seed/seed-mongo-500x.js
//   또는 mongosh --host <host> onlyone k6-tests/seed/seed-mongo-500x.js
//
// 규모:
//   notifications:            100,000,000 (유저당 200건)
//   messages:                  50,000,000 (채팅방당 250건)
//   counters:                  2 (notification_seq, message_seq)
//   user_notification_state:   500,000 (워터마크, 50% 유저)
//   총 MongoDB 도큐먼트:       ~150,500,000
//
// 예상 소요시간: 1~2시간 (EC2 r6g.xlarge 기준)
// 예상 디스크: ~60GB
// =============================================================

const TOTAL_USERS      = 500000;
const TOTAL_CLUBS      = 200000;
const TOTAL_CHATROOMS  = 200000;
const TOTAL_NOTIFICATIONS = 100000000;  // 100M
const TOTAL_MESSAGES     = 50000000;    // 50M
const NOTIF_PER_USER   = TOTAL_NOTIFICATIONS / TOTAL_USERS;  // 200
const MSG_PER_ROOM     = TOTAL_MESSAGES / TOTAL_CHATROOMS;    // 250
const BATCH_SIZE       = 10000;

const NOTIF_TYPES = ["CHAT", "SETTLEMENT", "LIKE", "COMMENT", "REFEED"];
const BASE_DATE = new Date("2026-01-01T00:00:00Z");

print("========================================");
print("=== MongoDB 시드 데이터 생성 시작 (500x) ===");
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
// 1) 인덱스 사전 생성 (빈 컬렉션에 먼저 생성하면 bulk insert 시 효율적)
// ═══════════════════════════════════════════
print("--- [1/5] 인덱스 생성 ---");

// notifications 인덱스
db.notifications.createIndex({ numericId: 1 }, { unique: true, name: "idx_numericId" });
db.notifications.createIndex({ userId: 1, numericId: -1 }, { name: "idx_user_numid_desc" });
db.notifications.createIndex({ userId: 1, isRead: 1, numericId: 1 }, { name: "idx_user_read" });
db.notifications.createIndex({ userId: 1, delivered: 1, numericId: 1 }, { name: "idx_user_delivered" });
print("  notifications 인덱스 4개 생성");

// messages 인덱스
db.messages.createIndex({ numericId: 1 }, { unique: true, name: "idx_numericId" });
db.messages.createIndex({ chatRoomId: 1, sentAt: -1, numericId: -1 }, { name: "idx_room_sentat_numid_desc" });
db.messages.createIndex({ chatRoomId: 1, deleted: 1, numericId: -1 }, { name: "idx_room_deleted_numid_desc" });
print("  messages 인덱스 3개 생성");

// user_notification_state 인덱스
db.user_notification_state.createIndex({ _id: 1 });  // _id 기본 인덱스
print("  user_notification_state 기본 인덱스");

print("  인덱스 생성 완료");

// ═══════════════════════════════════════════
// 2) notifications — 100M 건
// ═══════════════════════════════════════════
print("--- [2/5] notifications 생성 (100,000,000건) ---");
{
    let batch = [];
    let inserted = 0;
    const startTime = Date.now();

    for (let numericId = 1; numericId <= TOTAL_NOTIFICATIONS; numericId++) {
        // userId: 1~500000 순환 (numericId 1~200 → user 1, 201~400 → user 2, ...)
        const userId = Math.floor((numericId - 1) / NOTIF_PER_USER) + 1;
        const offsetInUser = (numericId - 1) % NOTIF_PER_USER;
        const type = NOTIF_TYPES[numericId % 5];

        // 시간: 유저 내 순서대로 1분 간격
        const createdAt = new Date(BASE_DATE.getTime() + offsetInUser * 60000);

        // 70% 읽음, 30% 미읽음 (최근 60건은 미읽음)
        const isRead = offsetInUser < (NOTIF_PER_USER - 60);
        // 80% delivered
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
// 3) messages — 50M 건
// ═══════════════════════════════════════════
print("--- [3/5] messages 생성 (50,000,000건) ---");
{
    let batch = [];
    let inserted = 0;
    const startTime = Date.now();

    for (let numericId = 1; numericId <= TOTAL_MESSAGES; numericId++) {
        // chatRoomId: 1~200000 순환 (250 messages per room)
        const roomIdx = Math.floor((numericId - 1) / MSG_PER_ROOM);  // 0-based room
        const chatRoomId = roomIdx + 1;
        const offsetInRoom = (numericId - 1) % MSG_PER_ROOM;

        // sender: room의 5명 참가자 중 순환
        // room n의 participant p: userId = ((n * 5 + p) % 500000) + 1
        const participantIdx = offsetInRoom % 5;
        const senderId = ((roomIdx * 5 + participantIdx) % TOTAL_USERS) + 1;

        // 시간: 방 내 순서대로 30초 간격
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
print("--- [5/5] user_notification_state 워터마크 (250,000건) ---");
{
    let batch = [];
    let inserted = 0;
    const startTime = Date.now();

    // 짝수 userId에게 워터마크 설정 (50%)
    // 워터마크 = 해당 유저의 140번째 알림 (200건 중 140번째까지 읽음 처리)
    for (let userId = 2; userId <= TOTAL_USERS; userId += 2) {
        const firstNotifId = (userId - 1) * NOTIF_PER_USER + 1;
        const watermark = firstNotifId + 139;  // 140번째 알림

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
print(`  notifications:           ${db.notifications.countDocuments().toLocaleString()}`);
print(`  messages:                ${db.messages.countDocuments().toLocaleString()}`);
print(`  counters:                ${db.counters.countDocuments()}`);
print(`  user_notification_state: ${db.user_notification_state.countDocuments().toLocaleString()}`);

// 샘플 확인
print("");
print("--- 샘플: notification (user 1, 최신 3건) ---");
printjson(db.notifications.find({ userId: NumberLong(1) }).sort({ numericId: -1 }).limit(3).toArray());

print("--- 샘플: message (room 1, 최신 3건) ---");
printjson(db.messages.find({ chatRoomId: NumberLong(1) }).sort({ numericId: -1 }).limit(3).toArray());

print("--- 샘플: counters ---");
printjson(db.counters.find().toArray());

print("");
print("========================================");
print("=== MongoDB 시드 완료 ===");
print("========================================");

// ═══════════════════════════════════════════
// k6 환경변수 가이드
// ═══════════════════════════════════════════
print("");
print("k6 실행 시 환경변수:");
print("  TOTAL_USERS=500000 TOTAL_CLUBS=200000 TOTAL_CHATROOMS=200000 TOTAL_SCHEDULES=10000000");
print("  USER_COUNT=500000 SETTLEMENT_COUNT=500000");
print("  MIN_CLUB=<DB조회> MIN_CHATROOM=<DB조회> MIN_SCHEDULE=<DB조회>");
