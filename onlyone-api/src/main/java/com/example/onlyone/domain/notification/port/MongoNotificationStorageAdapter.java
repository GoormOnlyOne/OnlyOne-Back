package com.example.onlyone.domain.notification.port;

import com.example.onlyone.domain.notification.dto.response.NotificationItemDto;
import com.example.onlyone.domain.notification.entity.NotificationDocument;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * MongoDB 기반 알림 저장소 어댑터.
 * {@code app.notification.storage=mongodb} 일 때 활성화된다.
 *
 * numericId: MongoDB sequence 기반 자동 증가 Long ID.
 * MySQL의 auto_increment notification_id와 동일한 역할 — 커서 페이지네이션 + API 호환.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.notification.storage", havingValue = "mongodb")
public class MongoNotificationStorageAdapter implements NotificationStoragePort {

    private static final int SEGMENT_SIZE = 1000;

    private final MongoTemplate mongoTemplate;
    private final AtomicLong currentId = new AtomicLong(0);
    private final AtomicLong maxId = new AtomicLong(0);
    private final ReentrantLock seqLock = new ReentrantLock();

    public MongoNotificationStorageAdapter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    // ========== CRUD ==========

    @Override
    public Long save(Long userId, NotificationType type, String content) {
        Long numericId = nextSequence();
        NotificationDocument doc = NotificationDocument.builder()
                .numericId(numericId)
                .userId(userId)
                .content(content)
                .type(type)
                .build();
        mongoTemplate.save(doc);
        return numericId;
    }

    @Override
    public List<NotificationItemDto> findByUserId(Long userId, Long cursor, int size) {
        long watermark = getWatermark(userId);
        Criteria criteria = Criteria.where("userId").is(userId);
        if (cursor != null) {
            criteria = criteria.and("numericId").lt(cursor);
        }

        Query query = new Query(criteria)
                .with(Sort.by(Sort.Direction.DESC, "numericId"))
                .limit(size);

        return mongoTemplate.find(query, NotificationDocument.class)
                .stream()
                .map(doc -> toItemDtoWithWatermark(doc, watermark))
                .toList();
    }

    @Override
    public Long countUnreadByUserId(Long userId) {
        long watermark = getWatermark(userId);
        Criteria criteria = Criteria.where("userId").is(userId)
                .and("isRead").is(false)
                .and("numericId").gt(watermark);
        return mongoTemplate.count(new Query(criteria), NotificationDocument.class);
    }

    @Override
    public int markAsReadByIdAndUserId(Long notificationId, Long userId) {
        Query query = new Query(Criteria.where("numericId").is(notificationId)
                .and("userId").is(userId)
                .and("isRead").is(false));
        UpdateResult result = mongoTemplate.updateFirst(query,
                new Update().set("isRead", true), NotificationDocument.class);
        return (int) result.getModifiedCount();
    }

    @Override
    public boolean deleteByIdAndUserId(Long notificationId, Long userId) {
        // 미읽음 상태 확인 후 삭제 (단일 findAndRemove)
        Query unreadQuery = new Query(Criteria.where("numericId").is(notificationId)
                .and("userId").is(userId)
                .and("isRead").is(false));
        NotificationDocument removed = mongoTemplate.findAndRemove(unreadQuery, NotificationDocument.class);
        if (removed != null) return true;

        // 읽음 상태 알림 삭제
        Query readQuery = new Query(Criteria.where("numericId").is(notificationId)
                .and("userId").is(userId));
        DeleteResult deleteResult = mongoTemplate.remove(readQuery, NotificationDocument.class);
        return false; // wasUnread = false
    }

    /**
     * 워터마크 방식 mark-all: document 대량 UPDATE 없음.
     * 현재 사용자의 최대 numericId를 조회하여 워터마크로 저장.
     * 이 값 이하의 알림은 조회 시 모두 읽음으로 간주.
     */
    @Override
    public long markAllAsReadByUserId(Long userId) {
        // 1. 현재 유저의 최대 numericId 조회
        Query maxQuery = new Query(Criteria.where("userId").is(userId))
                .with(Sort.by(Sort.Direction.DESC, "numericId"))
                .limit(1);
        maxQuery.fields().include("numericId");
        NotificationDocument latest = mongoTemplate.findOne(maxQuery, NotificationDocument.class);
        if (latest == null) return 0;

        long maxId = latest.getNumericId();
        long oldWatermark = getWatermark(userId);
        if (maxId <= oldWatermark) return 0;

        // 2. 워터마크 upsert (O(1) 연산)
        upsertWatermark(userId, maxId);

        // 3. 실제 변경 건수 근사값 반환 (워터마크 이상 & 미읽음)
        return maxId - oldWatermark;
    }

