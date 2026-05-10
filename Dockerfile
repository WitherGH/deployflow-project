FROM eclipse-temurin:21-jdk AS build

WORKDIR /app
COPY src ./src
COPY resources ./resources
RUN find src -name '*.java' > sources.txt \
    && javac -d out @sources.txt

FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=build /app/out ./out
COPY resources ./resources

EXPOSE 8080
CMD ["java", "-cp", "out", "com.deployflow.web.DeployFlowApp"]
