package com.example.onlyone.domain.notification.port;

import com.example.onlyone.domain.notification.dto.response.NotificationItemDto;
import com.example.onlyone.domain.notification.entity.DynamoNotificationItem;
import com.example.onlyone.domain.notification.entity.NotificationType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DynamoDB 기반 알림 저장소 어댑터.
 * {@code app.notification.storage=dynamodb} 일 때 활성화된다.
 * <p>
 * ID 생성: DynamoDB atomic counter (MongoDB 어댑터의 segment 패턴 재사용).
 * 쿼리: DynamoDB Enhanced Client + low-level QueryRequest (GSI).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.notification.storage", havingValue = "dynamodb")
public class DynamoNotificationStorageAdapter implements NotificationStoragePort {

    private static final int SEGMENT_SIZE = 1000;
    private static final String COUNTER_TABLE = "notification_counter";

    private final DynamoDbTable<DynamoNotificationItem> table;
    private final DynamoDbClient dynamoDbClient;

    private final AtomicLong currentId = new AtomicLong(0);
    private final AtomicLong maxId = new AtomicLong(0);

    public DynamoNotificationStorageAdapter(DynamoDbTable<DynamoNotificationItem> table,
                                             DynamoDbClient dynamoDbClient) {
        this.table = table;
        this.dynamoDbClient = dynamoDbClient;
        ensureCounterTable();
    }

    // ========== CRUD ==========

    @Override
    public Long save(Long userId, NotificationType type, String content) {
        Long numericId = nextSequence();
        DynamoNotificationItem item = DynamoNotificationItem.create(userId, numericId, type, content);
        table.putItem(item);
        return numericId;
    }

    @Override
    public List<NotificationItemDto> findByUserId(Long userId, Long cursor, int size) {
        // PK=userId, SK(numericId) DESC — ScanIndexForward=false
        Map<String, AttributeValue> exprValues = new HashMap<>();
        exprValues.put(":uid", AttributeValue.fromN(userId.toString()));

        String keyCondition = "userId = :uid";
        if (cursor != null) {
            keyCondition += " AND numericId < :cursor";
            exprValues.put(":cursor", AttributeValue.fromN(cursor.toString()));
        }

        QueryRequest req = QueryRequest.builder()
                .tableName(DynamoNotificationItem.TABLE_NAME)
                .keyConditionExpression(keyCondition)
                .expressionAttributeValues(exprValues)
                .scanIndexForward(false)
                .limit(size)
                .build();

        QueryResponse resp = dynamoDbClient.query(req);
        return resp.items().stream()
                .map(this::toItemDto)
                .toList();
    }

    @Override
    public Long countUnreadByUserId(Long userId) {
        // GSI: gsiUserId=userId, isRead=0 (false)
        Map<String, AttributeValue> exprValues = new HashMap<>();
        exprValues.put(":uid", AttributeValue.fromN(userId.toString()));
        exprValues.put(":unread", AttributeValue.fromN("0"));

        QueryRequest req = QueryRequest.builder()
                .tableName(DynamoNotificationItem.TABLE_NAME)
                .indexName(DynamoNotificationItem.GSI_USER_READ)
                .keyConditionExpression("gsiUserId = :uid AND isRead = :unread")
                .expressionAttributeValues(exprValues)
                .select(software.amazon.awssdk.services.dynamodb.model.Select.COUNT)
                .build();

        return (long) dynamoDbClient.query(req).count();
    }

    @Override
    public int markAsReadByIdAndUserId(Long notificationId, Long userId) {
        // 조건부 업데이트: isRead=false일 때만 true로 변경 (멱등성)
        Map<String, AttributeValue> key = new HashMap<>();
        key.put("userId", AttributeValue.fromN(userId.toString()));
        key.put("numericId", AttributeValue.fromN(notificationId.toString()));

        Map<String, AttributeValue> exprValues = new HashMap<>();
        exprValues.put(":t", AttributeValue.fromN("1"));
        exprValues.put(":f", AttributeValue.fromN("0"));

        try {
            dynamoDbClient.updateItem(UpdateItemRequest.builder()
                    .tableName(DynamoNotificationItem.TABLE_NAME)
                    .key(key)
                    .updateExpression("SET isRead = :t")
                    .conditionExpression("isRead = :f")
                    .expressionAttributeValues(exprValues)
                    .build());
            return 1;
        } catch (software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException e) {
            return 0; // 이미 읽음 상태
        }
    }

