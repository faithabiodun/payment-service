FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace
COPY . .
RUN bash ./mvnw --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:21-jre

WORKDIR /app
RUN groupadd --system spring && useradd --system --gid spring --home-dir /app spring
COPY --from=build --chown=spring:spring /workspace/target/payment-service-0.0.1-SNAPSHOT.jar /app/app.jar

USER spring:spring
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
