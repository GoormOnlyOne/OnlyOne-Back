package com.example.onlyone.domain.notification.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@FunctionalInterface
public interface SseEmitterFactory {
  SseEmitter create(long timeoutMillis);
}
