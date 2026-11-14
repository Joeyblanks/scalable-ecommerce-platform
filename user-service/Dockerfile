# Build from this service's own folder:  docker build -t user-service user-service/
# Uses the Maven Wrapper so the image builds with the same Maven version as local/CI builds.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
# Cache dependencies in their own layer so source edits don't re-download everything
RUN ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 app
WORKDIR /app
COPY --from=build /workspace/target/user-service-*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
