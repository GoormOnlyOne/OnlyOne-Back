package com.example.onlyone.domain.chat.controller;

import com.example.onlyone.domain.chat.dto.ChatMessageRequest;
import com.example.onlyone.domain.chat.dto.ChatMessageResponse;
import com.example.onlyone.domain.chat.service.AsyncMessageService;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

class ChatWebSocketControllerTest {

    UserRepository userRepository;
    SimpMessagingTemplate messagingTemplate;
    AsyncMessageService asyncMessageService;
    ChatWebSocketController controller;

    @BeforeEach
    void setUp() {
        userRepository = Mockito.mock(UserRepository.class);
        messagingTemplate = Mockito.mock(SimpMessagingTemplate.class);
        asyncMessageService = Mockito.mock(AsyncMessageService.class);

        controller = new ChatWebSocketController(userRepository, asyncMessageService, messagingTemplate);
    }

    private User mockUser(Long kakaoId, String nickname) {
        return User.builder()
                .userId(1L)
                .kakaoId(kakaoId)
                .nickname(nickname)
                .status(Status.ACTIVE)
                .profileImage("test.png")
                .build();
    }

    @Test
    @DisplayName("메시지 전송 성공 시, convertAndSend가 호출되고 저장은 비동기로 위임된다")
    void sendMessageSuccess() {
        Long roomId = 77L;
        Long userId = 1001L;
        String text = "안녕";
        var req = ChatMessageRequest.fromText(userId, text);

        given(userRepository.findByKakaoId(userId)).willReturn(Optional.of(mockUser(userId, "닉네임")));

        controller.sendMessage(roomId, req);

        String dest = "/sub/chat/" + roomId + "/messages";
        then(messagingTemplate).should().convertAndSend(eq(dest), any(ChatMessageResponse.class));
        then(asyncMessageService).should().saveMessageAsync(roomId, req);
    }

    @Test
    @DisplayName("존재하지 않는 유저일 경우 CustomException(USER_NOT_FOUND)을 던진다")
    void sendMessageUserNotFound() {
        Long roomId = 77L;
        Long userId = 9999L;
        String text = "안녕";
        var req = ChatMessageRequest.fromText(userId, text);

        given(userRepository.findByKakaoId(userId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> controller.sendMessage(roomId, req))
                .isInstanceOf(CustomException.class)
                .hasMessage(ErrorCode.USER_NOT_FOUND.getMessage());

        verifyNoInteractions(messagingTemplate);
        verifyNoInteractions(asyncMessageService);
    }

    @Test
    @DisplayName("전송 중 알 수 없는 예외 발생 시 MESSAGE_SERVER_ERROR로 래핑된다")
    void sendMessageUnknownExceptionWrapped() {
        Long roomId = 77L;
        Long userId = 1001L;
        String text = "안녕";
        var req = ChatMessageRequest.fromText(userId, text);

        given(userRepository.findByKakaoId(userId)).willThrow(new RuntimeException("DB down"));

        CustomException ex = catchThrowableOfType(
                () -> controller.sendMessage(roomId, req),
                CustomException.class
        );
        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MESSAGE_SERVER_ERROR);

        verifyNoInteractions(messagingTemplate);
        verifyNoInteractions(asyncMessageService);
    }
}