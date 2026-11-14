# Build from the repository root:  docker build -f services/user-service/Dockerfile .
# Uses the Maven Wrapper so the image builds with the same Maven version as local/CI builds.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
COPY services/user-service/pom.xml services/user-service/pom.xml
# Cache dependencies in their own layer so source edits don't re-download everything
RUN ./mvnw -q -B -pl services/user-service -am dependency:go-offline
COPY services/user-service/src services/user-service/src
RUN ./mvnw -q -B -pl services/user-service -am -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 app
WORKDIR /app
COPY --from=build /workspace/services/user-service/target/user-service-*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
