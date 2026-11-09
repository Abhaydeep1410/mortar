# Build the Spring Boot jar, then run it on a JRE as a non-root user.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Mortar modules are vendored as source under vendor/ (they are in no Maven repository).
COPY vendor ./vendor
RUN mvn -B -ntp -Dmaven.test.skip=true -f vendor/pom.xml install
COPY pom.xml .
COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 1001 app
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
