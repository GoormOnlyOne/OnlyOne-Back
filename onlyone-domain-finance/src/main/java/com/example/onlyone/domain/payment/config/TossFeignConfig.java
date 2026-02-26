package com.example.onlyone.domain.payment.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import feign.codec.Encoder;
import feign.jackson.JacksonEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Slf4j
@Configuration
@Profile("!loadtest")
@EnableFeignClients(basePackages = "com.example.onlyone.domain.payment.feign")
public class TossFeignConfig implements RequestInterceptor {
    private static final String AUTH_HEADER_PREFIX = "Basic ";
    @Value("${payment.toss.test_secret_api_key}")
    private String testSecretKey;

    @Override
    public void apply(final RequestTemplate template) {
        final String authHeader = createPaymentAuthorizationHeader();
        template.header("Authorization", authHeader);
    }

    private String createPaymentAuthorizationHeader() {
        final byte[] encodedBytes = Base64.getEncoder().encode((testSecretKey + ":").getBytes(StandardCharsets.UTF_8));
        return AUTH_HEADER_PREFIX + new String(encodedBytes);
    }

    @Bean
    public Encoder feignEncoder() {
        return new JacksonEncoder();
    }

}

