package com.example.onlyone;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.core.parameters.P;

@SpringBootApplication
@EnableJpaAuditing
@EnableAsync
@EnableRetry
@EnableScheduling
@EnableRetry
@OpenAPIDefinition(
        servers = {
                @Server(url = "https://api.buddkit.p-e.kr", description = "Production Server"),
                @Server(url = "http://localhost:8080", description = "Local Development Server")
        }
)
public class OnlyoneApplication {

    public static void main(String[] args) {
        SpringApplication.run(OnlyoneApplication.class, args);

    }
}