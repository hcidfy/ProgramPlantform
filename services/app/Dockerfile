# 后端镜像：多阶段构建，服务器上不需要装 JDK/Maven（build context = services/app）
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# 先拉依赖再拷源码，改代码可复用依赖层
COPY pom.xml .
RUN mvn -B -q -DskipTests dependency:go-offline || true
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre
# curl 只用于 compose healthcheck
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /build/target/app.jar /app/app.jar
ENV TZ=Asia/Shanghai \
    LOG_DIR=/app/logs
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
