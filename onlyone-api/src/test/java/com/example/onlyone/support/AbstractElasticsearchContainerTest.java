package com.example.onlyone.support;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * Elasticsearch 단독 컨테이너 베이스 클래스.
 * nori 분석 플러그인과 분석 파일(stopwords, synonyms)을 포함한 커스텀 이미지를 빌드한다.
 */
@ActiveProfiles("test")
public abstract class AbstractElasticsearchContainerTest {

    static final GenericContainer<?> ELASTICSEARCH;

    static {
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
    static void overrideEsProps(DynamicPropertyRegistry r) {
        r.add("spring.elasticsearch.uris",
                () -> "http://" + ELASTICSEARCH.getHost() + ":" + ELASTICSEARCH.getMappedPort(9200));
    }
}
