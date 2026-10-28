# ---------- Stage 1: Build ----------
FROM maven:3.9-eclipse-temurin-25 AS builder

WORKDIR /build

# Layer cache: dependencies resolve separately from source
COPY pom.xml .
RUN mvn -B dependency:go-offline

# Build the executable JAR (tests already run in GitHub Actions)
COPY src ./src
RUN mvn -B clean package -DskipTests

# ---------- Stage 2: Runtime ----------
FROM eclipse-temurin:25-jre-alpine

# Non-root user — Render runs containers as root by default, avoid that
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
RUN chown -R app:app /app
USER app

COPY --from=builder /build/target/*.jar app.jar

# Render injects PORT at runtime; default to 8080 for local `docker run`
ENV PORT=8080
EXPOSE 8080

# JVM flags tuned for 512 MB / 0.1 CPU (Render free tier)
# MaxRAMPercentage=65  → heap ~330 MB, leaves room for metaspace + OS
# MaxMetaspaceSize=128m → cap class metadata; without this Hibernate+Spring can exceed defaults
# UseSerialGC          → Serial GC beats G1 on single-core containers
# Xss512k              → 200 Tomcat threads × 1MB default stack = 200 MB wasted

ENTRYPOINT ["sh", "-c", "exec java -XX:MaxRAMPercentage=65 -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -Xss512k -Dserver.port=${PORT:-8080} -Dspring.profiles.active=prod -jar app.jar"]
git add .
git commit -m "Configure Docker and Render deployment for StockGuard"
git push origin main