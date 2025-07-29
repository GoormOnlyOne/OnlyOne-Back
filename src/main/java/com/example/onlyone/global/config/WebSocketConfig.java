package com.example.onlyone.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // → 클라이언트가 subscribe 할 때 prefix
        registry.enableSimpleBroker("/sub");
        // → @MessageMapping 메서드를 호출하기 위한 prefix
        //    (클라이언트는 이 prefix를 붙여서 메시지 전송)
        registry.setApplicationDestinationPrefixes("/pub");
        // → @SendToUser 로 보낼 때 쓰는 user prefix
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // SockJS fallback 포함, CORS 는 프로덕션 환경에 맞게 조정
        registry.addEndpoint("/ws-chat")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }
}
