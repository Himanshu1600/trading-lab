# Build the jar with the Maven wrapper, then run it on a small JRE image as a non-root user.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN sh ./mvnw -B -q dependency:go-offline
COPY src src
RUN sh ./mvnw -B -q package -DskipTests && cp target/*.jar app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
USER app
WORKDIR /app
COPY --from=build /src/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
