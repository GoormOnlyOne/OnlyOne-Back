package com.example.onlyone.domain.chat.port;

import com.example.onlyone.domain.chat.dto.ChatMessageItemDto;
import com.example.onlyone.domain.chat.entity.MessageDocument;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MongoDB 기반 채팅 메시지 저장소 어댑터.
 * {@code app.chat.storage=mongodb} 일 때 활성화.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.chat.storage", havingValue = "mongodb")
public class MongoChatMessageStorageAdapter implements ChatMessageStoragePort {

    private static final int SEGMENT_SIZE = 1000;
    private static final String COUNTER_NAME = "message_seq";

    private final MongoTemplate mongoTemplate;
    private final AtomicLong currentId = new AtomicLong(0);
    private final AtomicLong maxId = new AtomicLong(0);

    public MongoChatMessageStorageAdapter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    // ========== CRUD ==========

    @Override
    public ChatMessageItemDto save(Long chatRoomId, Long userId, String nickname,
                                   String profileImage, String text, LocalDateTime sentAt) {
        Long numericId = nextSequence();
        MessageDocument doc = MessageDocument.builder()
                .numericId(numericId)
                .chatRoomId(chatRoomId)
                .senderId(userId)
                .senderNickname(nickname)
                .senderProfileImage(profileImage)
                .text(text)
                .sentAt(sentAt)
                .build();
        mongoTemplate.save(doc);
        return toDto(doc, numericId);
    }

    @Override
    public Optional<ChatMessageItemDto> findById(Long messageId) {
        Query query = new Query(Criteria.where("numericId").is(messageId));
        MessageDocument doc = mongoTemplate.findOne(query, MessageDocument.class);
        return Optional.ofNullable(doc).map(d -> toDto(d, d.getNumericId()));
    }

    @Override
    public boolean markAsDeleted(Long messageId) {
        Query query = new Query(Criteria.where("numericId").is(messageId).and("deleted").is(false));
        MessageDocument doc = mongoTemplate.findOne(query, MessageDocument.class);
        if (doc == null) return false;
        doc.markAsDeleted();
        mongoTemplate.save(doc);
        return true;
    }

    @Override
    public List<ChatMessageItemDto> findLatest(Long chatRoomId, int limit) {
        Query query = new Query(
                Criteria.where("chatRoomId").is(chatRoomId).and("deleted").is(false))
                .with(Sort.by(Sort.Direction.DESC, "sentAt", "numericId"))
                .limit(limit);

        return mongoTemplate.find(query, MessageDocument.class)
                .stream()
                .map(d -> toDto(d, d.getNumericId()))
                .toList();
    }

    @Override
    public List<ChatMessageItemDto> findOlderThan(Long chatRoomId, LocalDateTime cursorAt,
                                                  Long cursorId, int limit) {
        // (sentAt < cursorAt) OR (sentAt = cursorAt AND numericId < cursorId)
        Criteria cursor = new Criteria().orOperator(
                Criteria.where("sentAt").lt(cursorAt),
                Criteria.where("sentAt").is(cursorAt).and("numericId").lt(cursorId)
        );

        Query query = new Query(
                new Criteria().andOperator(
                        Criteria.where("chatRoomId").is(chatRoomId),
                        Criteria.where("deleted").is(false),
                        cursor
                ))
                .with(Sort.by(Sort.Direction.DESC, "sentAt", "numericId"))
                .limit(limit);

        return mongoTemplate.find(query, MessageDocument.class)
                .stream()
                .map(d -> toDto(d, d.getNumericId()))
                .toList();
    }

    @Override
    public List<ChatMessageItemDto> findLastMessagesByChatRoomIds(List<Long> chatRoomIds) {
        if (chatRoomIds.isEmpty()) return List.of();

        Aggregation agg = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("chatRoomId").in(chatRoomIds).and("deleted").is(false)),
                Aggregation.sort(Sort.Direction.DESC, "sentAt", "numericId"),
                Aggregation.group("chatRoomId")
                        .first("numericId").as("numericId")
                        .first("chatRoomId").as("chatRoomId")
                        .first("senderId").as("senderId")
                        .first("senderNickname").as("senderNickname")
                        .first("senderProfileImage").as("senderProfileImage")
                        .first("text").as("text")
                        .first("sentAt").as("sentAt")
                        .first("deleted").as("deleted")
        );

        AggregationResults<MessageDocument> results =
                mongoTemplate.aggregate(agg, "messages", MessageDocument.class);

        return results.getMappedResults().stream()
                .map(d -> toDto(d, d.getNumericId()))
                .toList();
    }

    // ========== sequence (segment allocation) ==========

    private Long nextSequence() {
        long id = currentId.incrementAndGet();
        if (id <= maxId.get()) {
            return id;
        }
        synchronized (this) {
            if (currentId.get() <= maxId.get()) {
                return currentId.incrementAndGet();
            }
            long newMax = allocateSegment(SEGMENT_SIZE);
            long newStart = newMax - SEGMENT_SIZE;
            currentId.set(newStart);
            maxId.set(newMax);
            return currentId.incrementAndGet();
        }
    }

    private long allocateSegment(int segmentSize) {
        Query query = new Query(Criteria.where("_id").is(COUNTER_NAME));
        Update update = new Update().inc("seq", segmentSize);
        FindAndModifyOptions options = FindAndModifyOptions.options()
                .returnNew(true)
                .upsert(true);

        Document counter = mongoTemplate.findAndModify(
                query, update, options, Document.class, "counters");

        if (counter == null) return segmentSize;
        Object seq = counter.get("seq");
        return seq instanceof Number n ? n.longValue() : segmentSize;
    }

    // ========== mapping ==========

    private ChatMessageItemDto toDto(MessageDocument doc, Long numericId) {
        return new ChatMessageItemDto(
                numericId,
                doc.getChatRoomId(),
                doc.getSenderId(),
                doc.getSenderNickname(),
                doc.getSenderProfileImage(),
                doc.getText(),
                doc.getSentAt(),
                doc.isDeleted()
        );
    }
}
