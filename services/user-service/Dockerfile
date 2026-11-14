# Build from the repository root:  docker build -f services/user-service/Dockerfile .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY services/user-service/pom.xml services/user-service/pom.xml
# Cache dependencies in their own layer so source edits don't re-download everything
RUN mvn -q -B -pl services/user-service -am dependency:go-offline
COPY services/user-service/src services/user-service/src
RUN mvn -q -B -pl services/user-service -am -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 app
WORKDIR /app
COPY --from=build /workspace/services/user-service/target/user-service-*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
