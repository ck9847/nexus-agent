# 演示环境部署

在公网服务器上以已发布镜像运行 NexusAgent 演示栈。
镜像来自 GHCR（由 release 流水线构建、cosign 签名并附 SBOM）。

## 服务器准备

1. 安装 Docker Engine 与 compose 插件；
2. clone 本仓库（只需要 `deploy/demo/`）；
3. 准备 `.env`（与本文件同目录，绝不提交 Git）：

```dotenv
# 必填
DEMO_MYSQL_ROOT_PASSWORD=<32+ 随机串>
DEMO_MYSQL_PASSWORD=<32+ 随机串>
DEMO_JWT_SECRET=<Base64，>= 32 字节解码长度>

# 有域名（自动 TLS）
DEMO_DOMAIN=demo.example.com

# 无域名：不启用 edge profile，改用下面的直接端口映射
# （把 compose 中 app 的 ports 打开：80:8080）

# 可选：接入真实模型供应商
DEMO_OPENAI_ENABLED=true
DEMO_OPENAI_BASE_URL=https://api.openai.com/v1
DEMO_OPENAI_API_KEY=sk-...
```

`DEMO_JWT_SECRET` 生成示例：

```bash
openssl rand -base64 48
```

## 启动

```bash
cd deploy/demo

# 有域名（Caddy 自动 HTTPS）
docker compose --profile edge up -d --wait

# 无域名（HTTP 演示）
docker compose up -d --wait
```

## 演示数据

首次启动后用 `scripts/demo.ps1 -BaseUrl https://<域名或IP>`
跑一遍端到端流程（bootstrap 演示租户 → 建单 turn → 查询工单）。
`-SkipStream` 可在未配置真实模型供应商时验证非模型链路。

## 运维

- 日志：`docker compose logs -f app`
- 升级：修改 `.env` 中 `DEMO_IMAGE` 版本 →
  `docker compose pull && docker compose up -d`
- 备份：`docker run --rm -v nexus-demo-demo-mysql-data:/db -v $PWD:/out alpine tar czf /out/mysql-backup.tgz /db`
