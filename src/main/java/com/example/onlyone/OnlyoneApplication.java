package com.example.onlyone;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
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