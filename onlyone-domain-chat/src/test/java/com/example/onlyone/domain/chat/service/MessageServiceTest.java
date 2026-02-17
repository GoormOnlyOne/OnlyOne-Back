package com.example.onlyone.domain.chat.service;

import com.example.onlyone.domain.chat.dto.ChatMessageResponse;
import com.example.onlyone.domain.chat.dto.ChatRoomMessageResponse;
import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.chat.entity.ChatRoomType;
import com.example.onlyone.domain.chat.entity.Message;
import com.example.onlyone.domain.chat.repository.ChatRoomRepository;
import com.example.onlyone.domain.chat.repository.MessageRepository;
import com.example.onlyone.domain.chat.repository.UserChatRoomRepository;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.user.entity.Status;
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
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("MessageService 단위 테스트")
class MessageServiceTest {

    @InjectMocks
    private MessageService messageService;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private ChatRoomRepository chatRoomRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserChatRoomRepository userChatRoomRepository;

    // ========== 공용 픽스처 ==========

    private User createUser() {
        return User.builder()
                .userId(1L)
                .kakaoId(100L)
                .nickname("테스트유저")
                .profileImage("https://example.com/profile.jpg")
                .status(Status.ACTIVE)
                .build();
    }

    private Club createClub() {
        return Club.builder()
                .clubId(1L)
                .name("테스트모임")
                .build();
    }

    private ChatRoom createChatRoom() {
        return ChatRoom.builder()
                .chatRoomId(1L)
                .club(createClub())
                .type(ChatRoomType.CLUB)
                .build();
    }

    private Message createMessage(Long messageId, ChatRoom chatRoom, User user, String text) {
        return Message.builder()
                .messageId(messageId)
                .chatRoom(chatRoom)
                .user(user)
                .text(text)
                .sentAt(LocalDateTime.of(2025, 7, 29, 11, 0, 0))
                .deleted(false)
                .build();
    }

    private void stubSaveReturningWithId(Long savedMessageId) {
        given(messageRepository.save(any(Message.class))).willAnswer(invocation -> {
            Message msg = invocation.getArgument(0);
            return Message.builder()
                    .messageId(savedMessageId)
                    .chatRoom(msg.getChatRoom())
                    .user(msg.getUser())
                    .text(msg.getText())
                    .sentAt(msg.getSentAt())
                    .deleted(false)
                    .build();
        });
    }

    private void stubCommonSaveMessageDependencies(ChatRoom chatRoom, User user) {
        given(chatRoomRepository.findById(chatRoom.getChatRoomId())).willReturn(Optional.of(chatRoom));
        given(userRepository.findByKakaoId(user.getKakaoId())).willReturn(Optional.of(user));
        given(userChatRoomRepository.existsByUserUserIdAndChatRoomChatRoomId(
                user.getUserId(), chatRoom.getChatRoomId())).willReturn(true);
    }

    // ========== 메시지 저장 테스트 ==========

    @Nested
    @DisplayName("메시지 저장")
    class SaveMessage {

        @Test
        @DisplayName("성공: 텍스트 메시지가 저장된다")
        void saveTextMessage_success() {
            // given
            ChatRoom chatRoom = createChatRoom();
            User user = createUser();
            String text = "안녕하세요!";

            stubCommonSaveMessageDependencies(chatRoom, user);
            stubSaveReturningWithId(10L);

            // when
            ChatMessageResponse response = messageService.saveMessage(1L, 100L, text);

            // then
            assertThat(response.messageId()).isEqualTo(10L);
            assertThat(response.chatRoomId()).isEqualTo(1L);
            assertThat(response.senderId()).isEqualTo(100L);
            assertThat(response.senderNickname()).isEqualTo("테스트유저");
            assertThat(response.text()).isEqualTo("안녕하세요!");
            assertThat(response.imageUrl()).isNull();
            assertThat(response.deleted()).isFalse();
            verify(messageRepository).save(any(Message.class));
        }

