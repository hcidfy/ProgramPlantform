# 后端镜像：多阶段构建，服务器上不需要装 JDK/Maven（build context = services/app）
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# 先拉依赖再拷源码，改代码可复用依赖层
COPY pom.xml .
RUN mvn -B -q -DskipTests dependency:go-offline || true
COPY src ./src
RUN mvn -B -q -DskipTests package

# 运行镜像用 JDK 而不是 JRE：判题要 javac（PRD §7.4）；并装 python3 供 Python 判题
FROM eclipse-temurin:21-jdk
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl python3 util-linux ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 # 低权限判题用户：家目录不可写、无 shell、无 sudo（PRD §7.4 最低加固）
 && useradd --system --uid 2000 --no-create-home --shell /usr/sbin/nologin judge \
 && mkdir -p /app/logs /data/judge \
 && chmod 0755 /data/judge && chown judge:judge /data/judge

WORKDIR /app
COPY --from=build /build/target/app.jar /app/app.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod 0755 /app/docker-entrypoint.sh
ENV TZ=Asia/Shanghai \
    LOG_DIR=/app/logs \
    APP_JUDGE_DIR=/data/judge
EXPOSE 8080
# 进程以 root 起（要能 runuser 降权），判题子进程用 runuser 切到 judge 用户
ENTRYPOINT ["/app/docker-entrypoint.sh"]
