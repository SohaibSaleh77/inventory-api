# syntax=docker/dockerfile:1
# ============================================================================
# inventory-api - Inventory Management System
# Multi-stage build: Maven + JDK 21 builder -> slim JRE 21 runtime.
#
# The build produces a thin jar (target/inventory-api.jar) plus its runtime dependencies
# in target/lib/. Tests are NOT run here - CI (mvn verify) gates releases.
# ============================================================================

# ---------------------------------------------------------------------------
# Stage 1: build (mvn package, skip tests)
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# Cache dependencies first: this layer only rebuilds when pom.xml changes.
COPY pom.xml ./
RUN mvn -q -B dependency:go-offline

# Build the application jar + copy-dependencies into target/lib.
COPY src ./src
RUN mvn -q -B -DskipTests package

# ---------------------------------------------------------------------------
# Stage 2: runtime (JRE only, non-root, curl for HEALTHCHECK)
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy AS runtime

# curl is required by the in-image HEALTHCHECK.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# Dedicated non-root user (uid/gid 10001, matches the k8s manifests).
RUN groupadd --gid 10001 inventory \
 && useradd --uid 10001 --gid 10001 --home /opt/inventory-api --shell /usr/sbin/nologin inventory

WORKDIR /opt/inventory-api

# Thin jar + dependency directory from the builder stage.
COPY --from=builder --chown=10001:10001 /build/target/inventory-api.jar ./inventory-api.jar
COPY --from=builder --chown=10001:10001 /build/target/lib ./lib

# OCI image metadata.
LABEL org.opencontainers.image.title="inventory-api" \
      org.opencontainers.image.description="inventory-api - production-grade inventory management backend (Java 21, zero-framework, MySQL 8)" \
      org.opencontainers.image.version="1" \
      org.opencontainers.image.source="https://github.com/SohaibSaleh77/inventory-api"

# Containers must bind all interfaces so the port can be mapped/published.
ENV IMS_HTTP_HOST=0.0.0.0 \
    IMS_HTTP_PORT=8080

EXPOSE 8080

# Liveness probe inside the container (readiness is orchestrator-managed).
# Note: hardcoded to port 8080; adjust if you override IMS_HTTP_PORT.
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
  CMD curl -fsS http://localhost:8080/api/health/live || exit 1

USER 10001:10001

# exec form: java runs as PID 1 and receives SIGTERM directly (clean drain).
# The classpath wildcard (lib/*) is expanded by the JVM itself.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-cp", "inventory-api.jar:lib/*", "com.ims.Main"]
