# Prometheus 监控（Nexus Agent）

本目录包含 Nexus Agent 的 Prometheus 抓取配置、记录规则与告警规则。

## 文件

| 文件 | 说明 |
| --- | --- |
| `prometheus.yml` | 抓取配置：15 秒抓取 `app:8080/actuator/prometheus`，HTTP Basic（password_file），加载两组规则，标签治理 |
| `recording-rules.yml` | 7 条记录规则（5xx 比例、HTTP/turn/model P95、turn/model/tool 失败比例） |
| `alert-rules.yml` | 10 条告警规则（severity / summary / description / for / runbook_url） |

## 部署前提（应用侧）

必须先启用第一部分实现的指标抓取专用身份：

```bash
NEXUS_METRICS_SCRAPE_ENABLED=true
NEXUS_METRICS_USERNAME=prometheus
NEXUS_METRICS_PASSWORD=<至少 32 位的强密码>
```

未启用时 `/actuator/prometheus` 保持 ADMIN JWT 访问，Prometheus 的
Basic 抓取会得到 401。

## 抓取密码（password_file）

密码绝不写入任何配置文件。运行时单独挂载一个只读 secrets 文件到
`/run/secrets/metrics_scrape_password`：

```bash
# 创建（文件只含密码本身，无换行，权限 0400）
printf '%s' "$NEXUS_METRICS_PASSWORD" > /run/secrets/metrics_scrape_password
chmod 0400 /run/secrets/metrics_scrape_password
```

在 compose 部署中由 `deploy/secrets/` 目录整体挂载（见
`deploy/observability/README.md`）。注意：secrets 挂载点不要放在
`/etc/prometheus` 之下——配置文件目录是只读挂载，只读父目录内
无法创建嵌套挂载点，Prometheus 会启动失败。

## 运行方式

```bash
docker run --rm -p 9090:9090 \
  -v "$(pwd)/deploy/observability/prometheus:/etc/prometheus:ro" \
  -v /run/secrets:/run/secrets:ro \
  prom/prometheus:v3.13.2 \
  --config.file=/etc/prometheus/prometheus.yml \
  --storage.tsdb.path=/prometheus \
  --storage.tsdb.retention.time=15d
```

数据保留期使用 Prometheus 默认 `--storage.tsdb.retention.time=15d`
（15 天），无需额外调参；此处显式写出以便审计。

`rule_files` 使用相对路径，相对于本配置文件所在目录解析，因此整目录
挂载后即生效。

## 标签治理（不采集高基数/租户标签）

抓取侧在 `metric_relabel_configs` 中无条件丢弃以下标签名：

- `tenantId / tenant_id`、`userId / user_id`、`conversationId / conversation_id`
- `requestId / request_id`、`traceId / trace_id`、`messageId / message_id`
- `clientIp / client_ip`、`ipAddress / ip_address`

这是与应用侧标签白名单（`ConversationTurnMetrics`）相互印证的纵深防御。
新增指标时禁止引入上述标签或任何以租户/用户/请求 ID 命名的标签。

## 告警阈值与 runbook

`alert-rules.yml` 中的每条告警都带有可直接打开的 GitHub
`runbook_url`。统一入口为
[`docs/operations-runbook.md`](../../../docs/operations-runbook.md)。
阈值均为起点值，按环境容量调参：

| 告警 | severity | for | 阈值 |
| --- | --- | --- | --- |
| NexusAgentDown | critical | 1m | 抓取失败 |
| HighHttp5xxRate | warning | 5m | 5xx 比例 > 5% |
| HighConversationTurnFailureRate | warning | 5m | turn 失败率 > 10% |
| ModelRateLimitSpike | warning | 5m | RATE_LIMIT > 0.05/s |
| ModelAuthenticationFailure | critical | 1m | AUTHENTICATION > 0 次/10m |
| ToolExecutionFailureSpike | warning | 5m | FAILED/CONFLICT > 0.02/s |
| SseCapacityRejected | warning | 1m | 拒绝 > 0 次/10m |
| SseActiveConnectionLeakSuspected | warning | 30m | 活跃连接 > 50 |
| HikariConnectionPoolExhaustion | warning | 5m | 等待连接 > 3 |
| JvmHeapUsageHigh | critical | 5m | 堆使用率 > 90% |

## 校验

```bash
promtool check config /etc/prometheus/prometheus.yml
promtool check rules /etc/prometheus/recording-rules.yml /etc/prometheus/alert-rules.yml
```

HTTP、Conversation Turn 与 Model Timer 已在应用配置中显式启用有限
histogram/SLO bucket；没有这些 `*_bucket` 时，P50/P95/P99 面板和记录规则
均视为部署失败。`observability-smoke.ps1` 会通过 Prometheus Query API
验证 bucket 以及三条 P95 recording series。
