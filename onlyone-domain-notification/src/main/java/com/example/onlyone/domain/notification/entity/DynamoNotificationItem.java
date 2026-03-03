package com.example.onlyone.domain.notification.entity;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*;

import java.time.LocalDateTime;

/**
 * DynamoDB Enhanced Client용 알림 엔티티.
 * <p>
 * 테이블: notification
 * PK: userId (Long)  — Partition Key
 * SK: numericId (Long, DESC 쿼리)  — Sort Key
 * <p>
 * GSI-1: userId + isRead   → countUnreadByUserId
 * GSI-2: userId + delivered → findUndeliveredByUserId
 */
@DynamoDbBean
public class DynamoNotificationItem {

    public static final String TABLE_NAME = "notification";
    public static final String GSI_USER_READ = "gsi-user-read";
    public static final String GSI_USER_DELIVERED = "gsi-user-delivered";

    private Long userId;
    private Long numericId;
    private String content;
    private String type;
    private Boolean isRead;
    private Boolean delivered;
    private String createdAt;

    public DynamoNotificationItem() {
    }

    @DynamoDbPartitionKey
    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    @DynamoDbSortKey
    public Long getNumericId() {
        return numericId;
    }

    public void setNumericId(Long numericId) {
        this.numericId = numericId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = {GSI_USER_READ, GSI_USER_DELIVERED})
    public Long getGsiUserId() {
        return userId;
    }

    public void setGsiUserId(Long ignored) {
        // projected from userId — setter required by Enhanced Client
    }

    @DynamoDbSecondarySortKey(indexNames = GSI_USER_READ)
    public Boolean getIsRead() {
        return isRead;
    }

    public void setIsRead(Boolean isRead) {
        this.isRead = isRead;
    }

    @DynamoDbSecondarySortKey(indexNames = GSI_USER_DELIVERED)
    public Boolean getDelivered() {
        return delivered;
    }

    public void setDelivered(Boolean delivered) {
        this.delivered = delivered;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    // ========== Factory ==========

    public static DynamoNotificationItem create(Long userId, Long numericId,
                                                 NotificationType type, String content) {
        DynamoNotificationItem item = new DynamoNotificationItem();
        item.setUserId(userId);
        item.setNumericId(numericId);
        item.setContent(content);
        item.setType(type.name());
        item.setIsRead(false);
        item.setDelivered(false);
        item.setCreatedAt(LocalDateTime.now().toString());
        return item;
    }
}
