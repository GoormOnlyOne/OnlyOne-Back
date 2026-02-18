package com.example.onlyone.domain.chat.service;

import com.example.onlyone.domain.chat.entity.ChatRole;
import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.ChatRoomType;
import com.example.onlyone.domain.chat.entity.UserChatRoom;
import com.example.onlyone.domain.chat.repository.ChatRoomRepository;
import com.example.onlyone.domain.chat.repository.UserChatRoomRepository;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.UserSchedule;
import com.example.onlyone.domain.schedule.repository.ScheduleRepository;
import com.example.onlyone.domain.schedule.repository.UserScheduleRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
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

import java.util.Optional;

import static com.example.onlyone.domain.chat.fixture.ChatFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatRoomCommandService 단위 테스트")
class ChatRoomCommandServiceTest {

    @InjectMocks private ChatRoomCommandService chatRoomCommandService;
    @Mock private ChatRoomRepository chatRoomRepository;
    @Mock private UserChatRoomRepository userChatRoomRepository;
    @Mock private ClubRepository clubRepository;
    @Mock private UserClubRepository userClubRepository;
    @Mock private ScheduleRepository scheduleRepository;
    @Mock private UserScheduleRepository userScheduleRepository;
    @Mock private UserRepository userRepository;

    // ==================== 채팅방 삭제 ====================

    @Nested
    @DisplayName("채팅방 삭제")
    class DeleteChatRoom {

        @Test
        @DisplayName("성공: 채팅방이 삭제된다")
        void success() {
            Club c = club(10L, "모임A");
            ChatRoom room = clubChatRoom(1L, c);

            given(chatRoomRepository.findByChatRoomIdAndClubClubId(1L, 10L))
                    .willReturn(Optional.of(room));
            willDoNothing().given(chatRoomRepository).delete(room);

            chatRoomCommandService.deleteChatRoom(1L, 10L);

            then(chatRoomRepository).should().delete(room);
        }

        @Test
        @DisplayName("실패: 채팅방이 없으면 CHAT_ROOM_NOT_FOUND")
        void failNotFound() {
            given(chatRoomRepository.findByChatRoomIdAndClubClubId(1L, 10L))
                    .willReturn(Optional.empty());

            Throwable thrown = catchThrowable(() -> chatRoomCommandService.deleteChatRoom(1L, 10L));

            assertThat(thrown).isInstanceOf(CustomException.class);
            assertThat(((CustomException) thrown).getErrorCode()).isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND);
        }

