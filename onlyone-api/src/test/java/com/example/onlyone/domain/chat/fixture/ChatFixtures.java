package com.example.onlyone.domain.chat.fixture;

import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.ChatRoomType;
import com.example.onlyone.domain.chat.entity.Message;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.ClubRole;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleStatus;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;

import java.time.LocalDateTime;

public final class ChatFixtures {

    private ChatFixtures() {}

    // ==================== User ====================

    public static final Long DEFAULT_USER_ID = 1L;
    public static final Long DEFAULT_KAKAO_ID = 100L;

    public static User user() {
        return user(DEFAULT_USER_ID, DEFAULT_KAKAO_ID, "테스트유저");
    }

    public static User user(Long userId, Long kakaoId, String nickname) {
        return User.builder()
                .userId(userId)
                .kakaoId(kakaoId)
                .nickname(nickname)
                .profileImage("https://example.com/profile.jpg")
                .status(Status.ACTIVE)
                .build();
    }

    // ==================== Club ====================

    public static Club club() {
        return club(1L, "테스트모임");
    }

    public static Club club(Long id, String name) {
        return Club.builder()
                .clubId(id)
                .name(name)
                .userLimit(100)
                .description("desc")
                .build();
    }

    public static UserClub userClub(User user, Club club) {
        return UserClub.builder()
                .user(user)
                .club(club)
                .clubRole(ClubRole.MEMBER)
                .build();
    }

    // ==================== Schedule ====================

    public static Schedule schedule(Long id, String name, Club club) {
        return Schedule.builder()
                .scheduleId(id)
                .name(name)
                .userLimit(10)
                .scheduleStatus(ScheduleStatus.READY)
                .scheduleTime(LocalDateTime.now().plusDays(1))
                .club(club)
                .build();
    }

    // ==================== ChatRoom ====================

    public static ChatRoom clubChatRoom(Long id, Club club) {
        return ChatRoom.builder()
                .chatRoomId(id)
                .club(club)
                .type(ChatRoomType.CLUB)
                .build();
    }

    public static ChatRoom scheduleChatRoom(Long id, Club club, Long scheduleId, Schedule schedule) {
        return ChatRoom.builder()
                .chatRoomId(id)
                .club(club)
                .type(ChatRoomType.SCHEDULE)
                .scheduleId(scheduleId)
                .schedule(schedule)
                .build();
    }

    // ==================== Message ====================

    public static final LocalDateTime DEFAULT_SENT_AT = LocalDateTime.of(2025, 7, 29, 11, 0, 0);

    public static Message message(Long id, ChatRoom room, User user, String text) {
        return message(id, room, user, text, DEFAULT_SENT_AT, false);
    }

    public static Message message(Long id, ChatRoom room, User user, String text,
                                  LocalDateTime sentAt, boolean deleted) {
        return Message.builder()
                .messageId(id)
                .chatRoom(room)
                .user(user)
                .text(text)
                .sentAt(sentAt)
                .deleted(deleted)
                .build();
    }

    public static Message deletedMessage(Long id, ChatRoom room, User user) {
        return message(id, room, user, "삭제된 메시지입니다.", DEFAULT_SENT_AT, true);
    }
}
