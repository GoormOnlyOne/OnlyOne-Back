package com.example.onlyone.filter;

import com.example.onlyone.domain.user.dto.UserPrincipal;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StompAuthChannelInterceptor 단위 테스트")
class StompAuthChannelInterceptorTest {

    @InjectMocks
    private StompAuthChannelInterceptor interceptor;

    @Mock
    private JwtTokenParser jwtTokenParser;

    // ==================== fixtures ====================

    private Message<?> connectMessage(String authHeader) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        if (authHeader != null) {
            accessor.addNativeHeader("Authorization", authHeader);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<?> nonConnectMessage(StompCommand command) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private UserPrincipal activePrincipal() {
        return UserPrincipal.fromClaims("1", "10001", "ACTIVE", "ROLE_USER");
    }

    private UserPrincipal inactivePrincipal() {
        return UserPrincipal.fromClaims("2", "10002", "INACTIVE", "ROLE_USER");
    }

    // ==================== CONNECT 인증 성공 ====================

    @Nested
    @DisplayName("CONNECT 인증 성공")
    class ConnectSuccess {

        @Test
        @DisplayName("유효한 JWT 토큰으로 CONNECT 시 user가 설정된다")
        void successWithValidToken() {
            // given
            String token = "valid.jwt.token";
            Message<?> message = connectMessage("Bearer " + token);
            UserPrincipal expected = activePrincipal();
            given(jwtTokenParser.parseToken(token)).willReturn(expected);

            // when
            Message<?> result = interceptor.preSend(message, null);

            // then
            StompHeaderAccessor accessor =
                    MessageHeaderAccessor.getAccessor(result, StompHeaderAccessor.class);
            assertThat(accessor).isNotNull();
            assertThat(accessor.getUser()).isNotNull();
            assertThat(accessor.getUser()).isInstanceOf(UsernamePasswordAuthenticationToken.class);

            UsernamePasswordAuthenticationToken auth =
                    (UsernamePasswordAuthenticationToken) accessor.getUser();
            UserPrincipal principal = (UserPrincipal) auth.getPrincipal();
            assertThat(principal.getKakaoId()).isEqualTo(10001L);
            assertThat(principal.getUserId()).isEqualTo(1L);
            assertThat(auth.getAuthorities()).isNotEmpty();

            then(jwtTokenParser).should().parseToken(token);
        }
    }

    // ==================== CONNECT 인증 실패 ====================

    @Nested
    @DisplayName("CONNECT 인증 실패")
    class ConnectFailure {

        @Test
        @DisplayName("Authorization 헤더가 없으면 MessageDeliveryException")
        void failWithoutAuthHeader() {
            // given
            Message<?> message = connectMessage(null);

            // when & then
            assertThatThrownBy(() -> interceptor.preSend(message, null))
                    .isInstanceOf(MessageDeliveryException.class)
                    .hasMessageContaining("Authorization header is missing");
            then(jwtTokenParser).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("Bearer 접두사가 없는 헤더면 MessageDeliveryException")
        void failWithoutBearerPrefix() {
            // given
            Message<?> message = connectMessage("Basic some-token");

            // when & then
            assertThatThrownBy(() -> interceptor.preSend(message, null))
                    .isInstanceOf(MessageDeliveryException.class)
                    .hasMessageContaining("Authorization header is missing");
            then(jwtTokenParser).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("유효하지 않은 JWT 토큰이면 MessageDeliveryException")
        void failWithInvalidJwt() {
            // given
            String token = "invalid.jwt.token";
            Message<?> message = connectMessage("Bearer " + token);
            given(jwtTokenParser.parseToken(token)).willThrow(new JwtException("Invalid token"));

            // when & then
            assertThatThrownBy(() -> interceptor.preSend(message, null))
                    .isInstanceOf(MessageDeliveryException.class)
                    .hasMessageContaining("JWT authentication failed");
        }

        @Test
        @DisplayName("JWT 클레임 누락(IllegalArgumentException)이면 MessageDeliveryException")
        void failWithMissingClaims() {
            // given
            String token = "missing-claims.jwt.token";
            Message<?> message = connectMessage("Bearer " + token);
            given(jwtTokenParser.parseToken(token))
                    .willThrow(new IllegalArgumentException("JWT claim 'kakaoId' is missing"));

            // when & then
            assertThatThrownBy(() -> interceptor.preSend(message, null))
                    .isInstanceOf(MessageDeliveryException.class)
                    .hasMessageContaining("JWT authentication failed");
        }

        @Test
        @DisplayName("비활성 사용자이면 MessageDeliveryException")
        void failWithInactiveUser() {
            // given
            String token = "valid.jwt.token";
            Message<?> message = connectMessage("Bearer " + token);
            given(jwtTokenParser.parseToken(token)).willReturn(inactivePrincipal());

            // when & then
            assertThatThrownBy(() -> interceptor.preSend(message, null))
                    .isInstanceOf(MessageDeliveryException.class)
                    .hasMessageContaining("User account is inactive");
        }
    }

    // ==================== CONNECT 외 커맨드 ====================

    @Nested
    @DisplayName("CONNECT 외 커맨드")
    class NonConnectCommands {

        @Test
        @DisplayName("SEND 커맨드는 인증 없이 통과한다")
        void sendPassesThrough() {
            // given
            Message<?> message = nonConnectMessage(StompCommand.SEND);

            // when
            Message<?> result = interceptor.preSend(message, null);

            // then
            assertThat(result).isSameAs(message);
            then(jwtTokenParser).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("SUBSCRIBE 커맨드는 인증 없이 통과한다")
        void subscribePassesThrough() {
            // given
            Message<?> message = nonConnectMessage(StompCommand.SUBSCRIBE);

            // when
            Message<?> result = interceptor.preSend(message, null);

            // then
            assertThat(result).isSameAs(message);
            then(jwtTokenParser).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("DISCONNECT 커맨드는 인증 없이 통과한다")
        void disconnectPassesThrough() {
            // given
            Message<?> message = nonConnectMessage(StompCommand.DISCONNECT);

            // when
            Message<?> result = interceptor.preSend(message, null);

            // then
            assertThat(result).isSameAs(message);
            then(jwtTokenParser).shouldHaveNoInteractions();
        }
    }
}
