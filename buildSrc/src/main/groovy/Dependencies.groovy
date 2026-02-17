class Versions {
    static final String SPRING_BOOT = '3.5.4'
    static final String SPRING_CLOUD = '2025.0.0'
    static final String SPRING_DEPENDENCY_MANAGEMENT = '1.1.7'
    static final String QUERYDSL = '5.1.0'
    static final String JWT = '0.12.4'
    static final String JJWT_API = '0.11.5'
    static final String JAVA_JWT = '3.14.0'
    static final String FIREBASE = '9.5.0'
    static final String AWS_SDK = '2.32.11'
    static final String SPRINGDOC = '2.7.0'
    static final String P6SPY = '1.9.0'
    static final String TESTCONTAINERS = '1.20.2'
    static final String COMMONS_POOL2 = '2.12.0'
}

class Dependencies {
    // Spring Boot Starters
    static final String SPRING_BOOT_STARTER_WEB = 'org.springframework.boot:spring-boot-starter-web'
    static final String SPRING_BOOT_STARTER_DATA_JPA = 'org.springframework.boot:spring-boot-starter-data-jpa'
    static final String SPRING_BOOT_STARTER_SECURITY = 'org.springframework.boot:spring-boot-starter-security'
    static final String SPRING_BOOT_STARTER_VALIDATION = 'org.springframework.boot:spring-boot-starter-validation'
    static final String SPRING_BOOT_STARTER_LOGGING = 'org.springframework.boot:spring-boot-starter-logging'
    static final String SPRING_BOOT_STARTER_OAUTH2_CLIENT = 'org.springframework.boot:spring-boot-starter-oauth2-client'
    static final String SPRING_BOOT_STARTER_WEBSOCKET = 'org.springframework.boot:spring-boot-starter-websocket'
    static final String SPRING_BOOT_STARTER_REDIS = 'org.springframework.boot:spring-boot-starter-data-redis'
    static final String SPRING_BOOT_STARTER_ELASTICSEARCH = 'org.springframework.boot:spring-boot-starter-data-elasticsearch'
    static final String SPRING_BOOT_STARTER_ACTUATOR = 'org.springframework.boot:spring-boot-starter-actuator'
    static final String SPRING_BOOT_STARTER_AOP = 'org.springframework.boot:spring-boot-starter-aop'
    static final String SPRING_BOOT_DEVTOOLS = 'org.springframework.boot:spring-boot-devtools'

    // Spring Cloud
    static final String SPRING_CLOUD_OPENFEIGN = 'org.springframework.cloud:spring-cloud-starter-openfeign'

    // Database
    static final String MYSQL_CONNECTOR = 'com.mysql:mysql-connector-j'
    static final String H2_DATABASE = 'com.h2database:h2'

    // QueryDSL
    static final String QUERYDSL_JPA = "com.querydsl:querydsl-jpa:${Versions.QUERYDSL}:jakarta"
    static final String QUERYDSL_APT = "com.querydsl:querydsl-apt:${Versions.QUERYDSL}:jakarta"

    // JWT
    static final String JJWT_API = "io.jsonwebtoken:jjwt-api:${Versions.JWT}"
    static final String JJWT_IMPL = "io.jsonwebtoken:jjwt-impl:${Versions.JWT}"
    static final String JJWT_JACKSON = "io.jsonwebtoken:jjwt-jackson:${Versions.JWT}"
    static final String JAVA_JWT = "com.auth0:java-jwt:${Versions.JAVA_JWT}"

    // Firebase
    static final String FIREBASE_ADMIN = "com.google.firebase:firebase-admin:${Versions.FIREBASE}"

    // AWS
    static final String AWS_S3 = "software.amazon.awssdk:s3:${Versions.AWS_SDK}"

    // OpenAPI
    static final String SPRINGDOC = "org.springdoc:springdoc-openapi-starter-webmvc-ui:${Versions.SPRINGDOC}"

    // Feign
    static final String FEIGN_JACKSON = 'io.github.openfeign:feign-jackson'

    // Kafka
    static final String SPRING_KAFKA = 'org.springframework.kafka:spring-kafka'

    // Retry
    static final String SPRING_RETRY = 'org.springframework.retry:spring-retry'
    static final String SPRING_ASPECTS = 'org.springframework:spring-aspects'

    // Monitoring
    static final String MICROMETER_PROMETHEUS = 'io.micrometer:micrometer-registry-prometheus'

    // Utilities
    static final String P6SPY = "com.github.gavlyukovskiy:p6spy-spring-boot-starter:${Versions.P6SPY}"
    static final String COMMONS_POOL2 = "org.apache.commons:commons-pool2:${Versions.COMMONS_POOL2}"

    // Lombok
    static final String LOMBOK = 'org.projectlombok:lombok'

    // Jakarta
    static final String JAKARTA_ANNOTATION_API = 'jakarta.annotation:jakarta.annotation-api'
    static final String JAKARTA_PERSISTENCE_API = 'jakarta.persistence:jakarta.persistence-api'

    // Test
    static final String SPRING_BOOT_STARTER_TEST = 'org.springframework.boot:spring-boot-starter-test'
    static final String SPRING_SECURITY_TEST = 'org.springframework.security:spring-security-test'
    static final String JUNIT_PLATFORM_LAUNCHER = 'org.junit.platform:junit-platform-launcher'
    static final String TESTCONTAINERS = 'org.testcontainers:testcontainers'
    static final String TESTCONTAINERS_JUNIT = 'org.testcontainers:junit-jupiter'
}
