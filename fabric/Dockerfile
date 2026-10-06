FROM gradle:8.14.3-jdk21 AS build
WORKDIR /src
COPY settings.gradle build.gradle ./
COPY src src
ARG TARGETARCH
RUN --mount=type=cache,id=kiwy-gradle-fabric-${TARGETARCH},target=/home/gradle/.gradle,sharing=locked gradle --no-daemon remapJar -x test
FROM alpine:3.22
COPY --from=build /src/build/libs/kiwy-fabric-1.0.0.jar /kiwy-fabric.jar
CMD ["sh", "-c", "cp /kiwy-fabric.jar /shared/kiwy-fabric.jar"]
