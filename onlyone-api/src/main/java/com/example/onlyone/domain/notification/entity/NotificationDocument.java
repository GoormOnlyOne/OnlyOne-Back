package com.example.onlyone.domain.notification.entity;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * MongoDB 알림 도큐먼트.
 * MySQL의 Notification 엔티티와 동일한 역할을 한다.
 * numericId: MySQL auto_increment 대응 — 커서 페이지네이션 + API 호환용 Long ID.
 */
@Document(collection = "notifications")
@CompoundIndexes({
        @CompoundIndex(name = "idx_user_numid_desc", def = "{'userId': 1, 'numericId': -1}"),
        @CompoundIndex(name = "idx_user_read", def = "{'userId': 1, 'isRead': 1, 'numericId': 1}"),
        @CompoundIndex(name = "idx_user_delivered", def = "{'userId': 1, 'delivered': 1, 'numericId': 1}")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationDocument {

    @Id
    private String id;

    /** MySQL notification_id 대응 — 유일한 Long ID (sequence 채번) */
    @Indexed(unique = true)
    private Long numericId;

    private Long userId;

    private String content;

    private NotificationType type;

    private boolean isRead;

    private boolean delivered;

    @CreatedDate
    private LocalDateTime createdAt;

    @Builder
    public NotificationDocument(Long numericId, Long userId, String content, NotificationType type) {
        this.numericId = numericId;
        this.userId = userId;
        this.content = content;
        this.type = type;
        this.isRead = false;
        this.delivered = false;
    }

    public void markAsRead() {
        this.isRead = true;
    }
}
