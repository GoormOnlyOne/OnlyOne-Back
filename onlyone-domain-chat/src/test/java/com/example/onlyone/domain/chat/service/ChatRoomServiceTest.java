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
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleStatus;
import com.example.onlyone.domain.schedule.entity.UserSchedule;
import com.example.onlyone.domain.schedule.repository.ScheduleRepository;
import com.example.onlyone.domain.schedule.repository.UserScheduleRepository;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatRoomService 단위 테스트")
class ChatRoomServiceTest {

    @InjectMocks private ChatRoomService chatRoomService;
    @Mock private ChatRoomRepository chatRoomRepository;
    @Mock private UserChatRoomRepository userChatRoomRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private ClubRepository clubRepository;
    @Mock private UserClubRepository userClubRepository;
    @Mock private UserService userService;
    @Mock private ScheduleRepository scheduleRepository;
    @Mock private UserScheduleRepository userScheduleRepository;
    @Mock private UserRepository userRepository;

    // ==================== fixtures ====================

    private User user(Long id, long kakaoId, String nickname) {
        return User.builder()
                .userId(id)
                .kakaoId(kakaoId)
                .nickname(nickname)
                .status(Status.ACTIVE)
                .build();
    }

    private Club club(Long id, String name) {
        return Club.builder()
                .clubId(id)
                .name(name)
                .userLimit(100)
                .description("desc")
                .build();
    }

    private ChatRoom chatRoom(Long id, Club club, ChatRoomType type, Long scheduleId, Schedule schedule) {
        return ChatRoom.builder()
                .chatRoomId(id)
                .club(club)
                .type(type)
                .scheduleId(scheduleId)
                .schedule(schedule)
                .build();
    }

    private Message message(Long id, ChatRoom room, User user, String text, LocalDateTime sentAt, boolean deleted) {
        return Message.builder()
                .messageId(id)
                .chatRoom(room)
                .user(user)
                .text(text)
                .sentAt(sentAt)
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

    private UserClub userClub(User user, Club club) {
        return UserClub.builder()
                .user(user)
                .club(club)
                .clubRole(ClubRole.MEMBER)
                .build();
    }

    // ==================== 채팅방 삭제 ====================

    @Nested
    @DisplayName("채팅방 삭제")
    class DeleteChatRoom {

        @Test
        @DisplayName("성공: 채팅방이 삭제된다")
        void success() {
            // given
            Club c = club(10L, "모임A");
            ChatRoom room = chatRoom(1L, c, ChatRoomType.CLUB, null, null);

            given(chatRoomRepository.findByChatRoomIdAndClubClubId(1L, 10L))
                    .willReturn(Optional.of(room));
            willDoNothing().given(chatRoomRepository).delete(room);

            // when
            chatRoomService.deleteChatRoom(1L, 10L);

            // then
            then(chatRoomRepository).should().findByChatRoomIdAndClubClubId(1L, 10L);
            then(chatRoomRepository).should().delete(room);
        }

        @Test
        @DisplayName("실패: 채팅방이 없으면 CHAT_ROOM_NOT_FOUND")
        void failNotFound() {
            // given
            given(chatRoomRepository.findByChatRoomIdAndClubClubId(1L, 10L))
                    .willReturn(Optional.empty());

            // when
            Throwable thrown = catchThrowable(() -> chatRoomService.deleteChatRoom(1L, 10L));

            // then
            assertThat(thrown).isInstanceOf(CustomException.class);
            assertThat(((CustomException) thrown).getErrorCode()).isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND);
            then(chatRoomRepository).should(never()).delete(any());
        }

        @Test
        @DisplayName("실패: 삭제 중 무결성 위반시 CHAT_ROOM_DELETE_FAILED")
        void failDataIntegrityViolation() {
            // given
            Club c = club(10L, "모임A");
            ChatRoom room = chatRoom(1L, c, ChatRoomType.CLUB, null, null);

            given(chatRoomRepository.findByChatRoomIdAndClubClubId(1L, 10L))
                    .willReturn(Optional.of(room));
            doThrow(new DataIntegrityViolationException("test")).when(chatRoomRepository).delete(any());

            // when
            Throwable thrown = catchThrowable(() -> chatRoomService.deleteChatRoom(1L, 10L));

            // then
            assertThat(thrown).isInstanceOf(CustomException.class);
            assertThat(((CustomException) thrown).getErrorCode()).isEqualTo(ErrorCode.CHAT_ROOM_DELETE_FAILED);
        }
    }

    // ==================== 모임 채팅방 목록 조회 ====================

    @Nested
    @DisplayName("모임 채팅방 목록 조회")
    class GetChatRoomsUserJoinedInClub {

