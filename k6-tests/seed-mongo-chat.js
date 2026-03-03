// =============================================================
// MongoDB 채팅 메시지 시드 데이터 생성 — 100x 스케일
// =============================================================
// 실행: docker exec onlyone-mongodb mongosh -u root -p root --authenticationDatabase admin onlyone /scripts/seed-mongo-chat.js
//
// 규모: 25,000 채팅방 × 방당 1,000개 메시지 = 25,000,000 메시지
// =============================================================

const BATCH_SIZE = 10000;
const TOTAL_ROOMS = 25000;
const MESSAGES_PER_ROOM = 1000;
const TOTAL = TOTAL_ROOMS * MESSAGES_PER_ROOM;
const TOTAL_USERS = 100000;

db.messages.drop();

print("=== 채팅 MongoDB 시드 데이터 생성 시작 (100x) ===");
print(`목표: ${TOTAL.toLocaleString()} 메시지 (${TOTAL_ROOMS.toLocaleString()} 방 × ${MESSAGES_PER_ROOM.toLocaleString()})`);

const startTime = Date.now();
let totalInserted = 0;
let batch = [];
let numericId = 1;

const chatTemplates = [
    "안녕하세요! 오늘 모임 기대됩니다.",
    "혹시 장소 확인 되셨나요?",
    "네, 저도 참석합니다!",
    "오늘 정말 즐거웠어요 ㅎㅎ",
    "다음 모임은 언제인가요?",
    "사진 올려주세요~",
    "좋은 시간이었습니다 감사합니다",
    "저는 조금 늦을 것 같아요",
    "맛집 추천해주세요!",
    "다들 수고하셨습니다~"
];

function flushBatch() {
    if (batch.length === 0) return;
    db.messages.insertMany(batch, { ordered: false });
    totalInserted += batch.length;
    batch = [];

    if (totalInserted % 500000 === 0) {
        const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
        const pct = ((totalInserted / TOTAL) * 100).toFixed(1);
        print(`  [${elapsed}s] ${totalInserted.toLocaleString()} / ${TOTAL.toLocaleString()} (${pct}%)`);
    }
}

for (let roomId = 1; roomId <= TOTAL_ROOMS; roomId++) {
    // 방당 5명 고정 참여자
    const participants = [];
    for (let p = 0; p < 5; p++) {
        participants.push(((roomId * 5 + p) % TOTAL_USERS) + 1);
    }

    for (let m = 0; m < MESSAGES_PER_ROOM; m++) {
        const senderId = participants[m % participants.length];
        const baseDate = new Date('2026-01-01T00:00:00Z');
        const sentAt = new Date(baseDate.getTime() + numericId * 100);

        batch.push({
            numericId: numericId,
            chatRoomId: roomId,
            senderId: senderId,
            senderNickname: `테스트유저${senderId}`,
            senderProfileImage: null,
            text: `${chatTemplates[m % chatTemplates.length]} #${numericId}`,
            sentAt: sentAt,
            deleted: false,
            createdAt: sentAt
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
db.messages.createIndex({ numericId: 1 }, { unique: true, name: "idx_numericId" });
db.messages.createIndex({ chatRoomId: 1, sentAt: -1, numericId: -1 }, { name: "idx_room_sentat_numid_desc" });
db.messages.createIndex({ chatRoomId: 1, deleted: 1, numericId: -1 }, { name: "idx_room_deleted_numid_desc" });
print('인덱스 3개 생성 완료');

// 카운터 초기화
db.counters.updateOne(
    { _id: "message_seq" },
    { $set: { seq: numericId } },
    { upsert: true }
);
print(`카운터 초기화: message_seq = ${numericId}`);

// 검증
print('\n--- 확인 ---');
print(`총 메시지: ${db.messages.countDocuments()}`);
print(`방 1 메시지 수: ${db.messages.countDocuments({ chatRoomId: 1 })}`);
print(`방 100 메시지 수: ${db.messages.countDocuments({ chatRoomId: 100 })}`);
print(`삭제된 메시지: ${db.messages.countDocuments({ deleted: true })}`);

const totalElapsed = ((Date.now() - startTime) / 1000).toFixed(1);
print(`\n=== 전체 완료: ${totalElapsed}s ===`);
