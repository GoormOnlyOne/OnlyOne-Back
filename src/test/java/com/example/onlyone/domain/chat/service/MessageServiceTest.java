package com.example.onlyone.domain.chat.service;

import com.example.onlyone.domain.chat.dto.ChatRoomMessageResponse;
import com.example.onlyone.domain.chat.entity.*;
import com.example.onlyone.domain.chat.repository.ChatRoomRepository;
import com.example.onlyone.domain.chat.repository.MessageRepository;
import com.example.onlyone.domain.chat.repository.UserChatRoomRepository;
import com.example.onlyone.domain.chat.dto.ChatMessageResponse;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.interest.entity.Category;
import com.example.onlyone.domain.interest.entity.Interest;
import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.data.jpa.util.JpaMetamodel.of;

@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @InjectMocks
    MessageService messageService;

    @Mock
    MessageRepository messageRepository;
    @Mock
    ChatRoomRepository chatRoomRepository;
    @Mock
    UserRepository userRepository;

    /*
    @Mock
    NotificationService notificationService;
    */
    @Mock
    UserChatRoomRepository userChatRoomRepository;

    // ---------- fixtures ----------
    private Interest interest() {
        return Interest.builder().interestId(1L).category(Category.CULTURE).build();
    }

    private Club club() {
        return Club.builder()
                .clubId(10L).name("모임").userLimit(100).description("d")
                .city("Seoul").district("Gangnam").interest(interest())
                .build();
    }

    private ChatRoom room() {
        return ChatRoom.builder().chatRoomId(101L).club(club()).type(Type.CLUB).build();
    }

    private User user(Long id, long kakaoId, String nick) {
        return User.builder().userId(id).kakaoId(kakaoId).status(Status.ACTIVE)
                .nickname(nick).profileImage("p.png").city("Seoul").district("Gangnam").build();
    }

    private Message msg(Long id, ChatRoom r, User u, String text, boolean deleted, LocalDateTime at) {
        return Message.builder().messageId(id).chatRoom(r).user(u).text(text).deleted(deleted).sentAt(at).build();
    }

    private UserChatRoom membership(User u, ChatRoom r) {
        return UserChatRoom.builder().user(u).chatRoom(r).chatRole(ChatRole.MEMBER).build();
    }

    @Test
    @DisplayName("미참여자는_메시지_전송이_불가능하다")
    void saveMessageNotParticipantForbidden() {
        ChatRoom r = room();
        User sender = user(1L, 1001L, "보낸이");
        given(chatRoomRepository.findById(101L)).willReturn(Optional.of(r));
        given(userRepository.findByKakaoId(1001L)).willReturn(Optional.of(sender));
        given(userChatRoomRepository.existsByUserUserIdAndChatRoomChatRoomId(1L, 101L)).willReturn(false);

        assertThatThrownBy(() -> messageService.saveMessage(101L, 1001L, "안녕"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN_CHAT_ROOM);
    }

    @Test
    @DisplayName("텍스트_메세지_전송_성공_시_DB저장_및_본인_제외_참여자들에게_알림_전송")
    void saveTextMessageSuccess() {
        // given
        ChatRoom r = room();
        User sender = user(1L, 1001L, "보낸이");
        User other1 = user(2L, 2002L, "받는이1");
        User other2 = user(3L, 2003L, "받는이2");

        given(chatRoomRepository.findById(101L)).willReturn(Optional.of(r));
        given(userRepository.findByKakaoId(1001L)).willReturn(Optional.of(sender));
        // 참여 여부 검사 통과
        given(userChatRoomRepository.existsByUserUserIdAndChatRoomChatRoomId(1L, 101L)).willReturn(true);
        // 알림 대상(보낸이 + 타인 2명)
        given(userChatRoomRepository.findAllByChatRoom(r))
                .willReturn(List.of(membership(sender, r), membership(other1, r), membership(other2, r)));

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        willAnswer(inv -> {
            Message m = inv.getArgument(0);
            return msg(555L, m.getChatRoom(), m.getUser(), m.getText(), false, m.getSentAt());
        }).given(messageRepository).save(any(Message.class));

        // when
        // 두 번째 파라미터는 kakaoId 임에 주의!
        ChatMessageResponse res = messageService.saveMessage(101L, 1001L, "안녕하세요!");

        // then
        // DB 저장/응답 기본 검증
        assertThat(res.getMessageId()).isEqualTo(555L);
        assertThat(res.getChatRoomId()).isEqualTo(101L);
        assertThat(res.getSenderId()).isEqualTo(1001L);
        assertThat(res.getSenderNickname()).isEqualTo("보낸이");
        assertThat(res.getText()).isEqualTo("안녕하세요!");
        assertThat(res.getImageUrl()).isNull();

        then(messageRepository).should().save(captor.capture());
        assertThat(captor.getValue().getText()).isEqualTo("안녕하세요!");

        /*
        // 본인 제외 알림 전송
        then(notificationService).should().createNotification(
                eq(other1),
                eq(com.example.onlyone.domain.notification.entity.Type.CHAT),
                aryEq(new String[]{"보낸이"})
        );
        then(notificationService).should().createNotification(
                eq(other2),
                eq(com.example.onlyone.domain.notification.entity.Type.CHAT),
                aryEq(new String[]{"보낸이"})
        );
        then(notificationService).should(never()).createNotification(eq(sender), any(), any(String[].class));
         */
    }

    @Test
    @DisplayName("텍스트_2000자_초과시_자동_절단되어_DB저장_및_응답된다")
    void saveTextMessageTruncatedOver2000() {
        // given
        ChatRoom r = room();
        User sender = user(1L, 1001L, "보낸이");
        User other1 = user(2L, 2002L, "받는이1");
        User other2 = user(3L, 2003L, "받는이2");

        given(chatRoomRepository.findById(101L)).willReturn(Optional.of(r));
        given(userRepository.findByKakaoId(1001L)).willReturn(Optional.of(sender));
        // 참여 여부 검사 통과
        given(userChatRoomRepository.existsByUserUserIdAndChatRoomChatRoomId(1L, 101L)).willReturn(true);
        // 알림 대상(보낸이 + 타인 2명)
        given(userChatRoomRepository.findAllByChatRoom(r))
                .willReturn(List.of(membership(sender, r), membership(other1, r), membership(other2, r)));

        // 2100자 본문 → 2000자로 잘려야 함
        String longText = "a".repeat(2100);
        String expectedSaved = longText.substring(0, 2000);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        willAnswer(inv -> {
            Message m = inv.getArgument(0);
            // save 후 반환되는 엔티티에 잘린 텍스트가 들어가 있어야 함
            return msg(556L, m.getChatRoom(), m.getUser(), m.getText(), false, m.getSentAt());
        }).given(messageRepository).save(any(Message.class));

        // when
        ChatMessageResponse res = messageService.saveMessage(101L, 1001L, longText);

        // then
        // 응답 검증 (텍스트가 2000자로 잘려서 들어가야 함)
        assertThat(res.getMessageId()).isEqualTo(556L);
        assertThat(res.getChatRoomId()).isEqualTo(101L);
        assertThat(res.getSenderId()).isEqualTo(1001L);
        assertThat(res.getSenderNickname()).isEqualTo("보낸이");
        assertThat(res.getImageUrl()).isNull();
        assertThat(res.getText()).isEqualTo(expectedSaved);
        assertThat(res.getText().length()).isEqualTo(2000);

        // DB 저장 본문도 2000자로 잘렸는지 확인
        then(messageRepository).should().save(captor.capture());
        assertThat(captor.getValue().getText()).isEqualTo(expectedSaved);
        assertThat(captor.getValue().getText().length()).isEqualTo(2000);

        /*
        // 본인 제외 알림 전송 확인
        then(notificationService).should().createNotification(
                eq(other1),
                eq(com.example.onlyone.domain.notification.entity.Type.CHAT),
                aryEq(new String[]{"보낸이"})
        );
        then(notificationService).should().createNotification(
                eq(other2),
                eq(com.example.onlyone.domain.notification.entity.Type.CHAT),
                aryEq(new String[]{"보낸이"})
        );
        then(notificationService).should(never()).createNotification(eq(sender), any(), any(String[].class));
         */
    }

    @Test
    @DisplayName("이모지와_다국어_텍스트가_깨지지_않고_그대로_저장된다")
    void unicode_preserved() {
        ChatRoom r = room();
        User u = user(1L, 1001L, "u");

        when(chatRoomRepository.findById(101L)).thenReturn(Optional.of(r));
        when(userRepository.findByKakaoId(1001L)).thenReturn(Optional.of(u));
        when(userChatRoomRepository.existsByUserUserIdAndChatRoomChatRoomId(1L, 101L)).thenReturn(true);

        String unicode = "안녕👋 مرحبا שלום";
        when(messageRepository.save(any(Message.class)))
                .thenAnswer(inv -> {
                    Message m = inv.getArgument(0);
                    return Message.builder().messageId(7L)
                            .chatRoom(m.getChatRoom()).user(m.getUser())
                            .text(m.getText()).deleted(false).sentAt(m.getSentAt()).build();
                });
        when(userChatRoomRepository.findAllByChatRoom(r)).thenReturn(List.of(membership(u, r)));

        ChatMessageResponse res = messageService.saveMessage(101L, 1001L, unicode);
        assertThat(res.getText()).isEqualTo(unicode);
    }

    @Test
    @DisplayName("공백만_입력_시_전송이_불가능하다")
    void saveMessageBlankRejected() {
        // given
        // when & then
        assertThatThrownBy(() -> messageService.saveMessage(101L, 1001L, "   "))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.MESSAGE_BAD_REQUEST);

        then(messageRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("텍스트_입력_시_이미지_첨부는_불가능하다")
    void sendOnlyTextMessage() {
        // given
        ChatRoom r = room();
        User sender = user(1L, 1001L, "보낸이");

        given(chatRoomRepository.findById(101L)).willReturn(Optional.of(r));
        given(userRepository.findByKakaoId(1001L)).willReturn(Optional.of(sender));
        given(userChatRoomRepository.existsByUserUserIdAndChatRoomChatRoomId(1L, 101L)).willReturn(true);

        // 이미지 URL 뒤에 텍스트가 붙은 케이스 → 허용 안 함
        String payload = "IMAGE:: https://cdn.example.com/a.png 설명텍스트도함께";

        // when / then
        assertThatThrownBy(() -> messageService.saveMessage(101L, 1001L, payload))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.MESSAGE_BAD_REQUEST);

        then(messageRepository).shouldHaveNoInteractions();
        /*
        then(notificationService).shouldHaveNoInteractions();

         */
    }

    @Test
    @DisplayName("이미지_메세지_전송_성공_시_DB에_저장하고_본인을_제외한_참여자들에게_알림을_전송한다")
    void saveImageMessageSuccess() {
        // given
        ChatRoom r = room();
        User sender = user(1L, 1001L, "보낸이");
        User other1 = user(2L, 2002L, "받는이1");
        User other2 = user(3L, 2003L, "받는이2");

        given(chatRoomRepository.findById(101L)).willReturn(Optional.of(r));
        given(userRepository.findByKakaoId(1001L)).willReturn(Optional.of(sender));
        given(userChatRoomRepository.existsByUserUserIdAndChatRoomChatRoomId(1L, 101L))
                .willReturn(true);
        given(userChatRoomRepository.findAllByChatRoom(r))
                .willReturn(List.of(membership(sender, r), membership(other1, r), membership(other2, r)));

        ArgumentCaptor<Message> cap = ArgumentCaptor.forClass(Message.class);
        willAnswer(inv -> {
            Message m = inv.getArgument(0);
            return msg(777L, m.getChatRoom(), m.getUser(), m.getText(), false, m.getSentAt());
        }).given(messageRepository).save(any(Message.class));

        String cdnUrl = "https://cdn.example.com/chat/abc.png";

        // when
        ChatMessageResponse res = messageService.saveMessage(101L, 1001L, "IMAGE:: " + cdnUrl);

        // then
        assertThat(res.getMessageId()).isEqualTo(777L);
        assertThat(res.getText()).isNull();
        assertThat(res.getImageUrl()).isEqualTo(cdnUrl);

        then(messageRepository).should().save(cap.capture());
        assertThat(cap.getValue().getText()).isEqualTo(cdnUrl);

        /*
        // 본인 제외 2명에게 알림 각 1회
        then(notificationService).should().createNotification(eq(other1),
                any(com.example.onlyone.domain.notification.entity.Type.class),
                aryEq(new String[]{"보낸이"}));
        then(notificationService).should().createNotification(eq(other2),
                any(com.example.onlyone.domain.notification.entity.Type.class),
                aryEq(new String[]{"보낸이"}));
        then(notificationService).should(never()).createNotification(eq(sender), any(), any());
         */
    }

    @Test
    @DisplayName("메세지_하나에_한_개의_이미지만_전송_가능하다")
    void saveOnlyOneImage() {
        // given
        ChatRoom r = room();
        User sender = user(1L, 1001L, "보낸이");

        given(chatRoomRepository.findById(101L)).willReturn(Optional.of(r));
        given(userRepository.findByKakaoId(1001L)).willReturn(Optional.of(sender));
        given(userChatRoomRepository.existsByUserUserIdAndChatRoomChatRoomId(1L, 101L)).willReturn(true);

        String payload = "IMAGE:: https://cdn.example.com/a.png,https://cdn.example.com/b.png";

        // when / then
        assertThatThrownBy(() -> messageService.saveMessage(101L, 1001L, payload))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.MESSAGE_BAD_REQUEST);

        then(messageRepository).shouldHaveNoInteractions();
        /*
        then(notificationService).shouldHaveNoInteractions();

         */
    }

    @Test
    @DisplayName("메세지에_이미지_첨부_시_텍스트는_입력할_수_없다")
    void sendOnlyImageMessage() {
        // given
        ChatRoom r = room();
        User sender = user(1L, 1001L, "보낸이");

        given(chatRoomRepository.findById(101L)).willReturn(Optional.of(r));
        given(userRepository.findByKakaoId(1001L)).willReturn(Optional.of(sender));
        given(userChatRoomRepository.existsByUserUserIdAndChatRoomChatRoomId(1L, 101L)).willReturn(true);

        String payload = "IMAGE:: https://cdn.example.com/a.png 설명텍스트";

        // when / then
        assertThatThrownBy(() -> messageService.saveMessage(101L, 1001L, payload))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.MESSAGE_BAD_REQUEST);

        then(messageRepository).shouldHaveNoInteractions();
        /*
        then(notificationService).shouldHaveNoInteractions();

         */
    }

    @Test
    @DisplayName("본인이_보낸_메세지만_삭제_가능하다")
    void deleteMessageOwnerOnly() {
        // given
        ChatRoom r = room();
        User owner = user(1L, 1001L, "소유자");
        Message m = msg(1L, r, owner, "원본문자", false, LocalDateTime.now());
        given(messageRepository.findById(1L)).willReturn(Optional.of(m));

        // when
        messageService.deleteMessage(1L, 1L);

        // then
        assertThat(m.isDeleted()).isTrue();
        assertThat(m.getText()).isEqualTo("삭제된 메시지입니다.");
        then(messageRepository).should().findById(1L);
    }

    @Test
    @DisplayName("이미_삭제된_메세지는_삭제가_불가능하다")
    void deleteMessageAlreadyDeletedConflict() {
        // given
        ChatRoom r = room();
        User owner = user(1L, 1001L, "소유자");
        Message m = msg(1L, r, owner, "삭제된 메시지입니다.", true, LocalDateTime.now().minusMinutes(1));
        given(messageRepository.findById(1L)).willReturn(Optional.of(m));

        // when
        Throwable t = catchThrowable(() -> messageService.deleteMessage(1L, 1L));

        // then
        assertThat(t).isInstanceOf(CustomException.class);
        assertThat(((CustomException) t).getErrorCode()).isEqualTo(ErrorCode.MESSAGE_CONFLICT);
    }

    @Test
    @DisplayName("타인이_보낸_메세지는_삭제가_불가능하다")
    void deleteMessageForbidden() {
        // given
        ChatRoom r = room();
        User owner = user(99L, 9999L, "원본작성자");
        Message m = msg(1L, r, owner, "원본문자", false, LocalDateTime.now());
        given(messageRepository.findById(1L)).willReturn(Optional.of(m));

        // when
        Throwable t = catchThrowable(() -> messageService.deleteMessage(1L, 1L));

        // then
        assertThat(t).isInstanceOf(CustomException.class);
        assertThat(((CustomException) t).getErrorCode()).isEqualTo(ErrorCode.MESSAGE_DELETE_ERROR);
    }

    @Test
    @DisplayName("같은_채팅방에서_메세지들의_sentAt이_같으면_messageId_기준으로_오름차순으로_정렬한다")
    void SameSentAtMessagesSortById() {
        // given
        ChatRoom r = room(); // chatRoomId = 101L (fixture)
        User u = user(1L, 1001L, "u");

        LocalDateTime sameTime = LocalDateTime.of(2025, 8, 26, 12, 0, 0);

        Message m1 = msg(1001L, r, u, "first",  false, sameTime);
        Message m2 = msg(1002L, r, u, "second", false, sameTime);

        when(chatRoomRepository.findById(101L)).thenReturn(Optional.of(r));

        when(messageRepository.findLatest(eq(101L), any(Pageable.class)))
                .thenReturn(new ArrayList<>(Arrays.asList(m2, m1)));

        // when
        ChatRoomMessageResponse res = messageService.getChatRoomMessages(
                101L, /*size*/ 50, /*cursorId*/ null, /*cursorAt*/ null);

        // then
        List<ChatMessageResponse> msgs = res.getMessages();
        assertThat(msgs).extracting(ChatMessageResponse::getMessageId)
                .containsExactly(1001L, 1002L);

        assertThat(res.getHasMore()).isFalse();
        assertThat(res.getNextCursorId()).isEqualTo(1001L);
        assertThat(res.getNextCursorAt()).isEqualTo(sameTime);
    }
}