        @Test
        @DisplayName("성공: 사용자가 참여한 채팅방 목록이 반환된다")
        void success() {
            // given
            User u = user(1L, 1001L, "유저A");
            Club c = club(10L, "모임A");

            ChatRoom clubRoom = chatRoom(101L, c, ChatRoomType.CLUB, null, null);
            Schedule sch = schedule(20L, "정모A", c);
            ChatRoom scheduleRoom = chatRoom(102L, c, ChatRoomType.SCHEDULE, 20L, sch);

            given(userService.getCurrentUser()).willReturn(u);
            given(clubRepository.findById(10L)).willReturn(Optional.of(c));
            given(userClubRepository.findByUserAndClub(u, c))
                    .willReturn(Optional.of(userClub(u, c)));
            given(chatRoomRepository.findChatRoomsByUserIdAndClubId(1L, 10L))
                    .willReturn(List.of(clubRoom, scheduleRoom));

            LocalDateTime now = LocalDateTime.now();
            Message lastMsg = message(5001L, clubRoom, u, "마지막 메시지", now, false);
            given(messageRepository.findLastMessagesByChatRoomIds(List.of(101L, 102L)))
                    .willReturn(List.of(lastMsg));

            // when
            List<ChatRoomResponse> result = chatRoomService.getChatRoomsUserJoinedInClub(10L);

            // then
            assertThat(result).hasSize(2);
            assertThat(result).extracting(ChatRoomResponse::chatRoomId)
                    .containsExactly(101L, 102L);

            then(userService).should().getCurrentUser();
            then(clubRepository).should().findById(10L);
            then(userClubRepository).should().findByUserAndClub(u, c);
            then(chatRoomRepository).should().findChatRoomsByUserIdAndClubId(1L, 10L);
            then(messageRepository).should().findLastMessagesByChatRoomIds(anyList());
        }

