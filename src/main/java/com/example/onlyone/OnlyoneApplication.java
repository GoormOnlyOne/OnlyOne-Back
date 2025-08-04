package com.example.onlyone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
public class OnlyoneApplication {

    public static void main(String[] args) {
        SpringApplication.run(OnlyoneApplication.class, args);

    }
}