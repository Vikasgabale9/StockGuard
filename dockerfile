# ---------- Stage 1: Build ----------
FROM maven:3.9-eclipse-temurin-25 AS builder

WORKDIR /build

# Download dependencies first for better layer caching
COPY pom.xml .
RUN mvn -B dependency:go-offline

# Build executable JAR
COPY src ./src
RUN mvn -B clean package -DskipTests


# ---------- Stage 2: Runtime ----------
FROM eclipse-temurin:25-jre-alpine

# Create non-root application user
RUN addgroup -S app && adduser -S app -G app

WORKDIR /app

# Copy JAR with correct ownership
COPY --from=builder --chown=app:app /build/target/*.jar app.jar

USER app

# Render injects PORT at runtime
ENV PORT=8080
EXPOSE 8080

# JVM memory and thread tuning
ENTRYPOINT ["sh", "-c", "exec java -XX:MaxRAMPercentage=65 -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -Xss512k -Dserver.port=${PORT:-8080} -Dspring.profiles.active=prod -jar app.jar"]