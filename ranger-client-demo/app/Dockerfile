# syntax=docker/dockerfile:1
# Ranger Client demo app. Build context: repository root (see ranger-client-demo/docker-compose.yml).
# Stage 1 builds the SDK from this checkout, so SDK changes show up in the demo with `docker compose up --build`.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY spec/ spec/
COPY java/ java/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -f java/pom.xml install -DskipTests \
        -pl ranger-sdk-spring-boot-starter,ranger-sdk-policy-as-code -am
COPY ranger-client-demo/app/pom.xml ranger-client-demo/app/pom.xml
COPY ranger-client-demo/app/src/ ranger-client-demo/app/src/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -f ranger-client-demo/app/pom.xml package -DskipTests

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --home-dir /app demo
WORKDIR /app
COPY --from=build /src/ranger-client-demo/app/target/ranger-client-demo.jar /app/app.jar
USER demo
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
