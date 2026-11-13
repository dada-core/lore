# syntax=docker/dockerfile:1
# Lore image: the interface is built with Node.js, the application with Maven, and both run
# on a Java 21 runtime, under an unprivileged user. The first two stages run on the build
# machine whatever the target platform: only the runtime stage differs between amd64 and arm64.

# ---- Interface ----
FROM --platform=$BUILDPLATFORM node:24-alpine AS web
WORKDIR /build/web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
RUN npm run build

# ---- Application ----
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS app
# The Maven wrapper checks the SHA-256 of the .zip distribution; without unzip it would fetch the .tar.gz.
RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src/ src/
COPY --from=web /build/web/dist web/dist
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package \
    && cp target/lore.jar /build/lore.jar

# ---- Runtime ----
FROM eclipse-temurin:21-jre
RUN groupadd --system lore \
    && useradd --system --gid lore --home-dir /var/lib/lore --shell /usr/sbin/nologin lore \
    && mkdir -p /var/lib/lore/files \
    && chown -R lore:lore /var/lib/lore
COPY --from=app /build/lore.jar /opt/lore/lore.jar
COPY --chmod=755 docker/lore /usr/local/bin/lore

USER lore
WORKDIR /var/lib/lore
VOLUME /var/lib/lore/files
EXPOSE 8080

# Ready once migrated and connected to PostgreSQL. No curl in the image: bash opens the socket.
HEALTHCHECK --interval=10s --timeout=5s --start-period=90s --retries=5 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /actuator/health/readiness HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n' >&3 && grep -q '\"UP\"' <&3"]

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/opt/lore/lore.jar"]
