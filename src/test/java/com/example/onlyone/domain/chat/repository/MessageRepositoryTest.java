package com.example.onlyone.domain.chat.repository;

import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.Message;
import com.example.onlyone.domain.chat.entity.Type;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleStatus;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.interest.entity.Interest;
import com.example.onlyone.domain.interest.entity.Category;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.sql.init.mode=never",
        "decorator.datasource.enabled=false",
        "spring.jpa.properties.hibernate.globally_quoted_identifiers=true",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class MessageRepositoryTest {

    @Autowired EntityManager em;
    @Autowired MessageRepository messageRepository;

    private Interest persistInterest(Category category) {
        Interest interest = Interest.builder()
                .category(category)
                .build();
        em.persist(interest);
        return interest;
    }

    private Club persistClub(String name) {
        Interest interest = persistInterest(Category.CULTURE); // 임의 카테고리
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
        ChatRoom room = ChatRoom.builder()
                .club(club)
                .type(type)
                .scheduleId(scheduleId)
                .build();
        em.persist(room);
        return room;
    }

    private Message persistMsg(ChatRoom room, User user, String text, LocalDateTime sentAt, boolean deleted) {
        Message m = Message.builder()
                .chatRoom(room)
                .user(user)
                .text(text)
                .sentAt(sentAt)
                .deleted(deleted)
                .build();
        em.persist(m);
        return m;
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

    // ========= Tests =========

    @Test
    @DisplayName("삭제되지_않은_메시지만_발송시간_오름차순으로_조회한다")
    void returnsNotDeletedOnlyInAscendingOrder() {
        Club club = persistClub("모임A");
        ChatRoom room = persistRoom(club, Type.CLUB, null);
        User u1 = persistUser(1001L, Status.ACTIVE, "u1");
        User u2 = persistUser(1002L, Status.ACTIVE, "u2");

        LocalDateTime base = LocalDateTime.now().minusMinutes(10);

        Message m0 = persistMsg(room, u1, "hello-0", base.plusMinutes(0), false);
        persistMsg(room, u1, "deleted-1", base.plusMinutes(1), true);
        Message m2 = persistMsg(room, u2, "hello-2", base.plusMinutes(2), false);

        em.flush(); em.clear();

        List<Message> result = messageRepository
                .findByChatRoomChatRoomIdAndDeletedFalseOrderBySentAtAsc(room.getChatRoomId());

        assertThat(result).extracting("messageId")
                .containsExactly(m0.getMessageId(), m2.getMessageId());
        assertThat(result).allMatch(m -> !m.isDeleted());
        assertThat(result)
                .isSortedAccordingTo((a, b) -> a.getSentAt().compareTo(b.getSentAt()));
    }

    @Test
    @DisplayName("채팅방ID들로_마지막_메시지들을_조회한다")
    void findLastMessagesByChatRoomIds() {
        // given
        Club club = persistClub("모임A");
        ChatRoom r1 = persistRoom(club, Type.CLUB, null);

        Schedule sch = persistSchedule(club);
        ChatRoom r2 = persistRoom(club, Type.SCHEDULE, sch.getScheduleId());

        User u = persistUser(1001L, Status.ACTIVE, "u");

        LocalDateTime base = LocalDateTime.now().minusMinutes(5);

        persistMsg(r1, u, "r1-1", base.plusMinutes(1), false);
        Message r1Last = persistMsg(r1, u, "r1-2", base.plusMinutes(3), false);
        persistMsg(r1, u, "r1-del", base.plusMinutes(4), true);

        persistMsg(r2, u, "r2-1", base.plusMinutes(1), false);
        Message r2Last = persistMsg(r2, u, "r2-2", base.plusMinutes(4), false);

        em.flush(); em.clear();

        // when
        List<Message> result = messageRepository.findLastMessagesByChatRoomIds(
                List.of(r1.getChatRoomId(), r2.getChatRoomId()));

        // then
        assertThat(result).hasSize(2);
        Map<Long, Message> map = result.stream().collect(Collectors.toMap(
                (Message m) -> m.getChatRoom().getChatRoomId(),
                Function.identity(),
                // 동률(중복 키)일 때: 더 최신 sentAt, sentAt 같으면 messageId 큰 걸 선택
                (a, b) -> a.getSentAt().isAfter(b.getSentAt()) ? a :
                        (a.getSentAt().isBefore(b.getSentAt()) ? b :
                                (a.getMessageId() > b.getMessageId() ? a : b))
        ));
    }
}