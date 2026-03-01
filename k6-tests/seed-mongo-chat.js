// MongoDB 채팅 메시지 시드 데이터 생성
// 실행: docker exec onlyone-mongodb mongosh -u root -p root --authenticationDatabase admin onlyone /scripts/seed-mongo-chat.js

const BATCH_SIZE = 5000;
const TOTAL_USERS = 1000;

// 대형 방 메시지 수 (MySQL 실제 데이터 기준)
const BIG_ROOMS = {64:29978, 159:16193, 381:16083, 501:15682, 747:15495, 864:14513, 959:14534};
const TOTAL_ROOMS = 1000;
const TOTAL_MESSAGES = 3235398;
const BIG_ROOM_IDS = Object.keys(BIG_ROOMS).map(Number);
const BIG_ROOM_TOTAL = Object.values(BIG_ROOMS).reduce((a,b) => a+b, 0);
const REMAINING = TOTAL_MESSAGES - BIG_ROOM_TOTAL;
const SMALL_ROOM_COUNT = TOTAL_ROOMS - BIG_ROOM_IDS.length;
const AVG_PER_SMALL = Math.floor(REMAINING / SMALL_ROOM_COUNT);

// 컬렉션 초기화
db.messages.drop();
db.counters.drop();

print(`=== 채팅 메시지 시드 데이터 생성 시작 ===`);
print(`총 메시지: ${TOTAL_MESSAGES}, 대형방: ${BIG_ROOM_TOTAL}, 소형방: ${REMAINING}`);

let numericId = 0;
let totalInserted = 0;
const startTime = Date.now();

function generateBatch(chatRoomId, count) {
    const batch = [];
    const baseDate = new Date('2026-01-01T00:00:00Z');

    for (let i = 0; i < count; i++) {
        numericId++;
        const userId = (numericId % TOTAL_USERS) + 1;
        const sentAt = new Date(baseDate.getTime() + numericId * 1000); // 1초 간격

        batch.push({
            numericId: numericId,
            chatRoomId: chatRoomId,
            senderId: userId,
            senderNickname: `테스트유저${userId}`,
            senderProfileImage: null,
            text: `시드 메시지 #${numericId} in room ${chatRoomId}`,
            sentAt: sentAt,
            deleted: false
        });

        if (batch.length >= BATCH_SIZE) {
            db.messages.insertMany(batch);
            totalInserted += batch.length;
            batch.length = 0;

            if (totalInserted % 100000 === 0) {
                const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
                const pct = ((totalInserted / TOTAL_MESSAGES) * 100).toFixed(1);
                print(`  [${elapsed}s] ${totalInserted.toLocaleString()} / ${TOTAL_MESSAGES.toLocaleString()} (${pct}%)`);
            }
        }
    }

    // 남은 배치 삽입
    if (batch.length > 0) {
        db.messages.insertMany(batch);
        totalInserted += batch.length;
    }
}

// 1. 대형 방 데이터 생성
print(`\n--- 대형 방 (${BIG_ROOM_IDS.length}개) ---`);
for (const [roomId, count] of Object.entries(BIG_ROOMS)) {
    print(`  Room ${roomId}: ${count} messages...`);
    generateBatch(Number(roomId), count);
}

// 2. 소형 방 데이터 생성
print(`\n--- 소형 방 (${SMALL_ROOM_COUNT}개, 각 ~${AVG_PER_SMALL}) ---`);
let smallRoomIdx = 0;
for (let roomId = 1; roomId <= TOTAL_ROOMS; roomId++) {
    if (BIG_ROOM_IDS.includes(roomId)) continue;

    // 마지막 방에 나머지 할당
    smallRoomIdx++;
    const count = (smallRoomIdx === SMALL_ROOM_COUNT)
        ? (TOTAL_MESSAGES - totalInserted)
        : AVG_PER_SMALL;

    generateBatch(roomId, count);
}

const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
print(`\n=== 삽입 완료: ${totalInserted.toLocaleString()} messages in ${elapsed}s ===`);

// 3. 인덱스 생성
print('\n--- 인덱스 생성 ---');
db.messages.createIndex({ numericId: 1 }, { unique: true });
db.messages.createIndex({ chatRoomId: 1, sentAt: -1, numericId: -1 });
db.messages.createIndex({ chatRoomId: 1, deleted: 1, numericId: -1 });
print('인덱스 3개 생성 완료');

// 4. counters 컬렉션 설정 (Segment allocation용)
db.counters.insertOne({ _id: 'message_seq', seq: NumberLong(numericId + 1000) });
print(`counters.message_seq = ${numericId + 1000}`);

// 5. 확인
print('\n--- 확인 ---');
print(`messages count: ${db.messages.countDocuments()}`);
print(`sample: ${JSON.stringify(db.messages.findOne())}`);
