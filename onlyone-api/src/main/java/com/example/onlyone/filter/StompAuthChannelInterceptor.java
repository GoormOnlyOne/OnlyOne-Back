package com.example.onlyone.filter;

import com.example.onlyone.domain.user.dto.UserPrincipal;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * STOMP CONNECT 시 JWT 인증을 수행하는 Channel Interceptor.
 *
 * 클라이언트는 STOMP CONNECT 프레임의 native header에
 * {@code Authorization: Bearer <token>}을 포함해야 합니다.
 * 인증 성공 시 accessor.setUser()로 Principal을 설정하여
 * 이후 @MessageMapping 핸들러에서 사용할 수 있도록 합니다.
 */
@Slf4j
@Component("stompAuthInterceptor")
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenParser jwtTokenParser;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null || accessor.getCommand() != StompCommand.CONNECT) {
            return message;
        }

        String authHeader = accessor.getFirstNativeHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            log.warn("[STOMP.Auth] CONNECT without valid Authorization header");
            throw new MessageDeliveryException("Authorization header is missing or invalid");
        }

        String token = authHeader.substring(BEARER_PREFIX.length());

        try {
            UserPrincipal principal = jwtTokenParser.parseToken(token);

            if (!principal.isEnabled()) {
                log.warn("[STOMP.Auth] Inactive user attempted CONNECT: userId={}", principal.getUserId());
                throw new MessageDeliveryException("User account is inactive");
            }

            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(
                            principal, null, principal.getAuthorities());
            accessor.setUser(auth);

            log.debug("[STOMP.Auth] CONNECT authenticated: {}", principal);

        } catch (JwtException | IllegalArgumentException e) {
            log.warn("[STOMP.Auth] JWT validation failed: {}", e.getMessage());
            throw new MessageDeliveryException("JWT authentication failed: " + e.getMessage());
        }

        return message;
    }
}
