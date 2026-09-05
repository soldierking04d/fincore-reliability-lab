FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
ARG MAVEN_SETTINGS=
COPY config/maven/settings-tencent.xml /opt/fincore/settings-tencent.xml
COPY pom.xml .
RUN if [ -n "$MAVEN_SETTINGS" ]; then \
        mvn -q -s "$MAVEN_SETTINGS" -Dmaven.test.skip=true -DexcludeScope=test dependency:go-offline; \
    else \
        mvn -q -Dmaven.test.skip=true -DexcludeScope=test dependency:go-offline; \
    fi
COPY src src
RUN if [ -n "$MAVEN_SETTINGS" ]; then \
        mvn -q -s "$MAVEN_SETTINGS" package -Dmaven.test.skip=true; \
    else \
        mvn -q package -Dmaven.test.skip=true; \
    fi

FROM eclipse-temurin:21.0.8_9-jre-noble
WORKDIR /app
COPY --from=build /workspace/target/fincore-reliability-lab-*.jar app.jar
COPY config/jvm /app/config/jvm
COPY scripts/jvm/docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod 0755 /app/docker-entrypoint.sh \
    && mkdir -p /var/log/fincore
EXPOSE 8080
ENTRYPOINT ["/app/docker-entrypoint.sh"]
