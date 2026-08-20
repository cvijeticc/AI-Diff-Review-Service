# Stage 1: build with Maven (no local toolchain needed)
FROM maven:3.9-eclipse-temurin-17 AS build
# Label both stages so deploy-time image pruning can be scoped to this app only.
LABEL app=diff-review-service
WORKDIR /app
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

# Stage 2: slim runtime image
FROM eclipse-temurin:17-jre
LABEL app=diff-review-service
WORKDIR /app
# Wildcard on purpose: bumping the pom version must not break the build.
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
