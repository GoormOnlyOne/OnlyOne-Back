package com.example.onlyone.domain.chat.entity;

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
 * MongoDB 채팅 메시지 도큐먼트.
 * MySQL의 Message 엔티티와 동일한 역할.
 * sender 정보를 비정규화 저장하여 JOIN 불필요.
 */
@Document(collection = "messages")
@CompoundIndexes({
        @CompoundIndex(name = "idx_room_sentat_numid_desc",
                def = "{'chatRoomId': 1, 'sentAt': -1, 'numericId': -1}"),
        @CompoundIndex(name = "idx_room_deleted_numid_desc",
                def = "{'chatRoomId': 1, 'deleted': 1, 'numericId': -1}")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MessageDocument {

    @Id
    private String id;

    /** MySQL message_id 대응 — 유일한 Long ID (segment 채번) */
    @Indexed(unique = true)
    private Long numericId;

    private Long chatRoomId;
    private Long senderId;
    private String senderNickname;
    private String senderProfileImage;
    private String text;
    private LocalDateTime sentAt;
    private boolean deleted;

    @CreatedDate
    private LocalDateTime createdAt;

    @Builder
    public MessageDocument(Long numericId, Long chatRoomId, Long senderId,
                           String senderNickname, String senderProfileImage,
                           String text, LocalDateTime sentAt) {
        this.numericId = numericId;
        this.chatRoomId = chatRoomId;
        this.senderId = senderId;
        this.senderNickname = senderNickname;
        this.senderProfileImage = senderProfileImage;
        this.text = text;
        this.sentAt = sentAt;
        this.deleted = false;
    }

    public void markAsDeleted() {
        this.deleted = true;
        this.text = "삭제된 메시지입니다.";
    }
}
