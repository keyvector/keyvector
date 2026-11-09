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
# Optional: bake a model in for embedder.type = "local", so tasks start with no network access
# to Hugging Face. Example:
#   docker build --build-arg MODEL=sentence-transformers/all-MiniLM-L6-v2 \
#                --build-arg MODEL_REVISION=1110a243fdf4706b3f48f1d95db1a4f5529b4d41 -t keyvector .
ARG MODEL=""
ARG MODEL_REVISION="main"
# Optional reranker for `keyvector query` (rerank.model), baked in the same way.
ARG RERANK_MODEL=""
ARG RERANK_MODEL_REVISION="main"
RUN dnf install -y shadow-utils curl-minimal && dnf clean all \
 && useradd --uid 10001 --create-home keyvector
WORKDIR /app
COPY --from=build /src/keyvector.jar /app/keyvector.jar
ENV KEYVECTOR_MODEL_CACHE=/app/models
RUN mkdir -p /app/models && if [ -n "$MODEL" ]; then \
      java -jar /app/keyvector.jar fetch-model "$MODEL" --revision "$MODEL_REVISION"; \
    fi && if [ -n "$RERANK_MODEL" ]; then \
      java -jar /app/keyvector.jar fetch-model "$RERANK_MODEL" --revision "$RERANK_MODEL_REVISION" --reranker; \
    fi && chown -R keyvector /app/models
COPY keyvector.example.conf /app/keyvector.conf
USER keyvector
ENV APPLICATION_CONF_LOCATION=/app/keyvector.conf \
    HEALTH_PORT=8081 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC"
EXPOSE 8081
ENTRYPOINT ["java", "--add-opens", "java.base/java.nio=ALL-UNNAMED", "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED", "-jar", "/app/keyvector.jar"]
CMD ["run"]
