// MongoDB 미니 시드 검증 (1x 스케일)
// 실행: docker exec onlyone-mongodb mongosh -u root -p root --authenticationDatabase admin onlyone /scripts/verify-mongo-mini.js

const BATCH_SIZE = 5000;
const TOTAL_USERS = 1000;
const TOTAL_CLUBS = 500;

// ── 피드 ──
print("=== 피드 시드 (미니) ===");
db.feed.drop();

let batch = [];
let totalInserted = 0;
const ORIGINAL_PER_CLUB = 10;
const REFEED_TOTAL = 500;
const TOTAL_ORIGINAL = TOTAL_CLUBS * ORIGINAL_PER_CLUB;

// ORIGINAL: feedId = clubId + N * 500 (N=0~9)
for (let n = 0; n < ORIGINAL_PER_CLUB; n++) {
    for (let clubId = 1; clubId <= TOTAL_CLUBS; clubId++) {
        const feedId = clubId + n * TOTAL_CLUBS;
        const userId = ((feedId - 1) % TOTAL_USERS) + 1;
        batch.push({
            feedId: feedId,
            content: `테스트 피드 #${feedId}`,
            feedType: "ORIGINAL",
            clubId: clubId,
            clubName: `테스트모임${clubId}`,
            userId: userId,
            nickname: `테스트유저${userId}`,
            profileImage: null,
            parentFeedId: null,
            rootFeedId: null,
            likeCount: Math.floor(Math.random() * 30),
            commentCount: Math.floor(Math.random() * 15),
            imageUrls: [`https://d1c3fg3ti7m8cn.cloudfront.net/feed/${feedId}/img1.jpg`],
            likerUserIds: [],
            recentComments: [],
            deleted: false,
            createdAt: new Date(),
            modifiedAt: new Date(),
            deletedAt: null
        });
        if (batch.length >= BATCH_SIZE) {
            db.feed.insertMany(batch, { ordered: false });
            totalInserted += batch.length;
            batch = [];
        }
    }
}

// REFEED: feedId = 60000000 + seq
const refeedBaseId = 60000000;
for (let i = 0; i < REFEED_TOTAL; i++) {
    const feedId = refeedBaseId + i;
    const clubId = (i % TOTAL_CLUBS) + 1;
    const parentN = Math.floor(Math.random() * Math.min(ORIGINAL_PER_CLUB, 5));
    const parentFeedId = clubId + parentN * TOTAL_CLUBS;
    batch.push({
        feedId: feedId,
        content: `리피드 #${feedId}`,
        feedType: "REFEED",
        clubId: clubId,
        clubName: `테스트모임${clubId}`,
        userId: Math.floor(Math.random() * TOTAL_USERS) + 1,
        nickname: `테스트유저${Math.floor(Math.random() * TOTAL_USERS) + 1}`,
        profileImage: null,
        parentFeedId: parentFeedId,
        rootFeedId: parentFeedId,
        likeCount: 0,
        commentCount: 0,
        imageUrls: [],
        likerUserIds: [],
        recentComments: [],
        deleted: false,
        createdAt: new Date(),
        modifiedAt: new Date(),
        deletedAt: null
    });
    if (batch.length >= BATCH_SIZE) {
        db.feed.insertMany(batch, { ordered: false });
        totalInserted += batch.length;
        batch = [];
    }
}
if (batch.length > 0) { db.feed.insertMany(batch, { ordered: false }); totalInserted += batch.length; batch = []; }

db.feed.createIndex({ feedId: 1 }, { unique: true });
db.feed.createIndex({ clubId: 1, deleted: 1, createdAt: -1 });
db.feed.createIndex({ parentFeedId: 1, deleted: 1 });
db.feed.createIndex({ "likerUserIds": 1, feedId: 1 });
db.feed.createIndex({ deleted: 1, createdAt: -1, clubId: 1 });

print(`  피드 총: ${db.feed.countDocuments()}`);
print(`  ORIGINAL: ${db.feed.countDocuments({ feedType: "ORIGINAL" })}`);
print(`  REFEED: ${db.feed.countDocuments({ feedType: "REFEED" })}`);
print(`  feedId=1 존재: ${db.feed.countDocuments({ feedId: 1 }) > 0 ? 'OK' : 'FAIL'}`);
print(`  feedId=500 존재: ${db.feed.countDocuments({ feedId: 500 }) > 0 ? 'OK' : 'FAIL'}`);
print(`  clubId=1 피드 수: ${db.feed.countDocuments({ clubId: 1 })}`);