        @Test
        @DisplayName("실패: 삭제 중 무결성 위반시 CHAT_ROOM_DELETE_FAILED")
        void failDataIntegrityViolation() {
            Club c = club(10L, "모임A");
            ChatRoom room = clubChatRoom(1L, c);

            given(chatRoomRepository.findByChatRoomIdAndClubClubId(1L, 10L))
                    .willReturn(Optional.of(room));
            doThrow(new DataIntegrityViolationException("test")).when(chatRoomRepository).delete(any());

            Throwable thrown = catchThrowable(() -> chatRoomCommandService.deleteChatRoom(1L, 10L));

            assertThat(thrown).isInstanceOf(CustomException.class);
            assertThat(((CustomException) thrown).getErrorCode()).isEqualTo(ErrorCode.CHAT_ROOM_DELETE_FAILED);
        }
    }

    // ==================== 모임 채팅방 참여 ====================

    @Nested
    @DisplayName("모임 채팅방 참여")
    class JoinClubChatRoom {

        @Test
        @DisplayName("성공: 모임 채팅방에 참여한다")
        void success() {
            Long clubId = 10L;
            Long userId = 1L;
            Club c = club(clubId, "모임A");
            ChatRoom clubRoom = clubChatRoom(101L, c);
            User u = user(userId, 1001L, "유저A");

            given(clubRepository.findById(clubId)).willReturn(Optional.of(c));
            given(chatRoomRepository.findByTypeAndClub_ClubId(ChatRoomType.CLUB, clubId))
                    .willReturn(Optional.of(clubRoom));
            given(userClubRepository.findByUserAndClub(any(User.class), eq(c)))
                    .willReturn(Optional.of(userClub(u, c)));
            given(userChatRoomRepository.existsByUserUserIdAndChatRoomChatRoomId(userId, 101L))
                    .willReturn(false);

            chatRoomCommandService.joinClubChatRoom(clubId, userId);

            then(userChatRoomRepository).should().save(argThat(ucr ->
                    ucr.getChatRoom().getChatRoomId().equals(101L)
                            && ucr.getUser().getUserId().equals(userId)
            ));
        }

        @Test
        @DisplayName("실패: 모임 미가입이면 CLUB_NOT_JOIN")
        void failClubNotJoin() {
            Long clubId = 10L;
            Long userId = 1L;
            Club c = club(clubId, "모임A");
            ChatRoom clubRoom = clubChatRoom(101L, c);

            given(clubRepository.findById(clubId)).willReturn(Optional.of(c));
            given(chatRoomRepository.findByTypeAndClub_ClubId(ChatRoomType.CLUB, clubId))
                    .willReturn(Optional.of(clubRoom));
            given(userClubRepository.findByUserAndClub(any(User.class), eq(c)))
                    .willReturn(Optional.empty());

            Throwable thrown = catchThrowable(() -> chatRoomCommandService.joinClubChatRoom(clubId, userId));

            assertThat(thrown).isInstanceOf(CustomException.class);
            assertThat(((CustomException) thrown).getErrorCode()).isEqualTo(ErrorCode.CLUB_NOT_JOIN);
        }

        @Test
        @DisplayName("실패: 이미 참여중이면 ALREADY_JOINED")
        void failAlreadyJoined() {
            Long clubId = 10L;
            Long userId = 1L;
            Club c = club(clubId, "모임A");
            ChatRoom clubRoom = clubChatRoom(101L, c);
            User u = user(userId, 1001L, "유저A");

            given(clubRepository.findById(clubId)).willReturn(Optional.of(c));
            given(chatRoomRepository.findByTypeAndClub_ClubId(ChatRoomType.CLUB, clubId))
                    .willReturn(Optional.of(clubRoom));
            given(userClubRepository.findByUserAndClub(any(User.class), eq(c)))
                    .willReturn(Optional.of(userClub(u, c)));
            given(userChatRoomRepository.existsByUserUserIdAndChatRoomChatRoomId(userId, 101L))
                    .willReturn(true);

            Throwable thrown = catchThrowable(() -> chatRoomCommandService.joinClubChatRoom(clubId, userId));

            assertThat(thrown).isInstanceOf(CustomException.class);
            assertThat(((CustomException) thrown).getErrorCode()).isEqualTo(ErrorCode.ALREADY_JOINED);
        }
    }

    // ==================== 정기모임 채팅방 참여 ====================

    @Nested
    @DisplayName("정기모임 채팅방 참여")
    class JoinScheduleChatRoom {

        @Test
        @DisplayName("성공: 정기모임 채팅방에 참여한다")
        void success() {
            Long scheduleId = 20L;
            Long userId = 1L;
            Club c = club(10L, "모임A");
            Schedule sch = schedule(scheduleId, "정모A", c);
            ChatRoom scheduleRoom = scheduleChatRoom(201L, c, scheduleId, sch);

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

            chatRoomCommandService.joinScheduleChatRoom(scheduleId, userId);

            then(userChatRoomRepository).should().save(argThat(ucr ->
                    ucr.getChatRoom().getChatRoomId().equals(201L)
                            && ucr.getUser().getUserId().equals(userId)
            ));
        }

        @Test
        @DisplayName("실패: 정기모임 미참여면 SCHEDULE_NOT_JOIN")
        void failScheduleNotJoin() {
            Long scheduleId = 20L;
            Long userId = 1L;
            Club c = club(10L, "모임A");
            Schedule sch = schedule(scheduleId, "정모A", c);
            scheduleChatRoom(201L, c, scheduleId, sch);

            given(scheduleRepository.findById(scheduleId)).willReturn(Optional.of(sch));
            given(chatRoomRepository.findByTypeAndScheduleId(ChatRoomType.SCHEDULE, scheduleId))
                    .willReturn(Optional.of(scheduleChatRoom(201L, c, scheduleId, sch)));
            given(userScheduleRepository.findByUserAndSchedule(
                    argThat(u -> u != null && userId.equals(u.getUserId())),
                    argThat(s -> s != null && scheduleId.equals(s.getScheduleId()))
            )).willReturn(Optional.empty());

            Throwable thrown = catchThrowable(() -> chatRoomCommandService.joinScheduleChatRoom(scheduleId, userId));

            assertThat(thrown).isInstanceOf(CustomException.class);
            assertThat(((CustomException) thrown).getErrorCode()).isEqualTo(ErrorCode.SCHEDULE_NOT_JOIN);
        }

        @Test
        @DisplayName("실패: 이미 참여중이면 ALREADY_JOINED")
        void failAlreadyJoined() {
            Long scheduleId = 20L;
            Long userId = 1L;
            Club c = club(10L, "모임A");
            Schedule sch = schedule(scheduleId, "정모A", c);
            ChatRoom scheduleRoom = scheduleChatRoom(201L, c, scheduleId, sch);

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

            Throwable thrown = catchThrowable(() -> chatRoomCommandService.joinScheduleChatRoom(scheduleId, userId));

            assertThat(thrown).isInstanceOf(CustomException.class);
            assertThat(((CustomException) thrown).getErrorCode()).isEqualTo(ErrorCode.ALREADY_JOINED);
        }
    }

    // ==================== 채팅방 생성 (이벤트 리스너용) ====================

    @Nested
    @DisplayName("채팅방 생성")
    class CreateChatRoom {

        @Test
        @DisplayName("성공: CLUB 타입 채팅방이 생성된다")
        void successClubType() {
            Club c = club(10L, "모임A");
            given(chatRoomRepository.save(any(ChatRoom.class))).willAnswer(inv -> inv.getArgument(0));

            ChatRoom result = chatRoomCommandService.createChatRoom(c, ChatRoomType.CLUB, null);

            assertThat(result.getType()).isEqualTo(ChatRoomType.CLUB);
            assertThat(result.getClub().getClubId()).isEqualTo(10L);
            assertThat(result.getScheduleId()).isNull();
        }

        @Test
        @DisplayName("성공: SCHEDULE 타입 채팅방이 scheduleId와 함께 생성된다")
        void successScheduleType() {
            Club c = club(10L, "모임A");
            given(chatRoomRepository.save(any(ChatRoom.class))).willAnswer(inv -> inv.getArgument(0));

            ChatRoom result = chatRoomCommandService.createChatRoom(c, ChatRoomType.SCHEDULE, 20L);

            assertThat(result.getType()).isEqualTo(ChatRoomType.SCHEDULE);
            assertThat(result.getScheduleId()).isEqualTo(20L);
        }
    }

    // ==================== 멤버 추가/제거 (이벤트 리스너용) ====================

    @Nested
    @DisplayName("멤버 추가")
    class AddMember {

        @Test
        @DisplayName("성공: 채팅방에 멤버가 추가된다")
        void success() {
            Club c = club(10L, "모임A");
            ChatRoom room = clubChatRoom(101L, c);
            User u = user(1L, 1001L, "유저A");

            chatRoomCommandService.addMember(room, u, ChatRole.MEMBER);

            then(userChatRoomRepository).should().save(argThat(ucr ->
                    ucr.getChatRoom().getChatRoomId().equals(101L)
                            && ucr.getUser().getUserId().equals(1L)
                            && ucr.getChatRole() == ChatRole.MEMBER
            ));
        }

        @Test
        @DisplayName("성공: LEADER 역할로 추가된다")
        void successWithLeaderRole() {
            Club c = club(10L, "모임A");
            ChatRoom room = clubChatRoom(101L, c);
            User u = user(1L, 1001L, "리더");

            chatRoomCommandService.addMember(room, u, ChatRole.LEADER);

            then(userChatRoomRepository).should().save(argThat(ucr ->
                    ucr.getChatRole() == ChatRole.LEADER
            ));
        }
    }

    @Nested
    @DisplayName("멤버 제거")
    class RemoveMember {

        @Test
        @DisplayName("성공: 채팅방에서 멤버가 제거된다")
        void success() {
            User u = user(2L, 1002L, "멤버");
            Club c = club(10L, "모임A");
            ChatRoom room = clubChatRoom(101L, c);
            UserChatRoom ucr = UserChatRoom.builder()
                    .user(u).chatRoom(room).chatRole(ChatRole.MEMBER).build();

            given(userChatRoomRepository.findByUserUserIdAndChatRoomChatRoomId(2L, 101L))
                    .willReturn(Optional.of(ucr));

            chatRoomCommandService.removeMember(2L, 101L);

            then(userChatRoomRepository).should().delete(ucr);
        }

        @Test
        @DisplayName("실패: 참여 정보가 없으면 IllegalArgumentException")
        void failNotFound() {
            given(userChatRoomRepository.findByUserUserIdAndChatRoomChatRoomId(999L, 101L))
                    .willReturn(Optional.empty());

            Throwable thrown = catchThrowable(() -> chatRoomCommandService.removeMember(999L, 101L));

            assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ==================== 스케줄 채팅방 삭제 ====================

    @Nested
    @DisplayName("스케줄 채팅방 삭제")
    class DeleteChatRoomBySchedule {

        @Test
        @DisplayName("성공: 스케줄 채팅방이 삭제된다")
        void success() {
            Club c = club(10L, "모임A");
            Schedule sch = schedule(20L, "정모A", c);
            ChatRoom room = scheduleChatRoom(201L, c, 20L, sch);

            given(chatRoomRepository.findByTypeAndScheduleId(ChatRoomType.SCHEDULE, 20L))
                    .willReturn(Optional.of(room));

            chatRoomCommandService.deleteChatRoomBySchedule(20L);

            then(chatRoomRepository).should().delete(room);
        }

        @Test
        @DisplayName("실패: 채팅방 미존재시 IllegalArgumentException")
        void failNotFound() {
            given(chatRoomRepository.findByTypeAndScheduleId(ChatRoomType.SCHEDULE, 999L))
                    .willReturn(Optional.empty());

            Throwable thrown = catchThrowable(() -> chatRoomCommandService.deleteChatRoomBySchedule(999L));

            assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
