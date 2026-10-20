# claudius in one image: the Spring backend serves the built frontend from classpath:/static
# next to /api on port 8080. The bash tool sandboxes each command with Landlock via landrun,
# so the container itself needs no extra privileges (default seccomp allows Landlock).
#   docker compose up --build                 # local run, see docker-compose.yml
#   docker build --target landlock-test .     # runs the Landlock tests on Linux

# landrun built from source at a pinned tag: its author advises against trusting release binaries.
FROM golang:1.25 AS landrun
RUN CGO_ENABLED=0 go install github.com/zouuup/landrun/cmd/landrun@v0.1.17

FROM node:22-slim AS frontend
WORKDIR /src
COPY claudius-frontend/package.json claudius-frontend/package-lock.json ./
RUN npm ci
COPY claudius-frontend/ ./
RUN npm run build

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
# Wrapper + build scripts first so the dependency download layer is cached.
COPY claudius-backend/gradlew claudius-backend/settings.gradle.kts claudius-backend/build.gradle.kts ./
COPY claudius-backend/gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY claudius-backend/src src

# Not part of the runtime image: the Landlock tests need Linux + landrun, which macOS dev lacks.
FROM build AS landlock-test
COPY --from=landrun /go/bin/landrun /usr/local/bin/landrun
RUN ./gradlew --no-daemon landlockTest

FROM build AS package
COPY --from=frontend /src/dist src/main/resources/static
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:25-jre
COPY --from=landrun /go/bin/landrun /usr/local/bin/landrun
COPY --from=package /src/build/libs/*.jar /app/app.jar
# Non-root: Landlock does not need root, and a command escaping it should not land as root.
RUN useradd --create-home claudius && mkdir -p /data/workspaces && chown claudius /data/workspaces
USER claudius
ENV CLAUDIUS_WORKSPACES_DIR=/data/workspaces \
    SPRING_AI_OLLAMA_BASE_URL=http://host.docker.internal:11434
VOLUME /data/workspaces
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
