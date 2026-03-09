#!/bin/bash
# =============================================================
# MongoDB 시드 데이터 (알림 + 채팅) — 100x 스케일
# OOM 방지: mongosh를 chunk 단위로 반복 호출
# =============================================================
# 실행: bash seed-mongo-100x.sh
# =============================================================
set -eu

MONGO_URI="mongodb://root:root@localhost:27017/onlyone?authSource=admin"
TOTAL_USERS=100000
TOTAL_CHATROOMS=50000
NOTIF_PER_USER=200
MSG_PER_ROOM=250
TOTAL_NOTIFICATIONS=$((TOTAL_USERS * NOTIF_PER_USER))   # 20,000,000
TOTAL_MESSAGES=$((TOTAL_CHATROOMS * MSG_PER_ROOM))        # 12,500,000
CHUNK_SIZE=500000  # mongosh 1회당 처리량 (OOM 방지)

echo "========================================"
echo "=== MongoDB 시드 데이터 생성 (100x) ==="
echo "========================================"
echo "  notifications: $TOTAL_NOTIFICATIONS"
echo "  messages:      $TOTAL_MESSAGES"
echo "  chunk size:    $CHUNK_SIZE"
echo ""

# ── 0) 기존 데이터 정리 ──
echo "--- [0/5] 기존 컬렉션 삭제 ---"
docker exec onlyone-mongodb mongosh "$MONGO_URI" --quiet --eval '
db.notifications.drop();
db.messages.drop();
db.counters.drop();
db.user_notification_state.drop();
print("  완료");
'

# ── 1) notifications — 20M (chunk 단위) ──
echo "--- [1/5] notifications 생성 (${TOTAL_NOTIFICATIONS}건, chunk=${CHUNK_SIZE}) ---"
START_SEC=$SECONDS
for (( offset=0; offset<TOTAL_NOTIFICATIONS; offset+=CHUNK_SIZE )); do
    end=$((offset + CHUNK_SIZE))
    if [ $end -gt $TOTAL_NOTIFICATIONS ]; then end=$TOTAL_NOTIFICATIONS; fi

    docker exec onlyone-mongodb mongosh "$MONGO_URI" --quiet --eval "
    const BATCH=10000, TYPES=['CHAT','SETTLEMENT','LIKE','COMMENT','REFEED'];
    const BASE=new Date('2026-01-01T00:00:00Z').getTime();
    const TOTAL_USERS=${TOTAL_USERS}, NPU=${NOTIF_PER_USER};
    let batch=[], start=${offset}+1, end=${end};
    for(let id=start;id<=end;id++){
        const uid=Math.floor((id-1)/NPU)+1;
        const off=(id-1)%NPU;
        batch.push({
            numericId:id, userId:uid,
            content:'testuser'+(id%TOTAL_USERS+1)+'님이 알림을 보냈습니다',
            type:TYPES[id%5], isRead:off<(NPU-60), delivered:off<(NPU-40),
            createdAt:new Date(BASE+off*60000),
            _class:'com.example.onlyone.domain.notification.entity.NotificationDocument'
        });
        if(batch.length>=BATCH){db.notifications.insertMany(batch,{ordered:false});batch=[];}
    }
    if(batch.length>0) db.notifications.insertMany(batch,{ordered:false});
    print('  chunk ${offset}-${end} done');
    "

    elapsed=$((SECONDS - START_SEC))
    pct=$(( (end * 100) / TOTAL_NOTIFICATIONS ))
    echo "  ${end}/${TOTAL_NOTIFICATIONS} (${pct}%) — ${elapsed}s"
done
echo "  notifications 완료: $((SECONDS - START_SEC))s"

