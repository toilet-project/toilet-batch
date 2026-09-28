# Eclipse Temurin Java 21 경량화 이미지 사용
FROM eclipse-temurin:21-jdk-alpine
RUN apk add --no-cache python3

# 작업 디렉토리 지정
WORKDIR /app

# Gradle 빌드로 생성된 jar 파일 복사
ARG JAR_FILE=build/libs/*-SNAPSHOT.jar
COPY ${JAR_FILE} app.jar
COPY scripts/mobile-data/catalog.py scripts/mobile-data/monthly.py scripts/mobile-data/publish.py /app/mobile-data/

# 애플리케이션 실행
ENTRYPOINT ["java", "-Duser.timezone=Asia/Seoul", "-jar", "app.jar"]
