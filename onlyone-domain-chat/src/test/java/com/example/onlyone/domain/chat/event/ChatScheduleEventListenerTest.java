package com.example.onlyone.domain.chat.event;

import com.example.onlyone.common.event.ScheduleCreatedEvent;
import com.example.onlyone.common.event.ScheduleDeletedEvent;
import com.example.onlyone.common.event.ScheduleJoinedEvent;
import com.example.onlyone.common.event.ScheduleLeftEvent;
import com.example.onlyone.domain.chat.entity.ChatRole;
import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.ChatRoomType;
import com.example.onlyone.domain.chat.repository.ChatRoomRepository;
import com.example.onlyone.domain.chat.service.ChatRoomCommandService;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatScheduleEventListener 단위 테스트")
class ChatScheduleEventListenerTest {

    @InjectMocks
    private ChatScheduleEventListener listener;

    @Mock private ChatRoomCommandService chatRoomCommandService;
    @Mock private ChatRoomRepository chatRoomRepository;
    @Mock private ClubRepository clubRepository;
    @Mock private UserRepository userRepository;

    private Club club;
    private User leader;
    private User member;
    private ChatRoom chatRoom;

    @BeforeEach
    void setUp() {
        club = Club.builder().clubId(1L).name("테스트 모임").build();
        leader = User.builder().userId(1L).nickname("리더").build();
        member = User.builder().userId(2L).nickname("멤버").build();
        chatRoom = ChatRoom.builder()
                .chatRoomId(100L)
                .club(club)
                .scheduleId(10L)
                .type(ChatRoomType.SCHEDULE)
                .build();
    }

    @Nested
    @DisplayName("ScheduleCreatedEvent 처리")
    class HandleCreated {

        @Test
        @DisplayName("성공: 채팅방 생성 + 리더 추가")
        void 채팅방_생성_및_리더_추가() {
            ScheduleCreatedEvent event = new ScheduleCreatedEvent(10L, 1L, 1L, "정기 모임", LocalDateTime.now());
            given(clubRepository.findById(1L)).willReturn(Optional.of(club));
            given(userRepository.findById(1L)).willReturn(Optional.of(leader));
            given(chatRoomCommandService.createChatRoom(club, ChatRoomType.SCHEDULE, 10L))
                    .willReturn(chatRoom);

            listener.handleScheduleCreatedEvent(event);

            then(chatRoomCommandService).should().createChatRoom(club, ChatRoomType.SCHEDULE, 10L);
            then(chatRoomCommandService).should().addMember(chatRoom, leader, ChatRole.LEADER);
        }
    }

    @Nested
    @DisplayName("ScheduleJoinedEvent 처리")
    class HandleJoined {

        @Test
        @DisplayName("성공: 참여자를 채팅방에 추가")
        void 참여자_채팅방_추가() {
            ScheduleJoinedEvent event = new ScheduleJoinedEvent(10L, 1L, 2L, 5000L);
            given(chatRoomRepository.findByTypeAndScheduleId(ChatRoomType.SCHEDULE, 10L))
                    .willReturn(Optional.of(chatRoom));
            given(userRepository.findById(2L)).willReturn(Optional.of(member));

            listener.handleScheduleJoinedEvent(event);

            then(chatRoomCommandService).should().addMember(chatRoom, member, ChatRole.MEMBER);
        }
    }

    @Nested
    @DisplayName("ScheduleLeftEvent 처리")
    class HandleLeft {

        @Test
        @DisplayName("성공: 참여자를 채팅방에서 제거")
        void 참여자_채팅방_제거() {
            ScheduleLeftEvent event = new ScheduleLeftEvent(10L, 1L, 2L);
            given(chatRoomRepository.findByTypeAndScheduleId(ChatRoomType.SCHEDULE, 10L))
                    .willReturn(Optional.of(chatRoom));

            listener.handleScheduleLeftEvent(event);

            then(chatRoomCommandService).should().removeMember(2L, 100L);
        }
    }

    @Nested
    @DisplayName("ScheduleDeletedEvent 처리")
    class HandleDeleted {

        @Test
        @DisplayName("성공: 채팅방 삭제 (cascade)")
        void 채팅방_삭제() {
            ScheduleDeletedEvent event = new ScheduleDeletedEvent(10L, 1L);

            listener.handleScheduleDeletedEvent(event);

            then(chatRoomCommandService).should().deleteChatRoomBySchedule(10L);
        }

        @Test
        @DisplayName("실패: 채팅방 미존재 시 예외")
        void 채팅방_미존재_예외() {
            ScheduleDeletedEvent event = new ScheduleDeletedEvent(999L, 1L);
            willThrow(new IllegalArgumentException("ChatRoom not found for scheduleId: 999"))
                    .given(chatRoomCommandService).deleteChatRoomBySchedule(999L);

            assertThatThrownBy(() -> listener.handleScheduleDeletedEvent(event))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
