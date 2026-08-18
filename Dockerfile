# Stage 1: build with Maven (no local toolchain needed)
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

# Stage 2: slim runtime image
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/diff-review-service-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
