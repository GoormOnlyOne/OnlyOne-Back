package com.example.onlyone.domain.chat.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
@ConditionalOnProperty(name = "app.chat.websocket", havingValue = "stomp", matchIfMissing = true)
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${app.cors.allowed-origins:http://localhost:8080,http://localhost:5173}")
    private String[] corsAllowedOrigins;

    @Value("${app.chat.stomp-inbound-core-pool:64}")
    private int inboundCorePool;

    @Value("${app.chat.stomp-inbound-max-pool:128}")
    private int inboundMaxPool;

    @Value("${app.chat.stomp-outbound-core-pool:64}")
    private int outboundCorePool;

    @Value("${app.chat.stomp-outbound-max-pool:128}")
    private int outboundMaxPool;

    @Autowired(required = false)
    @Qualifier("stompAuthInterceptor")
    private ChannelInterceptor stompAuthInterceptor;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(corsAllowedOrigins)
                .withSockJS();

        registry.addEndpoint("/ws-native")
                .setAllowedOriginPatterns(corsAllowedOrigins);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/sub");
        config.setApplicationDestinationPrefixes("/pub");
    }

    @Bean(name = "stompInboundExecutor")
    public ThreadPoolTaskExecutor stompInboundExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(inboundCorePool);
        exec.setMaxPoolSize(inboundMaxPool);
        exec.setQueueCapacity(5000);
        exec.setThreadNamePrefix("stomp-in-");
        exec.initialize();
        return exec;
    }

    @Bean(name = "stompOutboundExecutor")
    public ThreadPoolTaskExecutor stompOutboundExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(outboundCorePool);
        exec.setMaxPoolSize(outboundMaxPool);
        exec.setQueueCapacity(2000);
        exec.setThreadNamePrefix("stomp-out-");
        exec.initialize();
        return exec;
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        if (stompAuthInterceptor != null) {
            registration.interceptors(stompAuthInterceptor);
        }
        registration.taskExecutor(stompInboundExecutor());
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.taskExecutor(stompOutboundExecutor());
    }
}
