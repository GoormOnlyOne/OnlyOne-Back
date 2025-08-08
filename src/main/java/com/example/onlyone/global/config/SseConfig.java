package com.example.onlyone.global.config;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.example.onlyone.domain.notification.service.SseEmitterFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SseConfig {
  @Bean
  public SseEmitterFactory sseEmitterFactory() {
    return SseEmitter::new;
  }
}