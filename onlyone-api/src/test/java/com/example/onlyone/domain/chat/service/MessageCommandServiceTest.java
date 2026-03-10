package com.example.onlyone.domain.chat.service;

import com.example.onlyone.domain.chat.dto.ChatMessageItemDto;
import com.example.onlyone.domain.chat.dto.ChatMessageResponse;
import com.example.onlyone.domain.chat.port.ChatMessageStoragePort;
import com.example.onlyone.domain.chat.repository.UserChatRoomRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.domain.chat.exception.ChatErrorCode;
import com.example.onlyone.domain.user.exception.UserErrorCode;
import com.example.onlyone.global.exception.CustomException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.core.JsonProcessingException;

import java.time.LocalDateTime;
import java.util.Optional;

import static com.example.onlyone.domain.chat.fixture.ChatFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MessageCommandService 단위 테스트")
class MessageCommandServiceTest {

    @InjectMocks private MessageCommandService messageCommandService;
    @Mock private ChatMessageStoragePort chatMessageStoragePort;
    @Mock private UserRepository userRepository;
    @Mock private UserChatRoomRepository userChatRoomRepository;
    @Mock private ChatPublisher chatPublisher;
    @Mock private ObjectMapper objectMapper;

    private ChatMessageItemDto stubItem(Long messageId, Long chatRoomId, String text) {
        return new ChatMessageItemDto(messageId, chatRoomId, DEFAULT_USER_ID,
                "테스트유저", "https://example.com/profile.jpg", text,
                DEFAULT_SENT_AT, false);
    }

    private void stubSaveReturningItem(Long savedMessageId, Long chatRoomId) {
        given(chatMessageStoragePort.save(eq(chatRoomId), eq(DEFAULT_USER_ID),
                anyString(), any(), anyString(), any(LocalDateTime.class)))
                .willAnswer(invocation -> new ChatMessageItemDto(
                        savedMessageId, chatRoomId, DEFAULT_USER_ID,
                        invocation.getArgument(2), invocation.getArgument(3),
                        invocation.getArgument(4),
                        invocation.getArgument(5), false));
    }

    private void stubCommonSaveMessageDependencies(User user) {
        given(userChatRoomRepository.existsByUserUserIdAndChatRoomChatRoomId(
                eq(user.getUserId()), anyLong())).willReturn(true);
        given(userRepository.findById(user.getUserId())).willReturn(Optional.of(user));
    }

    // ========== sendAndPublish 테스트 ==========

    @Nested
    @DisplayName("메시지 저장 + 발행")
    class SendAndPublish {

        @Test
        @DisplayName("성공: 메시지 저장 후 Redis 발행된다")
        void success() throws Exception {
            User user = user();
            stubCommonSaveMessageDependencies(user);
            stubSaveReturningItem(10L, 1L);
            given(objectMapper.writeValueAsString(any())).willReturn("{\"messageId\":10}");

            ChatMessageResponse response =
                    messageCommandService.sendAndPublish(1L, DEFAULT_USER_ID, "안녕하세요!");

            assertThat(response.messageId()).isEqualTo(10L);
            then(chatPublisher).should().publish(eq(1L), eq("{\"messageId\":10}"));
        }

