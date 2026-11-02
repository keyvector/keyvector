# Keyvector: one image for every command (run, backfill, resync, redrive, query, check).
#   docker build -t keyvector .
#   docker run --rm -v $PWD/keyvector.conf:/app/keyvector.conf keyvector check

FROM maven:3.9-amazoncorretto-17 AS build
WORKDIR /src
COPY pom.xml ./
COPY keyvector-core/pom.xml keyvector-core/
COPY keyvector-connector/pom.xml keyvector-connector/
COPY keyvector-app/pom.xml keyvector-app/
RUN mvn -B -q -pl keyvector-app -am dependency:go-offline -DexcludeGroupIds=dev.keyvector || true
COPY keyvector-core/src keyvector-core/src
COPY keyvector-connector/src keyvector-connector/src
COPY keyvector-app/src keyvector-app/src
RUN mvn -B -q -DskipTests -Dspotbugs.skip=true package \
 && cp keyvector-app/target/keyvector-app-*.jar /src/keyvector.jar

FROM amazoncorretto:17-al2023-headless
RUN dnf install -y shadow-utils curl-minimal && dnf clean all \
 && useradd --uid 10001 --create-home keyvector
WORKDIR /app
COPY --from=build /src/keyvector.jar /app/keyvector.jar
COPY keyvector.example.conf /app/keyvector.conf
USER keyvector
ENV APPLICATION_CONF_LOCATION=/app/keyvector.conf \
    HEALTH_PORT=8081 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC"
EXPOSE 8081
ENTRYPOINT ["java", "--add-opens", "java.base/java.nio=ALL-UNNAMED", "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED", "-jar", "/app/keyvector.jar"]
CMD ["run"]
