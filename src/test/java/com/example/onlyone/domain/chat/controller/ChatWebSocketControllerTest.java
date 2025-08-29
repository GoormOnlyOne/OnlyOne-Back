package com.example.onlyone.domain.chat.controller;

import com.example.onlyone.domain.chat.dto.ChatMessageRequest;
import com.example.onlyone.domain.chat.dto.ChatMessageResponse;
import com.example.onlyone.domain.chat.service.MessageService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.verifyNoInteractions;

class ChatWebSocketControllerTest {

    MessageService messageService;
    SimpMessagingTemplate messagingTemplate;
    ChatWebSocketController controller;

    @BeforeEach
    void setUp() {
        messageService = Mockito.mock(MessageService.class);
        messagingTemplate = Mockito.mock(SimpMessagingTemplate.class);
        controller = new ChatWebSocketController(messageService, messagingTemplate);
    }

    private ChatMessageResponse msg(Long id, Long roomId, Long kakaoId, String text) {
        return ChatMessageResponse.builder()
                .messageId(id)
                .chatRoomId(roomId)
                .senderId(kakaoId)
                .senderNickname("닉")
                .profileImage(null)
                .text(text)
                .imageUrl(null)
                .sentAt(LocalDateTime.now())
                .deleted(false)
                .build();
    }

    @Test
    @DisplayName("메세지_전송_성공시_saveMessage_위임하고_정확한_destination으로_브로드캐스트한다")
    void sendMessageSuccess() {
        Long roomId = 77L;
        Long userId = 1001L;
        String text = "안녕";
        var req = ChatMessageRequest.fromText(userId, text);
        var res = msg(1L, roomId, userId, text);

        given(messageService.saveMessage(roomId, userId, text)).willReturn(res);

        controller.sendMessage(roomId, req);

        then(messageService).should().saveMessage(roomId, userId, text);
        String dest = "/sub/chat/" + roomId + "/messages";
        then(messagingTemplate).should().convertAndSend(dest, res);
    }

    @Test
    @DisplayName("메세지_전송_중_예외_발생_시_CustomException은_그대로_전파된다")
    void sendMessageCustomException() {
        Long roomId = 77L;
        Long userId = 1001L;
        String text = "안녕";
        var req = ChatMessageRequest.fromText(userId, text);

        willThrow(new CustomException(ErrorCode.NO_PERMISSION))
                .given(messageService).saveMessage(roomId, userId, text);

        assertThatThrownBy(() -> controller.sendMessage(roomId, req))
                .isInstanceOf(CustomException.class)
                .hasMessage(ErrorCode.NO_PERMISSION.getMessage());

        verifyNoInteractions(messagingTemplate);
    }

    @Test
    @DisplayName("메세지_전송_중_알_수_없는_예외_발생_시_MESSAGE_SERVER_ERROR로_래핑되어_전파된다")
    void sendMessageUnknownExceptionWrapped() {
        Long roomId = 77L;
        Long userId = 1001L;
        String text = "안녕";
        var req = ChatMessageRequest.fromText(userId, text);

        willThrow(new RuntimeException("DB down"))
                .given(messageService).saveMessage(roomId, userId, text);

        CustomException ex = catchThrowableOfType(
                () -> controller.sendMessage(roomId, req),
                CustomException.class
        );
        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MESSAGE_SERVER_ERROR);

        verifyNoInteractions(messagingTemplate);
    }
}