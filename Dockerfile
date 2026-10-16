# ==============================================================================
# Multi-Stage Dockerfile for EduBridge Student Management System
# Base Runtime: Eclipse Temurin 17 JRE (Alpine)
# Architecture: Single self-contained container with built-in HttpServer & JDBC
# Target Host: Render Free Web Service / Container Cloud
# ==============================================================================

# ------------------------------------------------------------------------------
# Stage 1: Build Application and Extract Runtime Dependencies
# ------------------------------------------------------------------------------
FROM maven:3.9.6-eclipse-temurin-17 AS builder

WORKDIR /build

# Copy Maven Project Object Model
COPY pom.xml .

# Download dependencies in offline layer to optimize Docker build cache
RUN mvn dependency:go-offline -B || true

# Copy application source code
COPY src ./src

# Compile application and package JAR
RUN mvn clean package -DskipTests && \
    mvn dependency:copy-dependencies -DoutputDirectory=target/dependency -DincludeScope=runtime

# ------------------------------------------------------------------------------
# Stage 2: Minimal, Secure Production Runtime
# ------------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Create a dedicated non-root user and group for defense-in-depth container security
RUN addgroup -S edubridge && adduser -S edubridge -G edubridge

# Copy compiled JAR and runtime libraries from builder
COPY --from=builder /build/target/*.jar ./app.jar
COPY --from=builder /build/target/dependency ./libs

# Copy frontend static assets (HTML, CSS, JS, images)
COPY frontend ./frontend

# Set secure ownership of application files
RUN chown -R edubridge:edubridge /app

# Switch to unprivileged runtime user
USER edubridge

# Default environment configuration (Render overrides PORT dynamically at runtime)
ENV PORT=8080 \
    FRONTEND_DIR=/app/frontend

# Expose default HTTP port
EXPOSE 8080

# Start Java WebServer with the complete runtime classpath
CMD ["sh", "-c", "java -cp \"app.jar:libs/*\" com.studentmanagement.WebServer"]
