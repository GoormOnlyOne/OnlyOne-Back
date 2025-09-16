# 1단계: 빌드
FROM gradle:8.10.0-jdk21 AS builder
WORKDIR /app
COPY . .
RUN gradle clean bootJar -x test

# 2단계: 실행 (JRE만 사용 → 이미지 크기 ↓)
FROM eclipse-temurin:21-jre
WORKDIR /app

# 실행 가능한 fat jar만 복사 (버전 번호 상관없이 *.jar)
COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "--enable-preview", "-Duser.timezone=Asia/Seoul", "-jar", "app.jar"]