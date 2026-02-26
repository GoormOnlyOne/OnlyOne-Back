package com.example.onlyone.domain.chat.controller;

import com.example.onlyone.domain.chat.dto.ChatMessageRequest;
import com.example.onlyone.domain.chat.service.AsyncMessageService;
import com.example.onlyone.domain.chat.service.MessageCommandService;
import com.example.onlyone.domain.user.dto.UserPrincipal;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import static com.example.onlyone.domain.chat.fixture.ChatFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatWebSocketController 단위 테스트")
class ChatWebSocketControllerTest {

    @InjectMocks private ChatWebSocketController controller;
    @Mock private UserService userService;
    @Mock private AsyncMessageService asyncMessageService;
    @Mock private MessageCommandService messageCommandService;

    private static final Long USER_ID = 1L;
    private static final Long KAKAO_ID = 10001L;

    private SimpMessageHeaderAccessor headerWithPrincipal(Long userId, Long kakaoId) {
        UserPrincipal principal = UserPrincipal.fromClaims(
                userId.toString(), kakaoId.toString(), "ACTIVE", "ROLE_USER");
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
        @DisplayName("성공: Principal의 userId로 유저를 조회하고 메시지를 전송한다")
        void successWithPrincipalUserId() {
            Long chatRoomId = 1L;
            User authenticatedUser = user(USER_ID, KAKAO_ID, "인증유저");

            ChatMessageRequest request = new ChatMessageRequest("안녕하세요!", null);
            SimpMessageHeaderAccessor accessor = headerWithPrincipal(USER_ID, KAKAO_ID);

            given(userService.getMemberById(USER_ID)).willReturn(authenticatedUser);

            controller.sendMessage(chatRoomId, request, accessor);

            then(userService).should().getMemberById(USER_ID);
            then(messageCommandService).should().publishImmediately(
                    eq(chatRoomId), eq(USER_ID), eq("인증유저"),
                    eq(authenticatedUser.getProfileImage()), eq("안녕하세요!"));
            then(asyncMessageService).should()
                    .saveMessageAsync(eq(chatRoomId), eq(USER_ID), eq("안녕하세요!"));
        }

        @Test
        @DisplayName("성공: 비동기 저장 시 인증된 userId가 사용된다")
        void asyncSaveUsesAuthenticatedUserId() {
            Long chatRoomId = 1L;
            User authenticatedUser = user(USER_ID, KAKAO_ID, "인증유저");

            ChatMessageRequest request = new ChatMessageRequest("테스트 메시지", null);
            SimpMessageHeaderAccessor accessor = headerWithPrincipal(USER_ID, KAKAO_ID);

            given(userService.getMemberById(USER_ID)).willReturn(authenticatedUser);

            controller.sendMessage(chatRoomId, request, accessor);

            then(asyncMessageService).should()
                    .saveMessageAsync(eq(chatRoomId), eq(USER_ID), eq("테스트 메시지"));
        }

        @Test
        @DisplayName("성공: 이미지 메시지를 올바르게 처리한다")
        void successWithImageMessage() {
            Long chatRoomId = 1L;
            User authenticatedUser = user(USER_ID, KAKAO_ID, "인증유저");

            ChatMessageRequest request = new ChatMessageRequest("IMAGE::https://cdn.example.com/img.jpg", null);
            SimpMessageHeaderAccessor accessor = headerWithPrincipal(USER_ID, KAKAO_ID);

            given(userService.getMemberById(USER_ID)).willReturn(authenticatedUser);

            controller.sendMessage(chatRoomId, request, accessor);

            then(messageCommandService).should().publishImmediately(
                    eq(chatRoomId), eq(USER_ID), eq("인증유저"),
                    eq(authenticatedUser.getProfileImage()),
                    eq("IMAGE::https://cdn.example.com/img.jpg"));
            then(asyncMessageService).should()
                    .saveMessageAsync(eq(chatRoomId), eq(USER_ID), eq("IMAGE::https://cdn.example.com/img.jpg"));
        }

        @Test
        @DisplayName("실패: 인증된 userId에 해당하는 유저가 없으면 USER_NOT_FOUND")
        void failUserNotFound() {
            Long chatRoomId = 1L;

            ChatMessageRequest request = new ChatMessageRequest("안녕!", null);
            SimpMessageHeaderAccessor accessor = headerWithPrincipal(USER_ID, KAKAO_ID);

            given(userService.getMemberById(USER_ID))
                    .willThrow(new CustomException(ErrorCode.USER_NOT_FOUND));

            Throwable thrown = catchThrowable(() ->
                    controller.sendMessage(chatRoomId, request, accessor));

            assertThat(thrown).isInstanceOf(CustomException.class);
            assertThat(((CustomException) thrown).getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
            then(messageCommandService).shouldHaveNoInteractions();
            then(asyncMessageService).shouldHaveNoInteractions();
        }
    }
}
