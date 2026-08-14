# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /workspace

# 先复制依赖清单以便利用 Docker 层缓存；随后复制完整源码构建。
COPY pom.xml ./
COPY mvnw ./
COPY .mvn .mvn
COPY nexus-common nexus-common
COPY nexus-identity nexus-identity
COPY nexus-ticket nexus-ticket
COPY nexus-agent nexus-agent
COPY nexus-audit nexus-audit
COPY nexus-bootstrap nexus-bootstrap

# Windows 检出的 mvnw 可能带 CRLF 行尾，会导致 Linux shebang 失效；
# 统一转为 LF 并确保可执行。
RUN sed -i 's/\r$//' ./mvnw && chmod +x ./mvnw

RUN ./mvnw --batch-mode --no-transfer-progress \
    -pl nexus-bootstrap -am package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-jammy AS runtime

# curl 供 Docker 健康检查使用；安装后清理缓存以减小镜像体积。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# 创建非 root 用户运行应用。
RUN useradd --system --uid 10001 --create-home appuser

WORKDIR /app

COPY --from=build \
    /workspace/nexus-bootstrap/target/nexus-bootstrap-0.1.0-SNAPSHOT.jar \
    /app/app.jar

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