        @Test
        @DisplayName("성공: 이미지 메시지가 저장된다")
        void saveImageMessage_success() {
            // given
            ChatRoom chatRoom = createChatRoom();
            User user = createUser();
            String text = "IMAGE::https://example.com/img.png";

            stubCommonSaveMessageDependencies(chatRoom, user);
            stubSaveReturningWithId(11L);

            // when
            ChatMessageResponse response = messageService.saveMessage(1L, 100L, text);

            // then
            assertThat(response.messageId()).isEqualTo(11L);
            assertThat(response.text()).isNull();
            assertThat(response.imageUrl()).isEqualTo("https://example.com/img.png");
            assertThat(response.deleted()).isFalse();
            verify(messageRepository).save(any(Message.class));
        }

        @Test
        @DisplayName("성공: 2000자 초과 텍스트가 잘린다")
        void saveMessage_truncatesAt2000() {
            // given
            ChatRoom chatRoom = createChatRoom();
            User user = createUser();
            String longText = "a".repeat(2500);

            stubCommonSaveMessageDependencies(chatRoom, user);
            stubSaveReturningWithId(12L);

            // when
            ChatMessageResponse response = messageService.saveMessage(1L, 100L, longText);

            // then
            assertThat(response.text()).hasSize(2000);
            assertThat(response.text()).isEqualTo("a".repeat(2000));
        }

        @Test
        @DisplayName("실패: 빈 텍스트면 MESSAGE_BAD_REQUEST")
        void saveMessage_blankText_throwsException() {
            // given
            String blankText = "   ";

            // when & then
            assertThatThrownBy(() -> messageService.saveMessage(1L, 100L, blankText))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.MESSAGE_BAD_REQUEST);
        }

