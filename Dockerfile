# QuotaPilot 多阶段构建（生产镜像）
# 构建: docker build -t quotapilot:latest .
# 运行: 见 docker-compose.yml

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/quotapilot-*.jar app.jar
ENV QUOTAPILOT_REDIS_HOST=redis \
    QUOTAPILOT_REDIS_PORT=6379 \
    QUOTAPILOT_PG_URL=jdbc:postgresql://postgres:5432/quotapilot \
    QUOTAPILOT_PG_USER=quotapilot \
    QUOTAPILOT_PG_PASSWORD=changeme \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8"
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s \
  CMD curl -sf http://localhost:8080/v1/admin/alerts > /dev/null || exit 1
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar --spring.profiles.active=postgres"]