# ── 2) messages — 12.5M (chunk 단위) ──
echo "--- [2/5] messages 생성 (${TOTAL_MESSAGES}건, chunk=${CHUNK_SIZE}) ---"
START_SEC=$SECONDS
for (( offset=0; offset<TOTAL_MESSAGES; offset+=CHUNK_SIZE )); do
    end=$((offset + CHUNK_SIZE))
    if [ $end -gt $TOTAL_MESSAGES ]; then end=$TOTAL_MESSAGES; fi

    docker exec onlyone-mongodb mongosh "$MONGO_URI" --quiet --eval "
    const BATCH=10000, TOTAL_USERS=${TOTAL_USERS}, MPR=${MSG_PER_ROOM};
    const BASE=new Date('2026-01-01T00:00:00Z').getTime();
    let batch=[], start=${offset}+1, end=${end};
    for(let id=start;id<=end;id++){
        const ri=Math.floor((id-1)/MPR);
        const off=(id-1)%MPR;
        const pi=off%5;
        const sid=((ri*5+pi)%TOTAL_USERS)+1;
        const t=new Date(BASE+off*30000);
        batch.push({
            numericId:id, chatRoomId:ri+1, senderId:sid,
            senderNickname:'testuser'+sid,
            senderProfileImage:'https://example.com/profile/'+sid+'.jpg',
            text:'msg #'+id+' from user'+sid,
            sentAt:t, deleted:false, createdAt:t,
            _class:'com.example.onlyone.domain.chat.entity.MessageDocument'
        });
        if(batch.length>=BATCH){db.messages.insertMany(batch,{ordered:false});batch=[];}
    }
    if(batch.length>0) db.messages.insertMany(batch,{ordered:false});
    print('  chunk ${offset}-${end} done');
    "

    elapsed=$((SECONDS - START_SEC))
    pct=$(( (end * 100) / TOTAL_MESSAGES ))
    echo "  ${end}/${TOTAL_MESSAGES} (${pct}%) — ${elapsed}s"
done
echo "  messages 완료: $((SECONDS - START_SEC))s"

# ── 3) 인덱스 생성 ──
echo "--- [3/5] 인덱스 생성 ---"
docker exec onlyone-mongodb mongosh "$MONGO_URI" --quiet --eval '
db.notifications.createIndex({numericId:1},{unique:true,name:"idx_numericId"});
db.notifications.createIndex({userId:1,numericId:-1},{name:"idx_user_numid_desc"});
db.notifications.createIndex({userId:1,isRead:1,numericId:1},{name:"idx_user_read"});
db.notifications.createIndex({userId:1,delivered:1,numericId:1},{name:"idx_user_delivered"});
print("  notifications 4 indexes");
db.messages.createIndex({numericId:1},{unique:true,name:"idx_numericId"});
db.messages.createIndex({chatRoomId:1,sentAt:-1,numericId:-1},{name:"idx_room_sentat_numid_desc"});
db.messages.createIndex({chatRoomId:1,deleted:1,numericId:-1},{name:"idx_room_deleted_numid_desc"});
print("  messages 3 indexes");
'

# ── 4) counters ──
echo "--- [4/5] counters 시퀀스 초기화 ---"
docker exec onlyone-mongodb mongosh "$MONGO_URI" --quiet --eval "
db.counters.insertMany([
    {_id:'notification_seq', seq:${TOTAL_NOTIFICATIONS}},
    {_id:'message_seq', seq:${TOTAL_MESSAGES}}
]);
print('  notification_seq=${TOTAL_NOTIFICATIONS}, message_seq=${TOTAL_MESSAGES}');
"

# ── 5) watermarks ──
echo "--- [5/5] user_notification_state 워터마크 (50,000건) ---"
docker exec onlyone-mongodb mongosh "$MONGO_URI" --quiet --eval "
const NPU=${NOTIF_PER_USER};
let batch=[], cnt=0;
for(let uid=2;uid<=${TOTAL_USERS};uid+=2){
    const fid=(uid-1)*NPU+1;
    batch.push({_id:uid, readAllUptoId:fid+139, updatedAt:new Date()});
    if(batch.length>=10000){db.user_notification_state.insertMany(batch,{ordered:false});cnt+=batch.length;batch=[];}
}
if(batch.length>0){db.user_notification_state.insertMany(batch,{ordered:false});cnt+=batch.length;}
print('  watermarks: '+cnt);
"

# ── 검증 ──
echo ""
echo "========================================"
echo "=== 검증 ==="
echo "========================================"
docker exec onlyone-mongodb mongosh "$MONGO_URI" --quiet --eval "
const nc=db.notifications.countDocuments();
const mc=db.messages.countDocuments();
const wc=db.user_notification_state.countDocuments();
print('  notifications: '+nc+' (expected: ${TOTAL_NOTIFICATIONS})');
print('  messages:      '+mc+' (expected: ${TOTAL_MESSAGES})');
print('  counters:      '+db.counters.countDocuments());
print('  watermarks:    '+wc);
if(nc!==${TOTAL_NOTIFICATIONS}) print('  ERROR: notifications 불일치!');
if(mc!==${TOTAL_MESSAGES}) print('  ERROR: messages 불일치!');
printjson(db.counters.find().toArray());
"

echo ""
echo "========================================"
echo "=== MongoDB 시드 완료 (100x) ==="
echo "========================================"
