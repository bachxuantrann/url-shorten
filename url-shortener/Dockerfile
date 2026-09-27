# =============================================
#  Multi-stage Dockerfile
#  Stage 1: Build JAR bằng Maven
#  Stage 2: Chạy với JRE 21 slim (nhỏ gọn, an toàn)
# =============================================

# ===== STAGE 1: BUILD =====
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Copy Maven wrapper và pom.xml trước để tận dụng Docker layer cache
# (dependencies chỉ re-download khi pom.xml thay đổi)
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

# Download dependencies
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

# Copy source code và build
COPY src ./src
RUN ./mvnw clean package -DskipTests -B

# ===== STAGE 2: RUN =====
FROM eclipse-temurin:21-jre-alpine AS runtime

# Tạo user non-root để chạy app (security best practice)
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# Copy JAR từ build stage
COPY --from=builder /app/target/*.jar app.jar

# Đổi owner về appuser
RUN chown appuser:appgroup app.jar

USER appuser

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1

# JVM tuning cho container
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-XX:+UseG1GC", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar" \
]
