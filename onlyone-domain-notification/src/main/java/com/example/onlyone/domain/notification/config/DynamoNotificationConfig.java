package com.example.onlyone.domain.notification.config;

import com.example.onlyone.domain.notification.entity.DynamoNotificationItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.net.URI;
import java.util.List;

/**
 * DynamoDB 알림 저장소 설정.
 * {@code app.notification.storage=dynamodb} 일 때 활성화된다.
 * <p>
 * 테이블 + GSI 자동 생성 (존재하지 않을 경우).
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "app.notification.storage", havingValue = "dynamodb")
public class DynamoNotificationConfig {

    @Value("${aws.dynamodb.endpoint:}")
    private String endpoint;

    @Value("${aws.dynamodb.region:ap-northeast-2}")
    private String region;

    @Value("${aws.dynamodb.access-key:test}")
    private String accessKey;

    @Value("${aws.dynamodb.secret-key:test}")
    private String secretKey;

    @Bean
    public DynamoDbClient dynamoDbClient() {
        var builder = DynamoDbClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)));

        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }

        DynamoDbClient client = builder.build();
        createTableIfNotExists(client);
        return client;
    }

    @Bean
    public DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient dynamoDbClient) {
        return DynamoDbEnhancedClient.builder()
                .dynamoDbClient(dynamoDbClient)
                .build();
    }

    @Bean
    public DynamoDbTable<DynamoNotificationItem> notificationTable(DynamoDbEnhancedClient enhancedClient) {
        return enhancedClient.table(DynamoNotificationItem.TABLE_NAME,
                TableSchema.fromBean(DynamoNotificationItem.class));
    }

    private void createTableIfNotExists(DynamoDbClient client) {
        try {
            client.describeTable(b -> b.tableName(DynamoNotificationItem.TABLE_NAME));
            log.info("DynamoDB table '{}' already exists", DynamoNotificationItem.TABLE_NAME);
            return;
        } catch (ResourceNotFoundException ignored) {
            // 테이블이 없으면 생성
        }

        log.info("Creating DynamoDB table '{}'", DynamoNotificationItem.TABLE_NAME);

        CreateTableRequest request = CreateTableRequest.builder()
                .tableName(DynamoNotificationItem.TABLE_NAME)
                .keySchema(
                        KeySchemaElement.builder().attributeName("userId").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("numericId").keyType(KeyType.RANGE).build()
                )
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("userId").attributeType(ScalarAttributeType.N).build(),
                        AttributeDefinition.builder().attributeName("numericId").attributeType(ScalarAttributeType.N).build(),
                        AttributeDefinition.builder().attributeName("gsiUserId").attributeType(ScalarAttributeType.N).build(),
                        AttributeDefinition.builder().attributeName("isRead").attributeType(ScalarAttributeType.N).build(),
                        AttributeDefinition.builder().attributeName("delivered").attributeType(ScalarAttributeType.N).build()
                )
                .globalSecondaryIndexes(
                        GlobalSecondaryIndex.builder()
                                .indexName(DynamoNotificationItem.GSI_USER_READ)
                                .keySchema(
                                        KeySchemaElement.builder().attributeName("gsiUserId").keyType(KeyType.HASH).build(),
                                        KeySchemaElement.builder().attributeName("isRead").keyType(KeyType.RANGE).build()
                                )
                                .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                                .build(),
                        GlobalSecondaryIndex.builder()
                                .indexName(DynamoNotificationItem.GSI_USER_DELIVERED)
                                .keySchema(
                                        KeySchemaElement.builder().attributeName("gsiUserId").keyType(KeyType.HASH).build(),
                                        KeySchemaElement.builder().attributeName("delivered").keyType(KeyType.RANGE).build()
                                )
                                .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                                .build()
                )
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build();

        client.createTable(request);

        client.waiter().waitUntilTableExists(b -> b.tableName(DynamoNotificationItem.TABLE_NAME));
        log.info("DynamoDB table '{}' created successfully", DynamoNotificationItem.TABLE_NAME);
    }
}