        @Test
        @DisplayName("실패: 모임이 없으면 CLUB_NOT_FOUND")
        void failClubNotFound() {
            // given
            User u = user(1L, 1001L, "유저A");

            given(userService.getCurrentUser()).willReturn(u);
            given(clubRepository.findById(10L)).willReturn(Optional.empty());

            // when
            Throwable thrown = catchThrowable(() -> chatRoomService.getChatRoomsUserJoinedInClub(10L));

            // then
            assertThat(thrown).isInstanceOf(CustomException.class);
            assertThat(((CustomException) thrown).getErrorCode()).isEqualTo(ErrorCode.CLUB_NOT_FOUND);
            then(chatRoomRepository).shouldHaveNoInteractions();
            then(messageRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("실패: 모임 미가입이면 CLUB_NOT_JOIN")
        void failClubNotJoin() {
            // given
            User u = user(1L, 1001L, "유저A");
            Club c = club(10L, "모임A");

            given(userService.getCurrentUser()).willReturn(u);
            given(clubRepository.findById(10L)).willReturn(Optional.of(c));
            given(userClubRepository.findByUserAndClub(u, c)).willReturn(Optional.empty());

            // when
            Throwable thrown = catchThrowable(() -> chatRoomService.getChatRoomsUserJoinedInClub(10L));

            // then
            assertThat(thrown).isInstanceOf(CustomException.class);
            assertThat(((CustomException) thrown).getErrorCode()).isEqualTo(ErrorCode.CLUB_NOT_JOIN);
            then(chatRoomRepository).shouldHaveNoInteractions();
            then(messageRepository).shouldHaveNoInteractions();
        }
    }

    // ==================== 모임 채팅방 참여 ====================

    @Nested
    @DisplayName("모임 채팅방 참여")
    class JoinClubChatRoom {

        @Test
        @DisplayName("성공: 모임 채팅방에 참여한다")
        void success() {
            // given
            Long clubId = 10L;
            Long userId = 1L;
            Club c = club(clubId, "모임A");
            ChatRoom clubRoom = chatRoom(101L, c, ChatRoomType.CLUB, null, null);
            User u = user(userId, 1001L, "유저A");

            given(clubRepository.findById(clubId)).willReturn(Optional.of(c));
            given(chatRoomRepository.findByTypeAndClub_ClubId(ChatRoomType.CLUB, clubId))
                    .willReturn(Optional.of(clubRoom));
            given(userClubRepository.findByUserAndClub(any(User.class), eq(c)))
                    .willReturn(Optional.of(userClub(u, c)));
            given(userChatRoomRepository.existsByUserUserIdAndChatRoomChatRoomId(userId, 101L))
                    .willReturn(false);

            // when
            chatRoomService.joinClubChatRoom(clubId, userId);

            // then
            then(userChatRoomRepository).should().save(argThat(ucr ->
                    ucr.getChatRoom().getChatRoomId().equals(101L)
                            && ucr.getUser().getUserId().equals(userId)
            ));
        }

        @Test
        @DisplayName("실패: 모임 미가입이면 CLUB_NOT_JOIN")
        void failClubNotJoin() {
            // given
            Long clubId = 10L;
            Long userId = 1L;
            Club c = club(clubId, "모임A");
            ChatRoom clubRoom = chatRoom(101L, c, ChatRoomType.CLUB, null, null);

            given(clubRepository.findById(clubId)).willReturn(Optional.of(c));
            given(chatRoomRepository.findByTypeAndClub_ClubId(ChatRoomType.CLUB, clubId))
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
        @DisplayName("실패: 이미 참여중이면 ALREADY_JOINED")
        void failAlreadyJoined() {
            // given
            Long clubId = 10L;
            Long userId = 1L;
            Club c = club(clubId, "모임A");
            ChatRoom clubRoom = chatRoom(101L, c, ChatRoomType.CLUB, null, null);
            User u = user(userId, 1001L, "유저A");

            given(clubRepository.findById(clubId)).willReturn(Optional.of(c));
            given(chatRoomRepository.findByTypeAndClub_ClubId(ChatRoomType.CLUB, clubId))
                    .willReturn(Optional.of(clubRoom));
            given(userClubRepository.findByUserAndClub(any(User.class), eq(c)))
                    .willReturn(Optional.of(userClub(u, c)));
            given(userChatRoomRepository.existsByUserUserIdAndChatRoomChatRoomId(userId, 101L))
                    .willReturn(true);

            // when
            Throwable thrown = catchThrowable(() -> chatRoomService.joinClubChatRoom(clubId, userId));

            // then
            assertThat(thrown).isInstanceOf(CustomException.class);
            assertThat(((CustomException) thrown).getErrorCode()).isEqualTo(ErrorCode.ALREADY_JOINED);
            then(userChatRoomRepository).should(never()).save(any());
        }
    }

    // ==================== 정기모임 채팅방 참여 ====================

    @Nested
    @DisplayName("정기모임 채팅방 참여")
    class JoinScheduleChatRoom {

        @Test
        @DisplayName("성공: 정기모임 채팅방에 참여한다")
        void success() {
            // given
            Long scheduleId = 20L;
            Long userId = 1L;
            Club c = club(10L, "모임A");
            Schedule sch = schedule(scheduleId, "정모A", c);
            ChatRoom scheduleRoom = chatRoom(201L, c, ChatRoomType.SCHEDULE, scheduleId, sch);

            given(scheduleRepository.findById(scheduleId)).willReturn(Optional.of(sch));
            given(chatRoomRepository.findByTypeAndScheduleId(ChatRoomType.SCHEDULE, scheduleId))
                    .willReturn(Optional.of(scheduleRoom));
            given(userScheduleRepository.findByUserAndSchedule(
                    argThat(u -> u != null && userId.equals(u.getUserId())),
                    argThat(s -> s != null && scheduleId.equals(s.getScheduleId()))
            )).willReturn(Optional.of(
                    UserSchedule.builder()
                            .user(user(userId, 1001L, "유저A"))
                            .schedule(sch)
                            .build()
            ));
            given(userChatRoomRepository.existsByUserUserIdAndChatRoomChatRoomId(userId, 201L))
                    .willReturn(false);

            // when
            chatRoomService.joinScheduleChatRoom(scheduleId, userId);

            // then
            then(userChatRoomRepository).should().save(argThat(ucr ->
                    ucr.getChatRoom().getChatRoomId().equals(201L)
                            && ucr.getUser().getUserId().equals(userId)
            ));
        }

        @Test
        @DisplayName("실패: 정기모임 미참여면 SCHEDULE_NOT_JOIN")
        void failScheduleNotJoin() {
            // given
            Long scheduleId = 20L;
            Long userId = 1L;
            Club c = club(10L, "모임A");
            Schedule sch = schedule(scheduleId, "정모A", c);
            ChatRoom scheduleRoom = chatRoom(201L, c, ChatRoomType.SCHEDULE, scheduleId, sch);

            given(scheduleRepository.findById(scheduleId)).willReturn(Optional.of(sch));
            given(chatRoomRepository.findByTypeAndScheduleId(ChatRoomType.SCHEDULE, scheduleId))
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

        @Test
        @DisplayName("실패: 이미 참여중이면 ALREADY_JOINED")
        void failAlreadyJoined() {
            // given
            Long scheduleId = 20L;
            Long userId = 1L;
            Club c = club(10L, "모임A");
            Schedule sch = schedule(scheduleId, "정모A", c);
            ChatRoom scheduleRoom = chatRoom(201L, c, ChatRoomType.SCHEDULE, scheduleId, sch);

            given(scheduleRepository.findById(scheduleId)).willReturn(Optional.of(sch));
            given(chatRoomRepository.findByTypeAndScheduleId(ChatRoomType.SCHEDULE, scheduleId))
                    .willReturn(Optional.of(scheduleRoom));
            given(userScheduleRepository.findByUserAndSchedule(
                    argThat(u -> u != null && userId.equals(u.getUserId())),
                    argThat(s -> s != null && scheduleId.equals(s.getScheduleId()))
            )).willReturn(Optional.of(
                    UserSchedule.builder()
                            .user(user(userId, 1001L, "유저A"))
                            .schedule(sch)
                            .build()
            ));
            given(userChatRoomRepository.existsByUserUserIdAndChatRoomChatRoomId(userId, 201L))
                    .willReturn(true);

            // when
            Throwable thrown = catchThrowable(() -> chatRoomService.joinScheduleChatRoom(scheduleId, userId));

            // then
            assertThat(thrown).isInstanceOf(CustomException.class);
            assertThat(((CustomException) thrown).getErrorCode()).isEqualTo(ErrorCode.ALREADY_JOINED);
            then(userChatRoomRepository).should(never()).save(any());
        }
    }
}
