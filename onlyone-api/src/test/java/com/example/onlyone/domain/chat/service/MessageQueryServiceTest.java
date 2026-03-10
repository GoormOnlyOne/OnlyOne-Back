package com.example.onlyone.domain.chat.service;

import com.example.onlyone.domain.chat.dto.ChatMessageItemDto;
import com.example.onlyone.domain.chat.dto.ChatRoomMessageResponse;
import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.port.ChatMessageStoragePort;
import com.example.onlyone.domain.chat.repository.ChatRoomRepository;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.schedule.entity.Schedule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.example.onlyone.domain.chat.fixture.ChatFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("MessageQueryService 단위 테스트")
class MessageQueryServiceTest {

    @InjectMocks private MessageQueryService messageQueryService;
    @Mock private ChatMessageStoragePort chatMessageStoragePort;
    @Mock private ChatRoomRepository chatRoomRepository;

    private ChatMessageItemDto itemDto(Long id, Long chatRoomId, String text) {
        return new ChatMessageItemDto(id, chatRoomId, DEFAULT_USER_ID,
                "테스트유저", "https://example.com/profile.jpg", text,
                DEFAULT_SENT_AT, false);
    }

    @Nested
    @DisplayName("채팅방 메시지 조회")
    class GetChatRoomMessages {

        @Test
        @DisplayName("성공: 초기 로드시 최신 메시지가 반환된다")
        void getChatRoomMessages_initialLoad_success() {
            Club c = club();
            ChatRoom chatRoom = clubChatRoom(1L, c);

            ChatMessageItemDto item1 = itemDto(1L, 1L, "메시지1");
            ChatMessageItemDto item2 = itemDto(2L, 1L, "메시지2");
            ChatMessageItemDto item3 = itemDto(3L, 1L, "메시지3");
            // findLatest returns DESC order
            List<ChatMessageItemDto> descItems = new ArrayList<>(List.of(item3, item2, item1));

            given(chatRoomRepository.findByIdWithClub(1L)).willReturn(Optional.of(chatRoom));
            given(chatMessageStoragePort.findLatest(eq(1L), anyInt())).willReturn(descItems);

            ChatRoomMessageResponse response = messageQueryService.getChatRoomMessages(1L, 50, null, null);

            assertThat(response.chatRoomId()).isEqualTo(1L);
            assertThat(response.chatRoomName()).isEqualTo("테스트모임");
            assertThat(response.hasMore()).isFalse();
            assertThat(response.messages()).hasSize(3);
            // After reverse: ASC order
            assertThat(response.messages().get(0).messageId()).isEqualTo(1L);
            assertThat(response.messages().get(2).messageId()).isEqualTo(3L);
        }

        @Test
        @DisplayName("성공: 커서 기반 조회시 이전 메시지가 반환된다")
        void getChatRoomMessages_cursorBased_success() {
            Club c = club();
            Schedule sch = schedule(1L, "정기모임A", c);
            ChatRoom chatRoom = scheduleChatRoom(2L, c, 1L, sch);

            ChatMessageItemDto item1 = itemDto(1L, 2L, "이전메시지1");
            ChatMessageItemDto item2 = itemDto(2L, 2L, "이전메시지2");
            List<ChatMessageItemDto> descItems = new ArrayList<>(List.of(item2, item1));

            LocalDateTime cursorAt = LocalDateTime.of(2025, 7, 29, 12, 0, 0);

            given(chatRoomRepository.findByIdWithClub(2L)).willReturn(Optional.of(chatRoom));
            given(chatMessageStoragePort.findOlderThan(eq(2L), eq(cursorAt), eq(5L), anyInt()))
                    .willReturn(descItems);

            ChatRoomMessageResponse response = messageQueryService.getChatRoomMessages(2L, 50, 5L, cursorAt);

            assertThat(response.chatRoomId()).isEqualTo(2L);
            assertThat(response.chatRoomName()).isEqualTo("정기모임A");
            assertThat(response.hasMore()).isFalse();
            assertThat(response.messages()).hasSize(2);
            assertThat(response.messages().get(0).messageId()).isEqualTo(1L);
            assertThat(response.messages().get(1).messageId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("성공: hasMore가 올바르게 설정된다")
        void getChatRoomMessages_hasMore_true() {
            Club c = club();
            ChatRoom chatRoom = clubChatRoom(1L, c);

            int size = 2;
            ChatMessageItemDto item1 = itemDto(1L, 1L, "메시지1");
            ChatMessageItemDto item2 = itemDto(2L, 1L, "메시지2");
            ChatMessageItemDto item3 = itemDto(3L, 1L, "메시지3");
            // pageSize+1 = 3 items returned → hasMore = true
            List<ChatMessageItemDto> descItems = new ArrayList<>(List.of(item3, item2, item1));

            given(chatRoomRepository.findByIdWithClub(1L)).willReturn(Optional.of(chatRoom));
            given(chatMessageStoragePort.findLatest(eq(1L), anyInt())).willReturn(descItems);

            ChatRoomMessageResponse response = messageQueryService.getChatRoomMessages(1L, size, null, null);

            assertThat(response.hasMore()).isTrue();
            assertThat(response.messages()).hasSize(2);
            // After subList(0,2) → [item3, item2], then reverse → [item2, item3]
            assertThat(response.messages().get(0).messageId()).isEqualTo(2L);
            assertThat(response.messages().get(1).messageId()).isEqualTo(3L);
            assertThat(response.nextCursorId()).isEqualTo(2L);
        }
    }
}
