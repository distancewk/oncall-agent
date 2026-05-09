# ---- Build Stage ----
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /build

# Copy Maven wrapper and pom.xml first for dependency caching
COPY pom.xml ./
COPY src ./src

# Build the application JAR (skip tests since there are none yet)
RUN apk add --no-cache maven && mvn package -DskipTests -B -q

# ---- Runtime Stage ----
FROM eclipse-temurin:17-jre-alpine AS runtime

WORKDIR /app

# Create non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Copy JAR from builder stage
COPY --from=builder /build/target/*.jar app.jar

# Switch to non-root user
USER appuser

# Expose application port
EXPOSE 9900

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:9900/milvus/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