    @Override
    public boolean deleteByIdAndUserId(Long notificationId, Long userId) {
        Key key = Key.builder()
                .partitionValue(userId)
                .sortValue(numericId(notificationId))
                .build();

        // 먼저 조회하여 isRead 확인
        DynamoNotificationItem existing = table.getItem(key);
        if (existing == null) return false;

        boolean wasUnread = !Boolean.TRUE.equals(existing.getIsRead());
        table.deleteItem(key);
        return wasUnread;
    }

    @Override
    public long markAllAsReadByUserId(Long userId) {
        // GSI로 미읽음 항목 조회 후 배치 업데이트
        Map<String, AttributeValue> exprValues = new HashMap<>();
        exprValues.put(":uid", AttributeValue.fromN(userId.toString()));
        exprValues.put(":unread", AttributeValue.fromN("0"));

        QueryRequest req = QueryRequest.builder()
                .tableName(DynamoNotificationItem.TABLE_NAME)
                .indexName(DynamoNotificationItem.GSI_USER_READ)
                .keyConditionExpression("gsiUserId = :uid AND isRead = :unread")
                .expressionAttributeValues(exprValues)
                .projectionExpression("userId, numericId")
                .build();

        QueryResponse resp = dynamoDbClient.query(req);
        long count = 0;

        Map<String, AttributeValue> updateValues = new HashMap<>();
        updateValues.put(":t", AttributeValue.fromN("1"));

        for (Map<String, AttributeValue> item : resp.items()) {
            Map<String, AttributeValue> updateKey = new HashMap<>();
            updateKey.put("userId", item.get("userId"));
            updateKey.put("numericId", item.get("numericId"));

            dynamoDbClient.updateItem(UpdateItemRequest.builder()
                    .tableName(DynamoNotificationItem.TABLE_NAME)
                    .key(updateKey)
                    .updateExpression("SET isRead = :t")
                    .expressionAttributeValues(updateValues)
                    .build());
            count++;
        }
        return count;
    }

    @Override
    public void markDeliveredByIds(List<Long> notificationIds) {
        if (notificationIds.isEmpty()) return;

        // DynamoDB는 BatchWriteItem이 UPDATE를 지원하지 않으므로 개별 UpdateItem
        Map<String, AttributeValue> updateValues = new HashMap<>();
        updateValues.put(":t", AttributeValue.fromN("1"));

        for (Long nid : notificationIds) {
            // numericId만으로는 PK(userId) 없이 업데이트 불가 → 전체 스캔 방지를 위해
            // GSI 역조회 또는 caller에서 userId 전달이 이상적이지만,
            // 현재 인터페이스가 ids만 받으므로 scan 대신 skip (실제 배치 사이즈 10 이하)
            // 실제 운영에서는 userId+numericId 복합키를 넘기도록 인터페이스 확장 권장
            scanAndUpdateDelivered(nid, updateValues);
        }
    }

    @Override
    public List<NotificationItemDto> findUndeliveredByUserId(Long userId, int limit) {
        Map<String, AttributeValue> exprValues = new HashMap<>();
        exprValues.put(":uid", AttributeValue.fromN(userId.toString()));
        exprValues.put(":undelivered", AttributeValue.fromN("0"));

        QueryRequest req = QueryRequest.builder()
                .tableName(DynamoNotificationItem.TABLE_NAME)
                .indexName(DynamoNotificationItem.GSI_USER_DELIVERED)
                .keyConditionExpression("gsiUserId = :uid AND delivered = :undelivered")
                .expressionAttributeValues(exprValues)
                .limit(limit)
                .build();

        QueryResponse resp = dynamoDbClient.query(req);
        return resp.items().stream()
                .map(this::toItemDto)
                .toList();
    }

