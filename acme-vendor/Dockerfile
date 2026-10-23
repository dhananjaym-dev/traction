# syntax=docker/dockerfile:1
# Build context: the repo root (docker compose sets it), so the Gradle build sees every module.
# Build stage: Temurin 21 JDK with Gradle 8.14.3 (the wrapper's version) preinstalled, so the
# image build needs only Docker; nothing from the host's JDK or Gradle is used.
FROM gradle:8.14.3-jdk21 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle/caches \
    gradle --no-daemon -q :acme-vendor:bootJar \
    && find acme-vendor/build/libs -name '*.jar' ! -name '*-plain.jar' -exec cp {} /app.jar \;

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app.jar app.jar
EXPOSE 9090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
