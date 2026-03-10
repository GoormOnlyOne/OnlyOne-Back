package com.example.onlyone.global.reactive;

import com.example.onlyone.domain.user.dto.UserPrincipal;
import com.example.onlyone.global.filter.JwtTokenParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;

@Slf4j
@Configuration
@EnableWebSocket
@ConditionalOnProperty(name = "app.chat.websocket", havingValue = "reactive")
@RequiredArgsConstructor
public class ReactiveWebSocketConfig implements WebSocketConfigurer {

    private final ReactiveChatWebSocketHandler chatWebSocketHandler;
    private final JwtTokenParser jwtTokenParser;

    @Value("${app.chat.ws-send-timeout:5000}")
    private int sendTimeout;

    @Value("${app.chat.ws-buffer-size:65536}")
    private int bufferSize;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        WebSocketHandler decoratedHandler = new WebSocketHandlerDecorator(chatWebSocketHandler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                WebSocketSession decorated = new ConcurrentWebSocketSessionDecorator(
                        session, sendTimeout, bufferSize);
                getDelegate().afterConnectionEstablished(decorated);
            }
        };

        registry.addHandler(decoratedHandler, "/ws-reactive")
                .addInterceptors(jwtHandshakeInterceptor())
                .setAllowedOriginPatterns("*");
    }

    private HandshakeInterceptor jwtHandshakeInterceptor() {
        return new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                           WebSocketHandler wsHandler, Map<String, Object> attributes) {
                try {
                    URI uri = request.getURI();
                    String token = UriComponentsBuilder.fromUri(uri).build()
                            .getQueryParams().getFirst("token");

                    if (token == null || token.isBlank()) {
                        log.warn("Reactive WS handshake 실패: token 누락");
                        return false;
                    }

                    UserPrincipal principal = jwtTokenParser.parseToken(token);
                    attributes.put("userId", principal.getUserId());
                    log.debug("Reactive WS handshake 성공: userId={}", principal.getUserId());
                    return true;
                } catch (Exception e) {
                    log.warn("Reactive WS handshake JWT 검증 실패: {}", e.getMessage());
                    return false;
                }
            }

            @Override
            public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Exception exception) {
                // no-op
            }
        };
    }
}
