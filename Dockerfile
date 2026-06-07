FROM eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /app
COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN ./mvnw dependency:go-offline -q || true
COPY src src
RUN ./mvnw package -DskipTests -q

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/rede-0.0.1-SNAPSHOT.jar app.jar
RUN mkdir -p /app/data
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