        @Test
        @DisplayName("실패: null 텍스트면 MESSAGE_BAD_REQUEST")
        void saveMessage_nullText_throwsException() {
            // when & then
            assertThatThrownBy(() -> messageService.saveMessage(1L, 100L, null))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.MESSAGE_BAD_REQUEST);
        }

        @Test
        @DisplayName("실패: 채팅방이 없으면 CHAT_ROOM_NOT_FOUND")
        void saveMessage_chatRoomNotFound_throwsException() {
            // given
            given(chatRoomRepository.findById(999L)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> messageService.saveMessage(999L, 100L, "메시지"))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.CHAT_ROOM_NOT_FOUND);
        }

        @Test
        @DisplayName("실패: 사용자가 없으면 USER_NOT_FOUND")
        void saveMessage_userNotFound_throwsException() {
            // given
            ChatRoom chatRoom = createChatRoom();
            given(chatRoomRepository.findById(1L)).willReturn(Optional.of(chatRoom));
            given(userRepository.findByKakaoId(999L)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> messageService.saveMessage(1L, 999L, "메시지"))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.USER_NOT_FOUND);
        }

        @Test
        @DisplayName("실패: 채팅방 미참여면 FORBIDDEN_CHAT_ROOM")
        void saveMessage_notJoined_throwsException() {
            // given
            ChatRoom chatRoom = createChatRoom();
            User user = createUser();

            given(chatRoomRepository.findById(1L)).willReturn(Optional.of(chatRoom));
            given(userRepository.findByKakaoId(100L)).willReturn(Optional.of(user));
            given(userChatRoomRepository.existsByUserUserIdAndChatRoomChatRoomId(1L, 1L)).willReturn(false);

            // when & then
            assertThatThrownBy(() -> messageService.saveMessage(1L, 100L, "메시지"))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.FORBIDDEN_CHAT_ROOM);
        }

        @Test
        @DisplayName("실패: 이미지 확장자 유효하지 않으면 INVALID_IMAGE_CONTENT_TYPE")
        void saveMessage_invalidImageExtension_throwsException() {
            // given
            ChatRoom chatRoom = createChatRoom();
            User user = createUser();

            stubCommonSaveMessageDependencies(chatRoom, user);

            // when & then
            assertThatThrownBy(() -> messageService.saveMessage(1L, 100L, "IMAGE::https://example.com/file.gif"))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_IMAGE_CONTENT_TYPE);
        }

        @Test
        @DisplayName("실패: 이미지 URL에 쉼표가 있으면 MESSAGE_BAD_REQUEST")
        void saveMessage_imageUrlWithComma_throwsException() {
            // given
            ChatRoom chatRoom = createChatRoom();
            User user = createUser();

            stubCommonSaveMessageDependencies(chatRoom, user);

            // when & then
            assertThatThrownBy(() -> messageService.saveMessage(1L, 100L, "IMAGE::https://example.com/a,b.png"))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.MESSAGE_BAD_REQUEST);
        }
    }

    // ========== 메시지 삭제 테스트 ==========

    @Nested
    @DisplayName("메시지 삭제")
    class DeleteMessage {

        @Test
        @DisplayName("성공: 메시지가 논리 삭제된다")
        void deleteMessage_success() {
            // given
            ChatRoom chatRoom = createChatRoom();
            User user = createUser();
            Message message = createMessage(1L, chatRoom, user, "삭제할 메시지");

            given(messageRepository.findById(1L)).willReturn(Optional.of(message));

            // when
            messageService.deleteMessage(1L, 1L);

            // then
            assertThat(message.isDeleted()).isTrue();
            assertThat(message.getText()).isEqualTo("삭제된 메시지입니다.");
        }

        @Test
        @DisplayName("실패: 메시지가 없으면 MESSAGE_NOT_FOUND")
        void deleteMessage_notFound_throwsException() {
            // given
            given(messageRepository.findById(999L)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> messageService.deleteMessage(999L, 1L))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.MESSAGE_NOT_FOUND);
        }

        @Test
        @DisplayName("실패: 이미 삭제된 메시지면 MESSAGE_CONFLICT")
        void deleteMessage_alreadyDeleted_throwsException() {
            // given
            ChatRoom chatRoom = createChatRoom();
            User user = createUser();
            Message deletedMessage = Message.builder()
                    .messageId(1L)
                    .chatRoom(chatRoom)
                    .user(user)
                    .text("삭제된 메시지입니다.")
                    .sentAt(LocalDateTime.of(2025, 7, 29, 11, 0, 0))
                    .deleted(true)
                    .build();

            given(messageRepository.findById(1L)).willReturn(Optional.of(deletedMessage));

            // when & then
            assertThatThrownBy(() -> messageService.deleteMessage(1L, 1L))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.MESSAGE_CONFLICT);
        }

        @Test
        @DisplayName("실패: 본인 메시지가 아니면 MESSAGE_DELETE_ERROR")
        void deleteMessage_notOwner_throwsException() {
            // given
            ChatRoom chatRoom = createChatRoom();
            User user = createUser(); // userId = 1L
            Message message = createMessage(1L, chatRoom, user, "다른 사람 메시지");

            given(messageRepository.findById(1L)).willReturn(Optional.of(message));

            // when & then (userId = 999L은 소유자가 아님)
            assertThatThrownBy(() -> messageService.deleteMessage(1L, 999L))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.MESSAGE_DELETE_ERROR);
        }
    }

    // ========== 채팅방 메시지 조회 테스트 ==========

    @Nested
    @DisplayName("채팅방 메시지 조회")
    class GetChatRoomMessages {

        @Test
        @DisplayName("성공: 초기 로드시 최신 메시지가 반환된다")
        void getChatRoomMessages_initialLoad_success() {
            // given
            Club club = Club.builder().clubId(1L).name("테스트모임").build();
            ChatRoom chatRoom = ChatRoom.builder()
                    .chatRoomId(1L)
                    .club(club)
                    .type(ChatRoomType.CLUB)
                    .build();
            User user = createUser();

            Message msg1 = createMessage(1L, chatRoom, user, "메시지1");
            Message msg2 = createMessage(2L, chatRoom, user, "메시지2");
            Message msg3 = createMessage(3L, chatRoom, user, "메시지3");

            // findLatest는 desc 순서로 반환 (3, 2, 1)
            List<Message> descMessages = new ArrayList<>(List.of(msg3, msg2, msg1));

            given(chatRoomRepository.findById(1L)).willReturn(Optional.of(chatRoom));
            given(messageRepository.findLatest(eq(1L), any(Pageable.class))).willReturn(descMessages);

            // when (커서 없이 초기 로드)
            ChatRoomMessageResponse response = messageService.getChatRoomMessages(1L, 50, null, null);

            // then
            assertThat(response.chatRoomId()).isEqualTo(1L);
            assertThat(response.chatRoomName()).isEqualTo("테스트모임");
            assertThat(response.hasMore()).isFalse();
            // reverse되어 오름차순 (1, 2, 3)
            assertThat(response.messages()).hasSize(3);
            assertThat(response.messages().get(0).messageId()).isEqualTo(1L);
            assertThat(response.messages().get(2).messageId()).isEqualTo(3L);
        }

        @Test
        @DisplayName("성공: 커서 기반 조회시 이전 메시지가 반환된다")
        void getChatRoomMessages_cursorBased_success() {
            // given
            Club club = Club.builder().clubId(1L).name("테스트모임").build();
            Schedule schedule = Schedule.builder().scheduleId(1L).name("정기모임A").build();
            ChatRoom chatRoom = ChatRoom.builder()
                    .chatRoomId(2L)
                    .club(club)
                    .schedule(schedule)
                    .type(ChatRoomType.SCHEDULE)
                    .build();
            User user = createUser();

            Message msg1 = createMessage(1L, chatRoom, user, "이전메시지1");
            Message msg2 = createMessage(2L, chatRoom, user, "이전메시지2");
            List<Message> descMessages = new ArrayList<>(List.of(msg2, msg1));

            LocalDateTime cursorAt = LocalDateTime.of(2025, 7, 29, 12, 0, 0);

            given(chatRoomRepository.findById(2L)).willReturn(Optional.of(chatRoom));
            given(messageRepository.findOlderThan(eq(2L), eq(cursorAt), eq(5L), any(Pageable.class)))
                    .willReturn(descMessages);

            // when
            ChatRoomMessageResponse response = messageService.getChatRoomMessages(2L, 50, 5L, cursorAt);

            // then
            assertThat(response.chatRoomId()).isEqualTo(2L);
            assertThat(response.chatRoomName()).isEqualTo("정기모임A");
            assertThat(response.hasMore()).isFalse();
            // reverse되어 오름차순 (1, 2)
            assertThat(response.messages()).hasSize(2);
            assertThat(response.messages().get(0).messageId()).isEqualTo(1L);
            assertThat(response.messages().get(1).messageId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("성공: hasMore가 올바르게 설정된다")
        void getChatRoomMessages_hasMore_true() {
            // given
            Club club = Club.builder().clubId(1L).name("테스트모임").build();
            ChatRoom chatRoom = ChatRoom.builder()
                    .chatRoomId(1L)
                    .club(club)
                    .type(ChatRoomType.CLUB)
                    .build();
            User user = createUser();

            // size=2 -> fetchSize=3, 3개가 반환되면 hasMore=true
            int size = 2;
            Message msg1 = createMessage(1L, chatRoom, user, "메시지1");
            Message msg2 = createMessage(2L, chatRoom, user, "메시지2");
            Message msg3 = createMessage(3L, chatRoom, user, "메시지3");
            List<Message> descMessages = new ArrayList<>(List.of(msg3, msg2, msg1));

            given(chatRoomRepository.findById(1L)).willReturn(Optional.of(chatRoom));
            given(messageRepository.findLatest(eq(1L), any(Pageable.class))).willReturn(descMessages);

            // when
            ChatRoomMessageResponse response = messageService.getChatRoomMessages(1L, size, null, null);

            // then
            assertThat(response.hasMore()).isTrue();
            // 초과분 제거 후 size개만 반환, reverse되어 오름차순
            assertThat(response.messages()).hasSize(2);
            // desc에서 앞 2개(msg3, msg2)를 취한 뒤 reverse -> (msg2, msg3)
            assertThat(response.messages().get(0).messageId()).isEqualTo(2L);
            assertThat(response.messages().get(1).messageId()).isEqualTo(3L);
            // nextCursorId는 reverse 후 첫 번째(가장 오래된) 메시지 기준
            assertThat(response.nextCursorId()).isEqualTo(2L);
        }
    }
}
