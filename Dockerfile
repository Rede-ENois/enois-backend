FROM eclipse-temurin:25-jdk-alpine@sha256:1e20ead9eec7234db293f1913438090d533ed47c0b9e12dc8c7627a802a44ed6 AS builder
WORKDIR /app
RUN apk add --no-cache ca-certificates curl
ARG OPENNSFW_MODEL_COMMIT=1cbe63c555cb147fb04668e2568517c9bd2379e2
RUN mkdir -p /app/model \
    && curl --fail --location --retry 3 \
      "https://raw.githubusercontent.com/gawryco/opennsfw-onnx/${OPENNSFW_MODEL_COMMIT}/src/opennsfw_onnx/open-nsfw.onnx" \
      --output /app/model/open-nsfw.onnx \
    && echo "864bb37bf8863564b87eb330ab8c785a79a773f4e7c43cb96db52ed8611305fa  /app/model/open-nsfw.onnx" | sha256sum --check --status
COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN ./mvnw dependency:go-offline -q || true
COPY src src
RUN ./mvnw package -DskipTests -q

FROM eclipse-temurin:25-jre-noble@sha256:b4c93a50fc67612798db73d68ca3b0ee4ebdd51736e59cca370e689b9797037e
WORKDIR /app
COPY --from=builder /app/target/rede-0.0.1-SNAPSHOT.jar app.jar
COPY --from=builder /app/model/open-nsfw.onnx /app/data/open-nsfw.onnx
# ffmpeg remove metadados de vídeo/áudio com `-c copy` (sem transcodificar) no proxy de mídia.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg libheif1 \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /app/data \
    && groupadd --system appgroup && useradd --system --gid appgroup --no-create-home appuser \
    && chown -R appuser:appgroup /app
USER appuser
EXPOSE 8081
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]
