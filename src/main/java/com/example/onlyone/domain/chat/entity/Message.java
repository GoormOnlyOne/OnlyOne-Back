package com.example.onlyone.domain.chat.entity;

import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.global.BaseTimeEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "message")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Message extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "message_id", updatable = false, nullable = false)
    private Long messageId;

    @ManyToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "chat_room_id", updatable = false)
    @NotNull
    private ChatRoom chatRoom;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", updatable = false)
    @NotNull
    private User user;

    @Column(name = "text")
    @NotNull
    private String text;

    @Column(name = "sent_at")
    @NotNull
    private LocalDateTime sentAt;

    @Column(name = "deleted")
    @NotNull
    private boolean deleted;

    //소유자 확인 메서드
    public boolean isOwnedBy(Long userId) {
        return this.user != null && this.user.getUserId().equals(userId);
    }

    //삭제 처리 커맨드 메서드
    public void markAsDeleted() {
        this.deleted = true;
        this.text = "삭제된 메시지입니다.";
    }
}