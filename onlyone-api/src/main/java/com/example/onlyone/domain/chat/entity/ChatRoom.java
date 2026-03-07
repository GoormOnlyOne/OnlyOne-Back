package com.example.onlyone.domain.chat.entity;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.common.BaseTimeEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "chat_room", indexes = {
        @Index(name = "idx_chat_room_club_type", columnList = "club_id, type"),
        @Index(name = "idx_chat_room_schedule", columnList = "schedule_id")
})
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ChatRoom extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chat_room_id", updatable = false, nullable = false)
    private Long chatRoomId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "club_id", updatable = false)
    @NotNull
    @JsonIgnore
    private Club club;

    // 논리적 FK로 설정
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id", insertable = false, updatable = false)
    private Schedule schedule;

    @Column(name = "schedule_id")
    private Long scheduleId;

    @Column(name = "type")
    @NotNull
    @Enumerated(EnumType.STRING)
    private ChatRoomType type;

    @Builder.Default
    @OneToMany(mappedBy = "chatRoom", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Message> messages = new ArrayList<>();

    /**
     * 채팅방 타입(CLUB/SCHEDULE)에 따른 표시 이름을 반환한다.
     */
    public String resolveName() {
        if (type == ChatRoomType.SCHEDULE && schedule != null) {
            return schedule.getName();
        }
        return club.getName();
    }

    @Builder.Default
    @OneToMany(mappedBy = "chatRoom", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<UserChatRoom> userChatRooms = new ArrayList<>();
}