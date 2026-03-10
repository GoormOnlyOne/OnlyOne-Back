package com.example.onlyone.support;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.example.onlyone.domain.club.repository.ClubElasticsearchRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;

/**
 * Elasticsearch 통합 테스트용 설정.
 * OnlyoneApplication이 제외한 ES 자동 구성을 수동으로 구성한다.
 */
@TestConfiguration
@EnableElasticsearchRepositories(basePackageClasses = ClubElasticsearchRepository.class)
public class ElasticsearchTestConfig {

    @Bean
    public RestClient elasticsearchRestClient(
            @Value("${spring.elasticsearch.uris:http://localhost:9200}") String uris) {
        return RestClient.builder(HttpHost.create(uris)).build();
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(RestClient restClient) {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        RestClientTransport transport = new RestClientTransport(
                restClient, new JacksonJsonpMapper(objectMapper));
        return new ElasticsearchClient(transport);
    }

    @Bean(name = {"elasticsearchTemplate", "elasticsearchOperations"})
    public ElasticsearchTemplate elasticsearchTemplate(ElasticsearchClient elasticsearchClient) {
        return new ElasticsearchTemplate(elasticsearchClient);
    }
}
