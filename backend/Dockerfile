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
# Alpine 变体：更小的 OS 包面（供应链扫描面显著降低），busybox
# 自带 wget 供健康检查使用，无需 apt 安装 curl。
FROM eclipse-temurin:21-jre-alpine AS runtime

# 创建非 root 用户运行应用；同时把基础镜像内的系统包升级到
# 已修复 CVE 的补丁版本（供应链扫描门禁要求 HIGH/CRITICAL 为 0）。
RUN apk upgrade --no-cache \
    && addgroup -S -g 10001 appgroup \
    && adduser -S -u 10001 -G appgroup appuser

WORKDIR /app

# jar 名随版本变化（SNAPSHOT 或发布 tag 经 versions:set 去掉后缀），
# 以通配符匹配；package 产物只有主 jar，无 .original 扩展名冲突。
COPY --from=build \
    /workspace/nexus-bootstrap/target/nexus-bootstrap-*.jar \
    /app/app.jar

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
