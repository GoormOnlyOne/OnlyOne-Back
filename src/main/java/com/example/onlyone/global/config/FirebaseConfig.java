package com.example.onlyone.global.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import java.io.IOException;
import javax.annotation.PreDestroy;
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
  public FirebaseApp firebaseApp() throws IOException {
    if (FirebaseApp.getApps().stream().noneMatch(app -> app.getName().equals(FirebaseApp.DEFAULT_APP_NAME))) {
      try {
        FirebaseOptions options = FirebaseOptions.builder()
            .setCredentials(
                GoogleCredentials.fromStream(
                    new ClassPathResource(SERVICE_ACCOUNT_PATH).getInputStream())
            )
            .build();
        log.info("Successfully initialized FirebaseApp");
        return FirebaseApp.initializeApp(options);
      } catch (IOException e) {
        log.error("Failed to initialize FirebaseApp: {}", e.getMessage(), e);
        throw new IllegalStateException("Unable to initialize FirebaseApp", e);
      }
    } else {
      log.info("FirebaseApp [DEFAULT] already exists, returning existing instance");
      return FirebaseApp.getInstance(FirebaseApp.DEFAULT_APP_NAME);
    }
  }

  @Bean
  public FirebaseMessaging firebaseMessaging(FirebaseApp firebaseApp) {
    return FirebaseMessaging.getInstance(firebaseApp);
  }

  @PreDestroy
  public void cleanup() {
    log.info("Cleaning up FirebaseApp instances");
    FirebaseApp.getApps().forEach(app -> {
      try {
        app.delete();
        log.info("Deleted FirebaseApp: {}", app.getName());
      } catch (Exception e) {
        log.error("Failed to delete FirebaseApp {}: {}", app.getName(), e.getMessage(), e);
      }
    });
  }
}