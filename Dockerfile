FROM eclipse-temurin:21-jre
WORKDIR /app

# 실행 가능한 fat jar만 복사
COPY build/libs/onlyone-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java","--enable-preview","-jar","app.jar"]

