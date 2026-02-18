package com.example.onlyone.domain.user.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class KakaoConfig {

    @Bean
    public RestTemplate kakaoRestTemplate() {
        return new RestTemplate();
    }
}
