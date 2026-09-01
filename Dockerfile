FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src src
RUN mvn -q package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/fincore-reliability-lab-*.jar app.jar
COPY config/jvm /app/config/jvm
COPY scripts/jvm/docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod 0755 /app/docker-entrypoint.sh \
    && mkdir -p /var/log/fincore
EXPOSE 8080
ENTRYPOINT ["/app/docker-entrypoint.sh"]
