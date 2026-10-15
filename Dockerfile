# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon dependencies > /dev/null

COPY src ./src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon buildFatJar

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd --system --no-create-home health
COPY --from=build /src/build/libs/health-all.jar ./health.jar

ENV PORT=8080 \
    HEALTH_CONFIG=/config/health.json
EXPOSE 8080

USER health
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/health.jar"]
