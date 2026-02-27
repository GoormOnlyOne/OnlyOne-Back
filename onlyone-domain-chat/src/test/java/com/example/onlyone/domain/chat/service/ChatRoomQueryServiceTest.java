package com.example.onlyone.domain.chat.service;

import com.example.onlyone.domain.chat.dto.ChatRoomResponse;
import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.Message;
import com.example.onlyone.domain.chat.repository.ChatRoomRepository;
import com.example.onlyone.domain.chat.repository.MessageRepository;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.club.repository.UserClubRepository;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.domain.club.exception.ClubErrorCode;
import com.example.onlyone.global.exception.CustomException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static com.example.onlyone.domain.chat.fixture.ChatFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatRoomQueryService 단위 테스트")
class ChatRoomQueryServiceTest {

    @InjectMocks private ChatRoomQueryService chatRoomQueryService;
    @Mock private ChatRoomRepository chatRoomRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private ClubRepository clubRepository;
    @Mock private UserClubRepository userClubRepository;
    @Mock private UserService userService;

    @Nested
    @DisplayName("모임 채팅방 목록 조회")
    class GetChatRoomsUserJoinedInClub {

        @Test
        @DisplayName("성공: 사용자가 참여한 채팅방 목록이 반환된다")
        void success() {
            Long clubId = 10L, userId = 1L;
            User u = user(userId, 1001L, "유저A");
            Club c = club(clubId, "모임A");

            ChatRoom clubRoom = clubChatRoom(101L, c);
            Schedule sch = schedule(20L, "정모A", c);
            ChatRoom scheduleRoom = scheduleChatRoom(102L, c, 20L, sch);

            given(userService.getCurrentUserId()).willReturn(userId);
            given(clubRepository.existsById(clubId)).willReturn(true);
            given(userClubRepository.existsByUser_UserIdAndClub_ClubId(userId, clubId)).willReturn(true);
            given(chatRoomRepository.findChatRoomsByUserIdAndClubId(userId, clubId))
                    .willReturn(List.of(clubRoom, scheduleRoom));

            Message lastMsg = message(5001L, clubRoom, u, "마지막 메시지", LocalDateTime.now(), false);
            given(messageRepository.findLastMessagesByChatRoomIds(List.of(101L, 102L)))
                    .willReturn(List.of(lastMsg));

            List<ChatRoomResponse> result = chatRoomQueryService.getChatRoomsUserJoinedInClub(clubId);

            assertThat(result).hasSize(2);
            assertThat(result).extracting(ChatRoomResponse::chatRoomId)
                    .containsExactly(101L, 102L);
        }

        @Test
        @DisplayName("실패: 모임이 없으면 CLUB_NOT_FOUND")
        void failClubNotFound() {
            Long clubId = 10L, userId = 1L;
            User u = user(userId, 1001L, "유저A");

            given(userService.getCurrentUserId()).willReturn(userId);
            given(clubRepository.existsById(clubId)).willReturn(false);

            Throwable thrown = catchThrowable(() -> chatRoomQueryService.getChatRoomsUserJoinedInClub(clubId));

            assertThat(thrown).isInstanceOf(CustomException.class);
            assertThat(((CustomException) thrown).getErrorCode()).isEqualTo(ClubErrorCode.CLUB_NOT_FOUND);
        }

        @Test
        @DisplayName("실패: 모임 미가입이면 CLUB_NOT_JOIN")
        void failClubNotJoin() {
            Long clubId = 10L, userId = 1L;
            User u = user(userId, 1001L, "유저A");

            given(userService.getCurrentUserId()).willReturn(userId);
            given(clubRepository.existsById(clubId)).willReturn(true);
            given(userClubRepository.existsByUser_UserIdAndClub_ClubId(userId, clubId)).willReturn(false);

            Throwable thrown = catchThrowable(() -> chatRoomQueryService.getChatRoomsUserJoinedInClub(clubId));

            assertThat(thrown).isInstanceOf(CustomException.class);
            assertThat(((CustomException) thrown).getErrorCode()).isEqualTo(ClubErrorCode.CLUB_NOT_JOIN);
        }
    }
}
