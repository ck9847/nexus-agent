# Observability 部署（Prometheus + Grafana）

`deploy/compose.yaml` 中的 `prometheus` 与 `grafana` 服务挂在
`observability` profile 下：默认 `docker compose up` 不会启动它们，
需要显式启用：

```bash
docker compose --profile observability up -d
```

## 启用前的准备

### 1. 指标抓取密码（不进 Git）

应用需要 `NEXUS_METRICS_*` 环境变量，Prometheus 需要同一个密码的
`password_file`。两步必须保持一致：

```bash
# 生成强密码（至少 32 位）并写入 secrets 文件（无换行、仅密码本身）
export NEXUS_METRICS_PASSWORD="$(openssl rand -base64 32 | tr -d '\n')"
mkdir -p deploy/secrets
printf '%s' "$NEXUS_METRICS_PASSWORD" > deploy/secrets/metrics_scrape_password

# Grafana 管理密码使用独立 secret file，不设置默认 admin 密码
export GRAFANA_ADMIN_PASSWORD="$(openssl rand -base64 32 | tr -d '\n')"
printf '%s' "$GRAFANA_ADMIN_PASSWORD" > deploy/secrets/grafana_admin_password
```

`deploy/secrets/` 目录被 `.gitignore` 忽略（只保留 `.gitkeep`），
两个密码文件绝不进入版本库。Prometheus 与 Grafana 都以只读方式挂载该目录
到 `/run/secrets/`。文件缺失时对应容器会立即报错，这是预期的快速失败。

### 2. 环境变量

compose 通过宿主机环境或 `deploy/.env` 注入：

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `NEXUS_METRICS_SCRAPE_ENABLED` | `false` | 必须为 `true`，否则 `/actuator/prometheus` 只接受 ADMIN JWT |
| `NEXUS_METRICS_USERNAME` | `prometheus` | 固定机器用户名；启用时使用其他值会令应用启动失败 |
| `NEXUS_METRICS_PASSWORD` | 空 | 与 secrets 文件内容一致，至少 32 位 |
| `PROMETHEUS_PORT` | `9090` | 本机访问端口 |
| `GRAFANA_PORT` | `3000` | 本机访问端口 |
| `GRAFANA_ADMIN_USER` | `admin` | Grafana 管理员用户名 |
| `GRAFANA_ADMIN_USER` | `admin` | Grafana 管理员用户名 |

Grafana 密码不通过 `.env` 注入，固定从
`deploy/secrets/grafana_admin_password` 读取；仓库没有可工作的默认密码。

## 网络与端口

- Prometheus 与 Grafana 的端口**只绑定 `127.0.0.1`**，不对外暴露。
  本机访问：`http://127.0.0.1:9090`（Prometheus）、
  `http://127.0.0.1:3000`（Grafana）。
- 容器间全部走 compose 内网 `nexus-network`：Prometheus 抓取
  `app:8080/actuator/prometheus`，Grafana 数据源指向
  `http://prometheus:9090`，均不经过宿主机端口映射。

## 版本与资源

- 镜像固定版本、禁止 `latest`：`prom/prometheus:v3.13.2`、
  `grafana/grafana:13.1.3`。
- 两者均有 `restart: unless-stopped`、healthcheck 与资源上限
  （各 `1.0` CPU / `1g` 内存，按环境容量调整）。
- 配置全部只读挂载（`:ro`）；数据落在命名卷 `prometheus-data` /
  `grafana-data`。

## 目录结构

```
deploy/observability/
├── README.md                 # 本文件
├── prometheus/
│   ├── prometheus.yml        # 抓取配置（15s、password_file、labeldrop）
│   ├── recording-rules.yml   # 7 条记录规则
│   ├── alert-rules.yml       # 10 条告警规则
│   └── README.md             # Prometheus 部署与阈值说明
└── grafana/
    ├── provisioning/
    │   ├── datasources/prometheus.yml
    │   └── dashboards/nexus-agent.yml
    ├── dashboards/nexus-agent-overview.json
    └── README.md             # Grafana 面板与变量白名单说明
```

统一事故处理入口见
[`docs/operations-runbook.md`](../../docs/operations-runbook.md)。
