# syntax=docker/dockerfile:1
# Builds the universal archive and the .deb and .rpm packages on any machine with Docker:
#   docker build -f packaging/Dockerfile --output build/dist .
# For arm64 packages, add --platform linux/arm64 (emulated on another architecture: slower).

FROM goreleaser/nfpm:v2.49.0 AS nfpm

# ---- Interface and application, built once whatever the target architecture ----
FROM --platform=$BUILDPLATFORM node:24-alpine AS web
WORKDIR /build/web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
RUN npm run build

FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS app
RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src/ src/
COPY --from=web /build/web/dist web/dist
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package

# ---- Archive, Java runtime and packages, on the target architecture ----
FROM eclipse-temurin:21-jdk AS dist
RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip zip \
    && rm -rf /var/lib/apt/lists/*
COPY --from=nfpm /usr/bin/nfpm /usr/local/bin/nfpm
WORKDIR /build
COPY LICENSE ./
COPY packaging/ packaging/
COPY --from=app /build/target/lore.jar target/lore.jar
RUN sh packaging/package.sh all

FROM scratch
COPY --from=dist /build/build/dist/ /
