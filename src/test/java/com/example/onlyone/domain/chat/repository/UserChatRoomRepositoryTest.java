package com.example.onlyone.domain.chat.repository;

import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.Type;
import com.example.onlyone.domain.chat.entity.UserChatRoom;
import com.example.onlyone.domain.chat.entity.ChatRole;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.interest.entity.Category;
import com.example.onlyone.domain.interest.entity.Interest;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleStatus;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.sql.init.mode=never",
        "decorator.datasource.enabled=false",
        "spring.jpa.properties.hibernate.globally_quoted_identifiers=true",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class UserChatRoomRepositoryTest {

    @Autowired EntityManager em;
    @Autowired UserChatRoomRepository userChatRoomRepository;

    private Interest persistInterest(Category category) {
        Interest interest = Interest.builder()
                .category(category)
                .build();
        em.persist(interest);
        return interest;
    }

    private Club persistClub(String name) {
        Interest interest = persistInterest(Category.CULTURE);
        Club club = Club.builder()
                .name(name)
                .userLimit(100)
                .description("설명-" + name)
                .city("Seoul")
                .district("Gangnam")
                .interest(interest)
                .build();
        em.persist(club);
        return club;
    }

    private User persistUser(long kakaoId, Status status, String nickname) {
        User user = User.builder()
                .kakaoId(kakaoId)
                .status(status)
                .nickname(nickname)
                .build();
        em.persist(user);
        return user;
    }

    private ChatRoom persistRoom(Club club, Type type, Long scheduleId) {
        ChatRoom r = ChatRoom.builder()
                .club(club)
                .type(type)
                .scheduleId(scheduleId)
                .build();
        em.persist(r);
        return r;
    }

    private UserChatRoom persistUserChatRoom(User user, ChatRoom room, ChatRole role) {
        UserChatRoom ucr = UserChatRoom.builder()
                .user(user)
                .chatRoom(room)
                .chatRole(role)
                .build();
        em.persist(ucr);
        return ucr;
    }

    private Schedule persistSchedule(Club club) {
        Schedule schedule = Schedule.builder()
                .name("정기 모임 A")
                .location("서울 강남")
                .cost(10000)
                .userLimit(20)
                .scheduleTime(LocalDateTime.now().plusDays(7))
                .scheduleStatus(ScheduleStatus.READY) // enum 기본 상태
                .club(club)
                .build();

        em.persist(schedule);
        return schedule;
    }

    // ---------- Tests ----------

    @Test
    @DisplayName("사용자ID와_채팅방ID로_사용자의_채팅방_참여_정보를_단일_조회한다")
    void findByUserUserIdAndChatRoomChatRoomId() {
        // given
        Club club = persistClub("모임A");
        ChatRoom room = persistRoom(club, Type.CLUB, null);
        User user = persistUser(1001L, Status.ACTIVE, "u1");

        UserChatRoom saved = persistUserChatRoom(user, room, ChatRole.MEMBER);
        em.flush(); em.clear();

        // when
        Optional<UserChatRoom> found =
                userChatRoomRepository.findByUserUserIdAndChatRoomChatRoomId(user.getUserId(), room.getChatRoomId());

        // then
        assertThat(found).isPresent();
        assertThat(found.get().getUser().getUserId()).isEqualTo(user.getUserId());
        assertThat(found.get().getChatRoom().getChatRoomId()).isEqualTo(room.getChatRoomId());
        assertThat(found.get().getChatRole()).isEqualTo(ChatRole.MEMBER);
    }

    @Test
    @DisplayName("사용자가_채팅방에_참여중이면_true를_아니면_false를_반환한다")
    void existsTrueFalse() {
        // given
        Club club = persistClub("모임C");
        ChatRoom room1 = persistRoom(club, Type.CLUB, null);
        Schedule schedule = persistSchedule(club);
        ChatRoom room2 = persistRoom(club, Type.SCHEDULE, schedule.getScheduleId());

        User user = persistUser(2001L, Status.ACTIVE, "u");

        persistUserChatRoom(user, room1, ChatRole.MEMBER);
        em.flush(); em.clear();

        // when
        boolean inRoom1 = userChatRoomRepository
                .existsByUserUserIdAndChatRoomChatRoomId(user.getUserId(), room1.getChatRoomId());
        boolean inRoom2 = userChatRoomRepository
                .existsByUserUserIdAndChatRoomChatRoomId(user.getUserId(), room2.getChatRoomId());

        // then
        assertThat(inRoom1).isTrue();
        assertThat(inRoom2).isFalse();
    }
}

