package com.example.onlyone.global.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

@Slf4j
@Configuration
public class FirebaseConfig {

  @Value("${firebase.service-account.path}")
  private String SERVICE_ACCOUNT_PATH;

  @Bean
  public FirebaseApp firebaseApp(FirebaseApp firebaseApp) {
    try {
      FirebaseOptions options = FirebaseOptions.builder()
          .setCredentials(
              GoogleCredentials.fromStream(
                  new ClassPathResource(SERVICE_ACCOUNT_PATH).getInputStream())
          ).build();
      log.info("Successfully initialized FirebaseApp");
      return FirebaseApp.initializeApp(options);
    } catch (IOException exception) {
      log.error("Failed to initialize FirebaseApp{}", exception.getMessage());
      return null;
    }
  }

  @Bean
  public FirebaseMessaging firebaseMessaging(FirebaseApp firebaseApp) {
    return FirebaseMessaging.getInstance(firebaseApp);
  }
}
