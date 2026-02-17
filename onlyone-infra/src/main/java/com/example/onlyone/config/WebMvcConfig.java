package com.example.onlyone.global.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 설정 - SSE 및 비동기 요청 최적화
 * AsyncRequestTimeoutException 방지를 위한 타임아웃 설정
 */
@Slf4j
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${app.notification.sse-timeout-millis:600000}")  // 10분
    private long sseTimeoutMillis;

    @Value("${server.servlet.session.timeout:1800s}")
    private String sessionTimeout;

    @Value("${app.cors.allowed-origins:http://localhost:8080,http://localhost:5173}")
    private String[] corsAllowedOrigins;

    /**
     * 비동기 요청 설정 - SSE 연결 타임아웃 최적화
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        // SSE 연결을 위한 충분한 타임아웃 설정 (기본 30초 → 10분)
        configurer.setDefaultTimeout(sseTimeoutMillis);
        
        log.info("Async support configured: timeout={}ms ({}분)", 
                sseTimeoutMillis, sseTimeoutMillis / 60000);
    }

    /**
     * CORS 설정 - SSE 연결을 위한 필수 헤더 허용
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(corsAllowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .exposedHeaders("Last-Event-ID")  // SSE 재연결용 헤더
                .maxAge(3600);
    }
}