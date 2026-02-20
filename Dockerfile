# 1단계: 빌드
FROM gradle:8.10.0-jdk21 AS builder
WORKDIR /app
COPY . .
RUN gradle clean :onlyone-api:bootJar

# 2단계: 실행 (JRE만 사용 → 이미지 크기 ↓)
FROM eclipse-temurin:21-jre
WORKDIR /app

# 실행 가능한 fat jar만 복사 (멀티모듈 프로젝트 - onlyone-api 모듈)
COPY --from=builder /app/onlyone-api/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", \
    "--enable-preview", \
    "-Xms512m", "-Xmx1280m", \
    "-XX:+UseZGC", \
    "-XX:+HeapDumpOnOutOfMemoryError", "-XX:HeapDumpPath=/app/heapdump.hprof", \
    "-Duser.timezone=Asia/Seoul", \
    "-jar", "app.jar"]