// MongoDB seed resume — userId 634(나머지)~1000 삽입
// OOM 방지: 유저 10명 단위로 flush, batch 2000
const db = db.getSiblingDB("onlyone");
const coll = db.getCollection("notifications");
const TYPES = ["CHAT", "SETTLEMENT", "LIKE", "COMMENT", "REFEED"];
const CLASS_NAME = "com.example.onlyone.domain.notification.entity.NotificationDocument";
const PER_USER = 11200;
const UNREAD_RATIO = 0.43;
const UNDELIVERED_RATIO = 0.30;
const BATCH_SIZE = 2000;

// 현재 시퀀스
var seq = 10123000;
var startTime = Date.now();

// user 634: 이미 400건 있으므로 10800건 추가
print("Resuming from user 634 (10800 remaining) to user 1000");

function insertForUser(userId, count) {
    var batch = [];
    for (var i = 0; i < count; i++) {
        seq++;
        batch.push({
            numericId: seq,
            userId: userId,
            content: "고부하 알림 #" + (i + 1) + " - " + userId,
            type: TYPES[Math.floor(Math.random() * TYPES.length)],
            isRead: Math.random() >= UNREAD_RATIO,
            delivered: Math.random() >= UNDELIVERED_RATIO,
            createdAt: new Date(Date.now() - Math.floor(Math.random() * 86400 * 1000)),
            _class: CLASS_NAME
        });
        if (batch.length >= BATCH_SIZE) {
            coll.insertMany(batch, { ordered: false });
            batch = [];
        }
    }
    if (batch.length > 0) {
        coll.insertMany(batch, { ordered: false });
    }
}

// user 634 나머지
insertForUser(634, PER_USER - 400);
print("user 634 done: " + coll.countDocuments({userId: 634}));

// user 635~1000
for (var uid = 635; uid <= 1000; uid++) {
    insertForUser(uid, PER_USER);
    if (uid % 10 === 0) {
        var elapsed = ((Date.now() - startTime) / 1000).toFixed(0);
        print("user " + uid + " done (" + elapsed + "s)");
    }
}

// counters 업데이트
db.getCollection("counters").updateOne(
    { _id: "notification_seq" },
    { $set: { seq: seq } },
    { upsert: true }
);

print("\nResume 완료. seq=" + seq);
print("Total: " + coll.countDocuments({}));
print("소요: " + ((Date.now() - startTime) / 1000).toFixed(1) + "s");