// ── 알림 ──
print("\n=== 알림 시드 (미니) ===");
db.notifications.drop();
db.counters.drop();

let notifBatch = [];
let notifInserted = 0;
let numericId = 1;
const NOTIF_PER_USER = 5;
const TYPES = ['FEED', 'CHAT', 'SCHEDULE', 'CLUB', 'SETTLEMENT'];

for (let userId = 1; userId <= TOTAL_USERS; userId++) {
    for (let n = 0; n < NOTIF_PER_USER; n++) {
        notifBatch.push({
            numericId: numericId,
            userId: userId,
            content: `알림 #${numericId}`,
            type: TYPES[n % TYPES.length],
            isRead: Math.random() < 0.3,
            delivered: Math.random() < 0.7,
            createdAt: new Date()
        });
        numericId++;
        if (notifBatch.length >= BATCH_SIZE) {
            db.notifications.insertMany(notifBatch, { ordered: false });
            notifInserted += notifBatch.length;
            notifBatch = [];
        }
    }
}
if (notifBatch.length > 0) { db.notifications.insertMany(notifBatch, { ordered: false }); notifInserted += notifBatch.length; notifBatch = []; }

db.notifications.createIndex({ numericId: 1 }, { unique: true });
db.notifications.createIndex({ userId: 1, numericId: -1 });
db.notifications.createIndex({ userId: 1, isRead: 1, numericId: 1 });
db.notifications.createIndex({ userId: 1, delivered: 1, numericId: 1 });

db.counters.insertOne({ _id: "notification_seq", seq: numericId });

print(`  알림 총: ${db.notifications.countDocuments()}`);
print(`  유저 1 알림 수: ${db.notifications.countDocuments({ userId: 1 })}`);

// ── 채팅 ──
print("\n=== 채팅 시드 (미니) ===");
db.messages.drop();

let chatBatch = [];
let chatInserted = 0;
let chatNumericId = 1;
const TOTAL_ROOMS = 250;
const MESSAGES_PER_ROOM = 10;

for (let roomId = 1; roomId <= TOTAL_ROOMS; roomId++) {
    const participants = [];
    for (let p = 0; p < 5; p++) {
        participants.push(((roomId * 5 + p) % TOTAL_USERS) + 1);
    }
    for (let m = 0; m < MESSAGES_PER_ROOM; m++) {
        chatBatch.push({
            numericId: chatNumericId,
            chatRoomId: roomId,
            senderId: participants[m % participants.length],
            senderNickname: `테스트유저${participants[m % participants.length]}`,
            senderProfileImage: null,
            text: `채팅 메시지 #${chatNumericId}`,
            sentAt: new Date(),
            deleted: false,
            createdAt: new Date()
        });
        chatNumericId++;
        if (chatBatch.length >= BATCH_SIZE) {
            db.messages.insertMany(chatBatch, { ordered: false });
            chatInserted += chatBatch.length;
            chatBatch = [];
        }
    }
}
if (chatBatch.length > 0) { db.messages.insertMany(chatBatch, { ordered: false }); chatInserted += chatBatch.length; chatBatch = []; }

db.messages.createIndex({ numericId: 1 }, { unique: true });
db.messages.createIndex({ chatRoomId: 1, sentAt: -1, numericId: -1 });
db.messages.createIndex({ chatRoomId: 1, deleted: 1, numericId: -1 });

db.counters.updateOne({ _id: "message_seq" }, { $set: { seq: chatNumericId } }, { upsert: true });

print(`  채팅 메시지 총: ${db.messages.countDocuments()}`);
print(`  방 1 메시지 수: ${db.messages.countDocuments({ chatRoomId: 1 })}`);

// ── 최종 결과 ──
print("\n========================================");
print("=== MongoDB 미니 시드 최종 결과 ===");
print("========================================");
print(`feed:          ${db.feed.countDocuments()}`);
print(`notifications: ${db.notifications.countDocuments()}`);
print(`messages:      ${db.messages.countDocuments()}`);
print(`counters:      ${db.counters.countDocuments()}`);
print("=== 완료 ===");
