package com.example.onlyone.domain.chat.repository;

import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.Type;
import com.example.onlyone.domain.chat.entity.UserChatRoom;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.interest.entity.Category;
import com.example.onlyone.domain.interest.entity.Interest;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleStatus;
import com.example.onlyone.domain.user.entity.Gender;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.chat.entity.ChatRole;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.sql.init.mode=never",
        "decorator.datasource.enabled=false",
        "spring.jpa.properties.hibernate.globally_quoted_identifiers=true",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class ChatRoomRepositoryTest {

    @Autowired
    EntityManager em;
    @Autowired
    ChatRoomRepository chatRoomRepository;
    @Autowired
    UserChatRoomRepository userChatRoomRepository;

    // ---------- Helpers ----------

    private Interest persistInterest(Category category) {
        Interest interest = Interest.builder()
                .category(category)
                .build();
        em.persist(interest);
        return interest;
    }

    private Long ensureInterest(String category, long ignored) {
        // 네이티브 대신 JPA 사용
        Interest interest = persistInterest(Category.valueOf(category));
        return interest.getInterestId();
    }

    private User persistUser(long kakaoId, Status status, String nickname) {
        User user = User.builder()
                .kakaoId(kakaoId)
                .nickname(nickname)
                .birth(LocalDate.of(1990, 1, 1))
                .status(status)
                .profileImage(null)
                .gender(Gender.MALE)
                .city("서울특별시")
                .district("강남구")
                
                .build();
        em.persist(user);
        return user;
    }

    private User getUserRefByKakaoId(long kakaoId, String nickname) {
        User saved = persistUser(kakaoId, Status.ACTIVE, nickname);
        // 굳이 Reference가 필요없으면 saved 리턴해도 됨
        return em.getReference(User.class, saved.getUserId());
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

    private ChatRoom persistRoom(Club club, Type type, Long scheduleId) {
        ChatRoom r = ChatRoom.builder()
                .club(club)
                .type(type)
                .scheduleId(scheduleId)
                .build();
        em.persist(r);
        return r;
    }

    private Schedule persistSchedule(Club club) {
        Schedule schedule = Schedule.builder()
                .name("정기 모임 A")
                .location("서울 강남")
                .cost(10000)
                .userLimit(20)
                .scheduleTime(LocalDateTime.now().plusDays(7))
                .scheduleStatus(ScheduleStatus.READY)
                .club(club)
                .build();
        em.persist(schedule);
        return schedule;
    }

    private void join(User user, ChatRoom room) {
        UserChatRoom ucr = UserChatRoom.builder()
                .user(user)
                .chatRoom(room)
                .chatRole(ChatRole.MEMBER)
                .build();
        em.persist(ucr);
    }

    // ---------- Tests ----------

    @Test
    @DisplayName("채팅방ID와_모임ID로_채팅방을_단건_조회한다")
    void findByRoomIdAndClubId() {
        // given
        Club club = persistClub("모임A");
        ChatRoom room = persistRoom(club, Type.CLUB, null);

        // when
        Optional<ChatRoom> found =
                chatRoomRepository.findByChatRoomIdAndClubClubId(room.getChatRoomId(), club.getClubId());

        // then
        assertThat(found).isPresent();
        assertThat(found.get().getChatRoomId()).isEqualTo(room.getChatRoomId());
    }

    @Test
    @DisplayName("정기모임_채팅방을_단건_조회한다")
    void findScheduleChatRoom() {
        // given
        Club club = persistClub("모임A");
        Schedule schedule = persistSchedule(club);
        ChatRoom scheduleChatRoom = persistRoom(club, Type.SCHEDULE, schedule.getScheduleId());
        em.flush();
        em.clear();

        // when
        Optional<ChatRoom> found =
                chatRoomRepository.findByTypeAndScheduleId(Type.SCHEDULE, schedule.getScheduleId());

        // then
        assertThat(found).isPresent();
        assertThat(found.get().getScheduleId()).isEqualTo(scheduleChatRoom.getScheduleId());
        assertThat(found.get().getType()).isEqualTo(Type.SCHEDULE);
        assertThat(found.get().getClub().getClubId()).isEqualTo(club.getClubId());
    }


    @Test
    @DisplayName("모임_전체_채팅방을_조회한다")
    void findClubClubChatRoom() {
        // given
        Club club = persistClub("모임A");
        ChatRoom clubChatRoom = persistRoom(club, Type.CLUB, null);
        em.flush();
        em.clear();

        // when
        Optional<ChatRoom> found =
                chatRoomRepository.findByTypeAndClub_ClubId(Type.CLUB, club.getClubId());

        // then
        assertThat(found).isPresent();
        assertThat(found.get().getChatRoomId()).isEqualTo(clubChatRoom.getChatRoomId());
        assertThat(found.get().getType()).isEqualTo(Type.CLUB);
        assertThat(found.get().getScheduleId()).isNull();
    }

    @Test
    @DisplayName("잘못된_모임ID면_empty를_반환한다")
    void findByRoomIdAndWrongClubId_returnsEmpty() {
        // given
        Club club = persistClub("모임A");
        ChatRoom room = persistRoom(club, Type.CLUB, null);

        // when
        Optional<ChatRoom> found =
                chatRoomRepository.findByChatRoomIdAndClubClubId(room.getChatRoomId(), 9999L);

        // then
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("잘못된_채팅방ID면_empty를_반환한다")
    void findByWrongRoomId_returnsEmpty() {
        // given
        Club club = persistClub("모임A");
        // 채팅방 생성은 하지만 잘못된 ID로 조회
        persistRoom(club, Type.CLUB, null);

        // when
        Optional<ChatRoom> found =
                chatRoomRepository.findByChatRoomIdAndClubClubId(9999L, club.getClubId());

        // then
        assertThat(found).isEmpty();
    }


    @Test
    @DisplayName("사용자가_참여_중인_채팅방_목록을_조회한다")
    void findRoomsByUserAndClubOrdered() {
        // given
        Club club = persistClub("모임A");
        User user = getUserRefByKakaoId(100001L, "유저");

        ChatRoom olderClubRoom = persistRoom(club, Type.CLUB, null);

        Schedule schedule = persistSchedule(club);
        ChatRoom newerScheduleRoom = persistRoom(club, Type.SCHEDULE, schedule.getScheduleId());

        join(user, olderClubRoom);
        join(user, newerScheduleRoom);

        em.flush();
        em.clear();

        // when
        List<ChatRoom> result = chatRoomRepository.findChatRoomsByUserIdAndClubId(user.getUserId(), club.getClubId());

        // then
        assertThat(result)
                .extracting(ChatRoom::getChatRoomId)
                .containsExactly(newerScheduleRoom.getChatRoomId(), olderClubRoom.getChatRoomId());
    }
}