        @Test
        @DisplayName("실패: JSON 직렬화 실패시 MESSAGE_SERVER_ERROR")
        void failJsonSerialization() throws Exception {
            User user = user();
            stubCommonSaveMessageDependencies(user);
            stubSaveReturningItem(10L, 1L);
            given(objectMapper.writeValueAsString(any()))
                    .willThrow(new JsonProcessingException("fail") {});

            assertThatThrownBy(() ->
                    messageCommandService.sendAndPublish(1L, DEFAULT_USER_ID, "테스트"))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ChatErrorCode.MESSAGE_SERVER_ERROR);
        }
    }

    // ========== publishImmediately 테스트 ==========

    @Nested
    @DisplayName("즉시 발행 (WebSocket)")
    class PublishImmediately {

        @Test
        @DisplayName("성공: DB 저장 없이 Redis 발행만 수행된다")
        void success() throws Exception {
            given(objectMapper.writeValueAsString(any())).willReturn("{\"text\":\"hello\"}");

            messageCommandService.publishImmediately(
                    1L, DEFAULT_USER_ID, "테스트유저", null, "hello");

            then(chatPublisher).should().publish(eq(1L), eq("{\"text\":\"hello\"}"));
            then(chatMessageStoragePort).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("성공: 이미지 메시지도 올바르게 발행된다")
        void successWithImage() throws Exception {
            given(objectMapper.writeValueAsString(any())).willReturn("{\"imageUrl\":\"url\"}");

            messageCommandService.publishImmediately(
                    1L, DEFAULT_USER_ID, "테스트유저", null, "IMAGE::https://cdn.example.com/img.jpg");

            then(chatPublisher).should().publish(eq(1L), eq("{\"imageUrl\":\"url\"}"));
            then(chatMessageStoragePort).shouldHaveNoInteractions();
        }
    }

    // ========== 메시지 저장 테스트 ==========

    @Nested
    @DisplayName("메시지 저장")
    class SaveMessage {

        @Test
        @DisplayName("성공: 텍스트 메시지가 저장된다")
        void saveTextMessage_success() {
            User user = user();
            stubCommonSaveMessageDependencies(user);
            stubSaveReturningItem(10L, 1L);

            ChatMessageResponse response = messageCommandService.saveMessage(1L, DEFAULT_USER_ID, "안녕하세요!");

            assertThat(response.messageId()).isEqualTo(10L);
            assertThat(response.chatRoomId()).isEqualTo(1L);
            assertThat(response.senderId()).isEqualTo(DEFAULT_USER_ID);
            assertThat(response.senderNickname()).isEqualTo("테스트유저");
            assertThat(response.text()).isEqualTo("안녕하세요!");
            assertThat(response.imageUrl()).isNull();
            assertThat(response.deleted()).isFalse();
            verify(chatMessageStoragePort).save(eq(1L), eq(DEFAULT_USER_ID),
                    anyString(), any(), eq("안녕하세요!"), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("성공: 이미지 메시지가 저장된다")
        void saveImageMessage_success() {
            User user = user();
            stubCommonSaveMessageDependencies(user);
            // For image messages, the stored text is "IMAGE::https://example.com/img.png"
            given(chatMessageStoragePort.save(eq(1L), eq(DEFAULT_USER_ID),
                    anyString(), any(), eq("IMAGE::https://example.com/img.png"), any(LocalDateTime.class)))
                    .willReturn(new ChatMessageItemDto(
                            11L, 1L, DEFAULT_USER_ID, "테스트유저",
                            "https://example.com/profile.jpg",
                            "IMAGE::https://example.com/img.png",
                            DEFAULT_SENT_AT, false));

            ChatMessageResponse response = messageCommandService.saveMessage(1L, DEFAULT_USER_ID,
                    "IMAGE::https://example.com/img.png");

            assertThat(response.messageId()).isEqualTo(11L);
            assertThat(response.text()).isNull();
            assertThat(response.imageUrl()).isEqualTo("https://example.com/img.png");
            assertThat(response.deleted()).isFalse();
        }

        @Test
        @DisplayName("성공: 2000자 초과 텍스트가 잘린다")
        void saveMessage_truncatesAt2000() {
            User user = user();
            stubCommonSaveMessageDependencies(user);
            String longText = "a".repeat(2500);
            String truncated = "a".repeat(2000);

            given(chatMessageStoragePort.save(eq(1L), eq(DEFAULT_USER_ID),
                    anyString(), any(), eq(truncated), any(LocalDateTime.class)))
                    .willReturn(new ChatMessageItemDto(
                            12L, 1L, DEFAULT_USER_ID, "테스트유저",
                            "https://example.com/profile.jpg",
                            truncated, DEFAULT_SENT_AT, false));

            ChatMessageResponse response = messageCommandService.saveMessage(1L, DEFAULT_USER_ID, longText);

            assertThat(response.text()).hasSize(2000);
        }

        @Test
        @DisplayName("실패: 빈 텍스트면 MESSAGE_BAD_REQUEST")
        void saveMessage_blankText_throwsException() {
            assertThatThrownBy(() -> messageCommandService.saveMessage(1L, DEFAULT_USER_ID, "   "))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ChatErrorCode.MESSAGE_BAD_REQUEST);
        }

        @Test
        @DisplayName("실패: null 텍스트면 MESSAGE_BAD_REQUEST")
        void saveMessage_nullText_throwsException() {
            assertThatThrownBy(() -> messageCommandService.saveMessage(1L, DEFAULT_USER_ID, null))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ChatErrorCode.MESSAGE_BAD_REQUEST);
        }

        @Test
        @DisplayName("실패: 존재하지 않는 채팅방이면 FORBIDDEN_CHAT_ROOM (멤버십 검증 fail-fast)")
        void saveMessage_chatRoomNotFound_throwsException() {
            given(userChatRoomRepository.existsByUserUserIdAndChatRoomChatRoomId(DEFAULT_USER_ID, 999L))
                    .willReturn(false);

            assertThatThrownBy(() -> messageCommandService.saveMessage(999L, DEFAULT_USER_ID, "메시지"))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ChatErrorCode.FORBIDDEN_CHAT_ROOM);
        }

        @Test
        @DisplayName("실패: 사용자가 없으면 USER_NOT_FOUND")
        void saveMessage_userNotFound_throwsException() {
            given(userChatRoomRepository.existsByUserUserIdAndChatRoomChatRoomId(999L, 1L))
                    .willReturn(true);
            given(userRepository.findById(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> messageCommandService.saveMessage(1L, 999L, "메시지"))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(UserErrorCode.USER_NOT_FOUND);
        }

        @Test
        @DisplayName("실패: 채팅방 미참여면 FORBIDDEN_CHAT_ROOM")
        void saveMessage_notJoined_throwsException() {
            given(userChatRoomRepository.existsByUserUserIdAndChatRoomChatRoomId(DEFAULT_USER_ID, 1L))
                    .willReturn(false);

            assertThatThrownBy(() -> messageCommandService.saveMessage(1L, DEFAULT_USER_ID, "메시지"))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ChatErrorCode.FORBIDDEN_CHAT_ROOM);
        }

        @Test
        @DisplayName("실패: 이미지 확장자 유효하지 않으면 INVALID_IMAGE_CONTENT_TYPE")
        void saveMessage_invalidImageExtension_throwsException() {
            User user = user();
            stubCommonSaveMessageDependencies(user);

            assertThatThrownBy(() -> messageCommandService.saveMessage(1L, DEFAULT_USER_ID, "IMAGE::https://example.com/file.gif"))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ChatErrorCode.INVALID_IMAGE_CONTENT_TYPE);
        }

        @Test
        @DisplayName("실패: 이미지 URL에 쉼표가 있으면 MESSAGE_BAD_REQUEST")
        void saveMessage_imageUrlWithComma_throwsException() {
            User user = user();
            stubCommonSaveMessageDependencies(user);

            assertThatThrownBy(() -> messageCommandService.saveMessage(1L, DEFAULT_USER_ID, "IMAGE::https://example.com/a,b.png"))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ChatErrorCode.MESSAGE_BAD_REQUEST);
        }
    }

    // ========== 메시지 삭제 테스트 ==========

    @Nested
    @DisplayName("메시지 삭제")
    class DeleteMessage {

        @Test
        @DisplayName("성공: 메시지가 논리 삭제된다")
        void deleteMessage_success() {
            ChatMessageItemDto item = stubItem(1L, 1L, "삭제할 메시지");
            given(chatMessageStoragePort.findById(1L)).willReturn(Optional.of(item));
            given(chatMessageStoragePort.markAsDeleted(1L)).willReturn(true);

            messageCommandService.deleteMessage(1L, DEFAULT_USER_ID);

            verify(chatMessageStoragePort).markAsDeleted(1L);
        }

        @Test
        @DisplayName("실패: 메시지가 없으면 MESSAGE_NOT_FOUND")
        void deleteMessage_notFound_throwsException() {
            given(chatMessageStoragePort.findById(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> messageCommandService.deleteMessage(999L, DEFAULT_USER_ID))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ChatErrorCode.MESSAGE_NOT_FOUND);
        }

        @Test
        @DisplayName("실패: 이미 삭제된 메시지면 MESSAGE_CONFLICT")
        void deleteMessage_alreadyDeleted_throwsException() {
            ChatMessageItemDto deleted = new ChatMessageItemDto(1L, 1L, DEFAULT_USER_ID,
                    "테스트유저", null, "삭제된 메시지입니다.", DEFAULT_SENT_AT, true);
            given(chatMessageStoragePort.findById(1L)).willReturn(Optional.of(deleted));

            assertThatThrownBy(() -> messageCommandService.deleteMessage(1L, DEFAULT_USER_ID))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ChatErrorCode.MESSAGE_CONFLICT);
        }

        @Test
        @DisplayName("실패: 본인 메시지가 아니면 MESSAGE_FORBIDDEN")
        void deleteMessage_notOwner_throwsException() {
            ChatMessageItemDto item = stubItem(1L, 1L, "다른 사람 메시지");
            given(chatMessageStoragePort.findById(1L)).willReturn(Optional.of(item));

            assertThatThrownBy(() -> messageCommandService.deleteMessage(1L, 999L))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ChatErrorCode.MESSAGE_FORBIDDEN);
        }
    }
}
