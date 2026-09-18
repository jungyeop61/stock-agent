FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY spring-backend/pom.xml ./pom.xml
COPY spring-backend/src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 jusika \
    && useradd --uid 10001 --gid 10001 --no-create-home jusika
WORKDIR /app
COPY --from=build --chown=10001:10001 /build/target/spring-backend-0.0.1-SNAPSHOT.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
