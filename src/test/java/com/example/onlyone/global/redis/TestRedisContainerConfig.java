package com.example.onlyone.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.wait.strategy.Wait;

@Testcontainers
public abstract class TestRedisContainerConfig {

    private static final DockerImageName REDIS_IMAGE =
            DockerImageName.parse("redis:7.2-alpine");

    @Container
    @SuppressWarnings("resource")
    public static final GenericContainer<?> REDIS =
            new GenericContainer<>(REDIS_IMAGE)
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void overrideRedisProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }
}
