package com.example.onlyone.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();

        registry.addEndpoint("/ws-native")
                .setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/sub");
        config.setApplicationDestinationPrefixes("/pub"); // 클라이언트 → 서버 전송용
    }

    /**
     * 클라이언트 → 서버 (inbound)
     */
    @Bean(name = "stompInboundExecutor")
    public ThreadPoolTaskExecutor stompInboundExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(32);      // CPU 코어 수 * 2
        exec.setMaxPoolSize(64);
        exec.setQueueCapacity(5000);
        exec.setThreadNamePrefix("stomp-in-");
        exec.initialize();
        return exec;
    }

    /**
     * 서버 → 클라이언트 (outbound)
     */
    @Bean(name = "stompOutboundExecutor")
    public ThreadPoolTaskExecutor stompOutboundExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(64);      // outbound는 더 크게
        exec.setMaxPoolSize(128);
        exec.setQueueCapacity(10000);
        exec.setThreadNamePrefix("stomp-out-");
        exec.initialize();
        return exec;
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.taskExecutor(stompInboundExecutor());
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.taskExecutor(stompOutboundExecutor());
    }
}
