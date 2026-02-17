package com.example.onlyone.domain.chat.controller;

import com.example.onlyone.domain.chat.dto.ChatMessageRequest;
import com.example.onlyone.domain.chat.service.AsyncMessageService;
import com.example.onlyone.domain.chat.service.ChatPublisher;
import com.example.onlyone.domain.user.dto.UserPrincipal;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatWebSocketController 단위 테스트")
class ChatWebSocketControllerTest {

    @InjectMocks
    private ChatWebSocketController controller;

    @Mock private UserRepository userRepository;
    @Mock private AsyncMessageService asyncMessageService;
    @Mock private ChatPublisher chatPublisher;
    @Mock private ObjectMapper objectMapper;

    // ==================== fixtures ====================

    private User user(Long userId, Long kakaoId, String nickname) {
        return User.builder()
                .userId(userId)
                .kakaoId(kakaoId)
                .nickname(nickname)
                .profileImage("profile.jpg")
                .status(Status.ACTIVE)
                .build();
    }

    private SimpMessageHeaderAccessor headerWithPrincipal(Long kakaoId) {
        UserPrincipal principal = UserPrincipal.fromClaims("1", kakaoId.toString(), "ACTIVE", "ROLE_USER");
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create();
        accessor.setUser(auth);
        return accessor;
    }

    // ==================== 메시지 전송 ====================

    @Nested
    @DisplayName("메시지 전송")
    class SendMessage {

        @Test
        @DisplayName("성공: Principal의 kakaoId로 유저를 조회하고 메시지를 전송한다")
        void successWithPrincipalKakaoId() throws Exception {
            // given
            Long chatRoomId = 1L;
            Long authenticatedKakaoId = 10001L;
            User authenticatedUser = user(1L, authenticatedKakaoId, "인증유저");

            ChatMessageRequest request = new ChatMessageRequest(99999L, "안녕하세요!", null);
            SimpMessageHeaderAccessor accessor = headerWithPrincipal(authenticatedKakaoId);

            given(userRepository.findByKakaoId(authenticatedKakaoId))
                    .willReturn(Optional.of(authenticatedUser));
            given(objectMapper.writeValueAsString(any())).willReturn("{}");

            // when
            controller.sendMessage(chatRoomId, request, accessor);

            // then - Principal의 kakaoId(10001)로 조회, 클라이언트의 userId(99999)는 무시
            then(userRepository).should().findByKakaoId(authenticatedKakaoId);
            then(userRepository).should(never()).findByKakaoId(99999L);
            then(chatPublisher).should().publish(eq(chatRoomId), any());
        }

        @Test
        @DisplayName("성공: 비동기 저장 시 인증된 kakaoId가 사용된다")
        void asyncSaveUsesAuthenticatedKakaoId() throws Exception {
            // given
            Long chatRoomId = 1L;
            Long authenticatedKakaoId = 10001L;
            Long clientFakeUserId = 99999L;
            User authenticatedUser = user(1L, authenticatedKakaoId, "인증유저");

            ChatMessageRequest request = new ChatMessageRequest(clientFakeUserId, "테스트 메시지", null);
            SimpMessageHeaderAccessor accessor = headerWithPrincipal(authenticatedKakaoId);

            given(userRepository.findByKakaoId(authenticatedKakaoId))
                    .willReturn(Optional.of(authenticatedUser));
            given(objectMapper.writeValueAsString(any())).willReturn("{}");

            // when
            controller.sendMessage(chatRoomId, request, accessor);

            // then - 비동기 저장에 전달된 request의 userId가 인증된 kakaoId인지 검증
            ArgumentCaptor<ChatMessageRequest> captor = ArgumentCaptor.forClass(ChatMessageRequest.class);
            then(asyncMessageService).should().saveMessageAsync(eq(chatRoomId), captor.capture());

            ChatMessageRequest savedRequest = captor.getValue();
            assertThat(savedRequest.userId()).isEqualTo(authenticatedKakaoId);
            assertThat(savedRequest.userId()).isNotEqualTo(clientFakeUserId);
            assertThat(savedRequest.text()).isEqualTo("테스트 메시지");
        }

        @Test
        @DisplayName("성공: 이미지 메시지를 올바르게 처리한다")
        void successWithImageMessage() throws Exception {
            // given
            Long chatRoomId = 1L;
            Long kakaoId = 10001L;
            User authenticatedUser = user(1L, kakaoId, "인증유저");

            ChatMessageRequest request = new ChatMessageRequest(kakaoId, "IMAGE::https://cdn.example.com/img.jpg", null);
            SimpMessageHeaderAccessor accessor = headerWithPrincipal(kakaoId);

            given(userRepository.findByKakaoId(kakaoId))
                    .willReturn(Optional.of(authenticatedUser));
            given(objectMapper.writeValueAsString(any())).willReturn("{}");

            // when
            controller.sendMessage(chatRoomId, request, accessor);

            // then
            then(chatPublisher).should().publish(eq(chatRoomId), any());
            then(asyncMessageService).should().saveMessageAsync(eq(chatRoomId), any());
        }

        @Test
        @DisplayName("실패: 인증된 kakaoId에 해당하는 유저가 없으면 USER_NOT_FOUND")
        void failUserNotFound() throws Exception {
            // given
            Long chatRoomId = 1L;
            Long kakaoId = 10001L;

            ChatMessageRequest request = new ChatMessageRequest(kakaoId, "안녕!", null);
            SimpMessageHeaderAccessor accessor = headerWithPrincipal(kakaoId);

            given(userRepository.findByKakaoId(kakaoId)).willReturn(Optional.empty());

            // when
            Throwable thrown = catchThrowable(() ->
                    controller.sendMessage(chatRoomId, request, accessor));

            // then
            assertThat(thrown).isInstanceOf(CustomException.class);
            assertThat(((CustomException) thrown).getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
            then(chatPublisher).shouldHaveNoInteractions();
            then(asyncMessageService).shouldHaveNoInteractions();
        }
    }
}