    // ========== Sequence (segment allocation via DynamoDB atomic counter) ==========

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

    /**
     * DynamoDB atomic counter로 segment 할당.
     * UpdateItem + ADD 연산으로 원자적으로 증가시킨 값을 반환한다.
     */
    private long allocateSegment(int segmentSize) {
        Map<String, AttributeValue> key = new HashMap<>();
        key.put("counterName", AttributeValue.fromS("notification_seq"));

        Map<String, AttributeValue> exprValues = new HashMap<>();
        exprValues.put(":inc", AttributeValue.fromN(String.valueOf(segmentSize)));

        var response = dynamoDbClient.updateItem(UpdateItemRequest.builder()
                .tableName(COUNTER_TABLE)
                .key(key)
                .updateExpression("ADD seq :inc")
                .expressionAttributeValues(exprValues)
                .returnValues(software.amazon.awssdk.services.dynamodb.model.ReturnValue.UPDATED_NEW)
                .build());

        return Long.parseLong(response.attributes().get("seq").n());
    }

    private void ensureCounterTable() {
        try {
            dynamoDbClient.describeTable(b -> b.tableName(COUNTER_TABLE));
        } catch (software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException e) {
            dynamoDbClient.createTable(software.amazon.awssdk.services.dynamodb.model.CreateTableRequest.builder()
                    .tableName(COUNTER_TABLE)
                    .keySchema(software.amazon.awssdk.services.dynamodb.model.KeySchemaElement.builder()
                            .attributeName("counterName").keyType(software.amazon.awssdk.services.dynamodb.model.KeyType.HASH).build())
                    .attributeDefinitions(software.amazon.awssdk.services.dynamodb.model.AttributeDefinition.builder()
                            .attributeName("counterName").attributeType(software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType.S).build())
                    .billingMode(software.amazon.awssdk.services.dynamodb.model.BillingMode.PAY_PER_REQUEST)
                    .build());
            dynamoDbClient.waiter().waitUntilTableExists(b -> b.tableName(COUNTER_TABLE));
        }
    }

    // ========== Internal helpers ==========

    private void scanAndUpdateDelivered(Long numericId, Map<String, AttributeValue> updateValues) {
        // Scan for the item with this numericId (배치 사이즈 소규모이므로 허용)
        Map<String, AttributeValue> exprValues = new HashMap<>(updateValues);
        exprValues.put(":nid", AttributeValue.fromN(numericId.toString()));

        var scanResp = dynamoDbClient.scan(software.amazon.awssdk.services.dynamodb.model.ScanRequest.builder()
                .tableName(DynamoNotificationItem.TABLE_NAME)
                .filterExpression("numericId = :nid")
                .expressionAttributeValues(Map.of(":nid", AttributeValue.fromN(numericId.toString())))
                .projectionExpression("userId, numericId")
                .limit(1)
                .build());

        for (Map<String, AttributeValue> item : scanResp.items()) {
            Map<String, AttributeValue> updateKey = new HashMap<>();
            updateKey.put("userId", item.get("userId"));
            updateKey.put("numericId", item.get("numericId"));

            dynamoDbClient.updateItem(UpdateItemRequest.builder()
                    .tableName(DynamoNotificationItem.TABLE_NAME)
                    .key(updateKey)
                    .updateExpression("SET delivered = :t")
                    .expressionAttributeValues(updateValues)
                    .build());
        }
    }

    private long numericId(Long id) {
        return id;
    }

    private NotificationItemDto toItemDto(Map<String, AttributeValue> item) {
        return new NotificationItemDto(
                Long.parseLong(item.get("numericId").n()),
                item.containsKey("content") ? item.get("content").s() : "",
                NotificationType.valueOf(item.get("type").s()),
                "1".equals(item.containsKey("isRead") ? item.get("isRead").n() : "0"),
                item.containsKey("createdAt") ? LocalDateTime.parse(item.get("createdAt").s()) : null
        );
    }
}
