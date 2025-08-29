package com.example.onlyone.domain.chat.service;

import com.example.onlyone.domain.chat.dto.ChatRoomResponse;
import com.example.onlyone.domain.chat.entity.*;
import com.example.onlyone.domain.chat.repository.ChatRoomRepository;
import com.example.onlyone.domain.chat.repository.MessageRepository;
import com.example.onlyone.domain.chat.repository.UserChatRoomRepository;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.ClubRole;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.interest.entity.Category;
import com.example.onlyone.domain.interest.entity.Interest;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleStatus;
import com.example.onlyone.domain.schedule.entity.UserSchedule;
import com.example.onlyone.domain.schedule.repository.ScheduleRepository;
import com.example.onlyone.domain.schedule.repository.UserScheduleRepository;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;

import com.example.onlyone.global.common.util.MessageUtils;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class ChatRoomServiceTest {

    @InjectMocks ChatRoomService chatRoomService;

    @Mock ClubRepository clubRepository;
    @Mock UserClubRepository userClubRepository;
    @Mock ChatRoomRepository chatRoomRepository;
    @Mock UserChatRoomRepository userChatRoomRepository;
    @Mock MessageRepository messageRepository;
    @Mock ScheduleRepository scheduleRepository;
    @Mock UserScheduleRepository userScheduleRepository;
    @Mock UserService userService;

    private Interest interest(Category c) {
        return Interest.builder()
                .interestId(77L)
                .category(c)
                .build();
    }

    private Club club(Long id, String name) {
        return Club.builder()
                .clubId(id)
                .name(name)
                .userLimit(100)
                .description("desc")
                .city("Seoul")
                .district("Gangnam")
                .interest(interest(Category.CULTURE))
                .build();
    }

    private User user(Long id, long kakaoId, String nick) {
        return User.builder()
                .userId(id)
                .kakaoId(kakaoId)
                .status(Status.ACTIVE)
                .nickname(nick)
                .build();
    }

    private ChatRoom room(Long id, Club club, Type type, Long scheduleId, Schedule schedule) {
        return ChatRoom.builder()
                .chatRoomId(id)
                .club(club)
                .type(type)
                .scheduleId(scheduleId)
                .schedule(schedule)
                .build();
    }

    private Message message(Long id, ChatRoom room, User user, String text, LocalDateTime at, boolean deleted) {
        return Message.builder()
                .messageId(id)
                .chatRoom(room)
                .user(user)
                .text(text)
                .sentAt(at)
                .deleted(deleted)
                .build();
    }

    private Schedule schedule(Long id, String name, Club club) {
        return Schedule.builder()
                .scheduleId(id)
                .name(name)
                .userLimit(10)
                .scheduleStatus(ScheduleStatus.READY)
                .scheduleTime(LocalDateTime.now().plusDays(1))
                .club(club)
                .build();
    }

    private UserClub membership(User user, Club club) {
        return UserClub.builder()
                .user(user)
                .club(club)
                .clubRole(ClubRole.MEMBER) // 필요 enum 경로로 맞추기
                .build();
    }

    // ========== getChatRoomsUserJoinedInClub ==========

    @Test
    @DisplayName("사용자가_모임에서_참여_중인_채팅방_목록을_조회한다")
    void getUserChatRoomsJoinedInClub() {
        // given
        User u = user(1L, 1001L, "u");
        Club club = club(10L, "모임A");

        ChatRoom r1 = room(101L, club, Type.CLUB, null, null);

        // 스케줄 엔티티 생성 + ChatRoom에 연관까지 채움
        Schedule sch = schedule(20L,"정모A", club);
        ChatRoom r2 = room(102L, club, Type.SCHEDULE, null, sch);

        given(userService.getCurrentUser()).willReturn(u);
        given(clubRepository.findById(10L)).willReturn(Optional.of(club));
        given(userClubRepository.findByUserAndClub(u, club))
                .willReturn(Optional.of(membership(u, club)));

        given(chatRoomRepository.findChatRoomsByUserIdAndClubId(1L, 10L))
                .willReturn(List.of(r1, r2));

        LocalDateTime now = LocalDateTime.now();
        Message last1 = message(10001L, r1, u, "r1-last", now, false);

        given(messageRepository.findLastMessagesByChatRoomIds(argThat(ids ->
                ids.containsAll(List.of(101L, 102L)) && ids.size() == 2
        ))).willReturn(List.of(last1));

        // when
        List<ChatRoomResponse> list = chatRoomService.getChatRoomsUserJoinedInClub(10L);

        // then
        assertThat(list).hasSize(2);
        Map<Long, ChatRoomResponse> byId = list.stream().collect(toMap(ChatRoomResponse::getChatRoomId, v -> v));

        ChatRoomResponse resp1 = byId.get(101L);
        assertThat(resp1.getChatRoomName()).isEqualTo("모임A");
        assertThat(resp1.getLastMessageText()).isEqualTo("r1-last");
        assertThat(resp1.getLastMessageTime()).isEqualTo(now);

        ChatRoomResponse resp2 = byId.get(102L);
        assertThat(resp2.getChatRoomName()).isEqualTo("정모A");
        assertThat(resp2.getScheduleId()).isEqualTo(20L);
        assertThat(resp2.getLastMessageText()).isNull();
        assertThat(resp2.getLastMessageTime()).isNull();

        then(userService).should().getCurrentUser();
        then(clubRepository).should().findById(10L);
        then(userClubRepository).should().findByUserAndClub(u, club);
        then(chatRoomRepository).should().findChatRoomsByUserIdAndClubId(1L, 10L);
        then(messageRepository).should().findLastMessagesByChatRoomIds(anyList());

        then(userService).shouldHaveNoMoreInteractions();
        then(clubRepository).shouldHaveNoMoreInteractions();
        then(userClubRepository).shouldHaveNoMoreInteractions();
        then(chatRoomRepository).shouldHaveNoMoreInteractions();
        then(messageRepository).shouldHaveNoMoreInteractions();
    }

    @Test
    @DisplayName("모임에_가입하지_않은_회원의_경우_채팅방_목록_조회에서_예외가_발생한다")
    void ChatRoomListNotClubMember() {
        // given
        User u = user(1L, 1001L, "u");
        Club club = club(10L, "모임A");
        given(userService.getCurrentUser()).willReturn(u);
        given(clubRepository.findById(10L)).willReturn(Optional.of(club));
        given(userClubRepository.findByUserAndClub(u, club)).willReturn(Optional.empty());

        // when
        Throwable thrown = catchThrowable(() -> chatRoomService.getChatRoomsUserJoinedInClub(10L));

        // then
        assertThat(thrown).isInstanceOf(CustomException.class);
        assertThat(((CustomException) thrown).getErrorCode()).isEqualTo(ErrorCode.CLUB_NOT_JOIN);

        then(userService).should().getCurrentUser();
        then(clubRepository).should().findById(10L);
        then(userClubRepository).should().findByUserAndClub(u, club);
        then(chatRoomRepository).shouldHaveNoInteractions();
        then(messageRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("로그인하지_않은_사용자의_경우_채팅방_목록_조회에서_예외가_발생한다")
    void ChatRoomListUnauthenticated() {
        // given
        given(userService.getCurrentUser()).willThrow(new CustomException(ErrorCode.UNAUTHORIZED));

        // when
        Throwable thrown = catchThrowable(() -> chatRoomService.getChatRoomsUserJoinedInClub(10L));

        // then
        assertThat(thrown).isInstanceOf(CustomException.class);
        assertThat(((CustomException) thrown).getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);

        then(userService).should().getCurrentUser();
        then(clubRepository).shouldHaveNoInteractions();
        then(userClubRepository).shouldHaveNoInteractions();
        then(chatRoomRepository).shouldHaveNoInteractions();
        then(messageRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("채팅방의_마지막_메시지가_이미지일_경우_'사진을 보냈습니다.'로_표시한다")
    void lastMessageIsImage() {
        // given
        User u = user(1L, 1001L, "u");
        Club c = club(10L, "모임A");
        ChatRoom r1 = room(101L, c, Type.CLUB, null, null);
        ChatRoom r2 = room(102L, c, Type.SCHEDULE, 20L, schedule(20L, "정모A", c));

        given(userService.getCurrentUser()).willReturn(u);
        given(clubRepository.findById(10L)).willReturn(Optional.of(c));
        given(userClubRepository.findByUserAndClub(u, c)).willReturn(Optional.of(membership(u, c)));
        given(chatRoomRepository.findChatRoomsByUserIdAndClubId(1L, 10L)).willReturn(List.of(r1, r2));

        var now = LocalDateTime.now().withNano(0);
        var last1 = message(5001L, r1, u, "text-last", now, false);

        Message last2 = mock(Message.class);
        when(last2.getChatRoom()).thenReturn(r2);
        when(last2.getText()).thenReturn("https://cdn/img.png");
        when(last2.isDeleted()).thenReturn(false);
        when(last2.getSentAt()).thenReturn(now.minusSeconds(1));

        given(messageRepository.findLastMessagesByChatRoomIds(argThat(ids ->
                ids.containsAll(List.of(101L, 102L)) && ids.size() == 2
        ))).willReturn(List.of(last1, last2));

        try (var mocked = org.mockito.Mockito.mockStatic(MessageUtils.class)) {
            mocked.when(() -> MessageUtils.getDisplayText("text-last"))
                    .thenReturn("text-last");
            mocked.when(() -> MessageUtils.getDisplayText("https://cdn/img.png"))
                    .thenReturn("사진을 보냈습니다.");

            // when
            List<ChatRoomResponse> list = chatRoomService.getChatRoomsUserJoinedInClub(10L);

            // then
            Map<Long, ChatRoomResponse> byId = list.stream()
                    .collect(Collectors.toMap(ChatRoomResponse::getChatRoomId, v -> v));

            assertThat(byId.get(101L).getLastMessageText()).isEqualTo("text-last");
            assertThat(byId.get(102L).getLastMessageText()).isEqualTo("사진을 보냈습니다.");
        }
    }

    @Test
    @DisplayName("모임_가입_시_전체_채팅방에_자동_참여한다")
    void joinClubChatRoom() {
        // given
        Long clubId = 10L;
        Long userId = 1001L;

        Club c = club(clubId, "모임A");
        ChatRoom clubRoom = room(111L, c, Type.CLUB, null, null);
        User u = user(userId, 9999L, "u");

        given(clubRepository.findById(clubId)).willReturn(Optional.of(c));
        given(chatRoomRepository.findByTypeAndClub_ClubId(Type.CLUB, clubId))
                .willReturn(Optional.of(clubRoom));
        given(userClubRepository.findByUserAndClub(
                argThat(usr -> usr != null && userId.equals(usr.getUserId())),
                argThat(clb -> clb != null && clubId.equals(clb.getClubId()))
        )).willReturn(Optional.of(membership(u, c)));
        given(userChatRoomRepository.existsByUserUserIdAndChatRoomChatRoomId(userId, 111L))
                .willReturn(false);

        // when
        chatRoomService.joinClubChatRoom(clubId, userId);

        // then
        then(userChatRoomRepository).should().save(argThat(m ->
                m.getChatRoom().getChatRoomId().equals(111L) &&
                        m.getUser().getUserId().equals(userId)
        ));
        then(userChatRoomRepository).shouldHaveNoMoreInteractions();
    }

    @Test
    @DisplayName("모임에_가입하지_않은_회원이_채팅방에_참여할_시_예외가_발생한다")
    void joinClubChatRoomNotClubMember() {
        // given
        Long clubId = 10L;
        Long userId = 1001L;

        Club c = club(clubId, "모임A");
        ChatRoom clubRoom = room(111L, c, Type.CLUB, null, null);

        given(clubRepository.findById(clubId)).willReturn(Optional.of(c));
        given(chatRoomRepository.findByTypeAndClub_ClubId(Type.CLUB, clubId))
                .willReturn(Optional.of(clubRoom));

        given(userClubRepository.findByUserAndClub(any(User.class), eq(c)))
                .willReturn(Optional.empty());

        // when
        Throwable thrown = catchThrowable(() -> chatRoomService.joinClubChatRoom(clubId, userId));

        // then
        assertThat(thrown).isInstanceOf(CustomException.class);
        assertThat(((CustomException) thrown).getErrorCode()).isEqualTo(ErrorCode.CLUB_NOT_JOIN);

        then(userChatRoomRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("이미_참여_중인_회원이_채팅방에_중복_참여할_시_예외가_발생한다")
    void joinClubChatRoomDuplicate() {
        // given
        Long clubId = 10L;
        Long userId = 1001L;
        Club c = club(clubId, "모임A");
        ChatRoom clubRoom = room(111L, c, Type.CLUB, null, null);

        given(clubRepository.findById(clubId)).willReturn(Optional.of(c));
        given(chatRoomRepository.findByTypeAndClub_ClubId(Type.CLUB, clubId))
                .willReturn(Optional.of(clubRoom));
        given(userClubRepository.findByUserAndClub(any(User.class), eq(c)))
                .willReturn(Optional.of(membership(user(1L, 9999L, "u"), c)));
        given(userChatRoomRepository.existsByUserUserIdAndChatRoomChatRoomId(userId, 111L))
                .willReturn(true);

        // when
        Throwable thrown = catchThrowable(() -> chatRoomService.joinClubChatRoom(clubId, userId));

        // then
        assertThat(thrown).isInstanceOf(CustomException.class);
        assertThat(((CustomException) thrown).getErrorCode()).isEqualTo(ErrorCode.ALREADY_JOINED);

        then(userChatRoomRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("정기_모임에_참여할_시_채팅방에_자동_참여한다")
    void joinScheduleChatRoom() {
        // given
        Long scheduleId = 20L;
        Long userId = 1001L;

        Club c = club(10L, "모임A");
        Schedule sch = schedule(scheduleId, "정모A", c);
        ChatRoom scheduleRoom = room(222L, c, Type.SCHEDULE, scheduleId, sch);

        given(scheduleRepository.findById(scheduleId)).willReturn(Optional.of(sch));
        given(chatRoomRepository.findByTypeAndScheduleId(Type.SCHEDULE, scheduleId))
                .willReturn(Optional.of(scheduleRoom));
        given(userScheduleRepository.findByUserAndSchedule(
                argThat(u -> u != null && userId.equals(u.getUserId())),
                argThat(s -> s != null && scheduleId.equals(s.getScheduleId()))
        )).willReturn(Optional.of(
                UserSchedule.builder()
                        .user(user(userId, 9999L, "u"))
                        .schedule(sch)
                        .build()
        ));
        given(userChatRoomRepository.existsByUserUserIdAndChatRoomChatRoomId(userId, 222L))
                .willReturn(false);

        // when
        chatRoomService.joinScheduleChatRoom(scheduleId, userId);

        // then
        then(userChatRoomRepository).should().save(argThat(m ->
                m.getChatRoom().getChatRoomId().equals(222L)
                        && m.getUser().getUserId().equals(userId)
        ));
        then(userChatRoomRepository).shouldHaveNoMoreInteractions();
    }

    @Test
    @DisplayName("정기_모임에_참여하지_않는_회원이_채팅방에_참여할_시_예외가_발생한다")
    void joinScheduleChatRoomNotScheduleMember() {
        // given
        Long scheduleId = 20L;
        Long userId = 1001L;

        Club c = club(10L, "모임A");
        Schedule sch = schedule(scheduleId, "정모A", c);
        ChatRoom scheduleRoom = room(222L, c, Type.SCHEDULE, scheduleId, sch);

        given(scheduleRepository.findById(scheduleId)).willReturn(Optional.of(sch));
        given(chatRoomRepository.findByTypeAndScheduleId(Type.SCHEDULE, scheduleId))
                .willReturn(Optional.of(scheduleRoom));
        given(userScheduleRepository.findByUserAndSchedule(
                argThat(u -> u != null && userId.equals(u.getUserId())),
                argThat(s -> s != null && scheduleId.equals(s.getScheduleId()))
        )).willReturn(Optional.empty());

        // when
        Throwable thrown = catchThrowable(() -> chatRoomService.joinScheduleChatRoom(scheduleId, userId));

        // then
        assertThat(thrown).isInstanceOf(CustomException.class);
        assertThat(((CustomException) thrown).getErrorCode()).isEqualTo(ErrorCode.SCHEDULE_NOT_JOIN);
        then(userChatRoomRepository).shouldHaveNoInteractions();
    }


}