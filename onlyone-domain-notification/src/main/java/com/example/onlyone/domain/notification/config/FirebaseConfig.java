package com.example.onlyone.domain.notification.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

/**
 * Firebase Admin SDK 설정.
 * {@code app.notification.delivery=fcm} 일 때만 활성화된다.
 * classpath의 firebase-service-account.json 키 파일이 필요하다.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "app.notification.delivery", havingValue = "fcm")
public class FirebaseConfig {

    @Value("${app.notification.fcm.service-account-path:firebase-service-account.json}")
    private String serviceAccountPath;

    @PostConstruct
    public void init() {
        try {
            if (FirebaseApp.getApps().isEmpty()) {
                GoogleCredentials credentials = GoogleCredentials.fromStream(
                        new ClassPathResource(serviceAccountPath).getInputStream());

                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(credentials)
                        .build();

                FirebaseApp.initializeApp(options);
                log.info("Firebase Admin SDK 초기화 완료");
            }
        } catch (IOException e) {
            log.error("Firebase 초기화 실패: {}", e.getMessage(), e);
        }
    }

    @Bean
    public FirebaseMessaging firebaseMessaging() {
        return FirebaseMessaging.getInstance();
    }
}