    @Override
    public void markDeliveredByIds(List<Long> notificationIds) {
        if (notificationIds.isEmpty()) return;
        Query query = new Query(Criteria.where("numericId").in(notificationIds));
        mongoTemplate.updateMulti(query,
                new Update().set("delivered", true), NotificationDocument.class);
    }

    @Override
    public List<NotificationItemDto> findUndeliveredByUserId(Long userId, int limit) {
        Query query = new Query(
                Criteria.where("userId").is(userId).and("delivered").is(false))
                .with(Sort.by(Sort.Direction.ASC, "numericId"))
                .limit(limit);

        return mongoTemplate.find(query, NotificationDocument.class)
                .stream()
                .map(this::toItemDto)
                .toList();
    }

    // ========== sequence (segment allocation) ==========

    /**
     * 호번(Segment) 방식 ID 할당.
     * MongoDB findAndModify를 SEGMENT_SIZE 건당 1회만 호출하여 경합을 1/1000로 줄인다.
     * 메모리에서 AtomicLong으로 순차 할당 → MongoDB 호출 없이 즉시 반환.
     */
    private Long nextSequence() {
        long id = currentId.incrementAndGet();
        if (id <= maxId.get()) {
            return id;
        }
        seqLock.lock();
        try {
            // double-check: 다른 스레드가 이미 확장했을 수 있음
            if (currentId.get() <= maxId.get()) {
                return currentId.incrementAndGet();
            }
            long newMax = allocateSegment(SEGMENT_SIZE);
            long newStart = newMax - SEGMENT_SIZE;
            currentId.set(newStart);
            maxId.set(newMax);
            return currentId.incrementAndGet();
        } finally {
            seqLock.unlock();
        }
    }

    /**
     * MongoDB counters 컬렉션에서 segmentSize만큼 원자적으로 증가시켜 범위를 확보한다.
     * @return 확보된 범위의 최대값 (exclusive 아닌 inclusive 상한)
     */
    private long allocateSegment(int segmentSize) {
        Query query = new Query(Criteria.where("_id").is("notification_seq"));
        Update update = new Update().inc("seq", segmentSize);
        FindAndModifyOptions options = FindAndModifyOptions.options()
                .returnNew(true)
                .upsert(true);

        org.bson.Document counter = mongoTemplate.findAndModify(
                query, update, options, org.bson.Document.class, "counters");

        if (counter == null) return segmentSize;
        Object seq = counter.get("seq");
        return seq instanceof Number n ? n.longValue() : segmentSize;
    }

    // ========== watermark ==========

    private static final String WATERMARK_COLLECTION = "user_notification_state";

    private long getWatermark(Long userId) {
        Query query = new Query(Criteria.where("_id").is(userId));
        query.fields().include("readAllUptoId");
        Document doc = mongoTemplate.findOne(query, Document.class, WATERMARK_COLLECTION);
        if (doc == null) return 0L;
        Object val = doc.get("readAllUptoId");
        return val instanceof Number n ? n.longValue() : 0L;
    }

    private void upsertWatermark(Long userId, long maxId) {
        Query query = new Query(Criteria.where("_id").is(userId));
        // GREATEST 동등: $max 연산자로 기존 값보다 클 때만 갱신
        Update update = new Update()
                .max("readAllUptoId", maxId)
                .currentDate("updatedAt");
        mongoTemplate.upsert(query, update, WATERMARK_COLLECTION);
    }

    // ========== mapping ==========

    private NotificationItemDto toItemDto(NotificationDocument doc) {
        return new NotificationItemDto(
                doc.getNumericId(),
                doc.getContent(),
                doc.getType(),
                doc.isRead(),
                doc.getCreatedAt()
        );
    }

    private NotificationItemDto toItemDtoWithWatermark(NotificationDocument doc, long watermark) {
        boolean effectiveRead = doc.isRead() || doc.getNumericId() <= watermark;
        return new NotificationItemDto(
                doc.getNumericId(),
                doc.getContent(),
                doc.getType(),
                effectiveRead,
                doc.getCreatedAt()
        );
    }
}
