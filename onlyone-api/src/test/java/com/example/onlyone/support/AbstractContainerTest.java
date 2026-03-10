package com.example.onlyone.support;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

/**
 * Redis + Kafka + Elasticsearch 전체 컨테이너 베이스 클래스.
 * JVM당 1회 기동하며, 테스트 클래스가 상속하면 컨테이너를 공유한다.
 */
@ActiveProfiles("test")
public abstract class AbstractContainerTest {

    static final GenericContainer<?> REDIS;
    static final KafkaContainer KAFKA;
    static final GenericContainer<?> ELASTICSEARCH;

    static {
        REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
        REDIS.start();

        KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));
        KAFKA.start();

        ELASTICSEARCH = new GenericContainer<>(
                new ImageFromDockerfile("es-nori-test", false)
                        .withDockerfileFromBuilder(builder -> builder
                                .from("docker.elastic.co/elasticsearch/elasticsearch:8.13.0")
                                .run("bin/elasticsearch-plugin install analysis-nori")
                                .run("mkdir -p config/analysis")
                                .run("echo '' > config/analysis/club-stopwords.txt")
                                .run("echo '' > config/analysis/club-synonyms.txt")
                                .build()))
                .withExposedPorts(9200)
                .withEnv("xpack.security.enabled", "false")
                .withEnv("discovery.type", "single-node")
                .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
                .waitingFor(Wait.forHttp("/_cluster/health")
                        .forPort(9200)
                        .forStatusCode(200));
        ELASTICSEARCH.start();
    }

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry r) {
        // Redis
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", REDIS::getFirstMappedPort);
        r.add("spring.data.redis.password", () -> "");
        // Kafka
        r.add("spring.kafka.enabled", () -> "true");
        r.add("spring.kafka.default-bootstrap-servers", KAFKA::getBootstrapServers);
        r.add("spring.kafka.producer.common-config.bootstrap-servers",
                () -> List.of(KAFKA.getBootstrapServers()));
        r.add("spring.kafka.consumer.common-config.bootstrap-servers",
                () -> List.of(KAFKA.getBootstrapServers()));
        // Elasticsearch
        r.add("spring.elasticsearch.uris",
                () -> "http://" + ELASTICSEARCH.getHost() + ":" + ELASTICSEARCH.getMappedPort(9200));
    }
}
