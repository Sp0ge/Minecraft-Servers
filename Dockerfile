FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2,sharing=locked mvn -B package -DskipTests
FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends python3 && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/target/network-proxy-1.0.0.jar /opt/network-proxy.jar
COPY start.py /opt/start.py
COPY server-icon.png /opt/server-icon.png
WORKDIR /data
ENTRYPOINT ["python3", "/opt/start.py"]
