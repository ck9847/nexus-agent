# Grafana 监控面板（Nexus Agent）

本目录包含 Grafana provisioning 配置与 Nexus Agent 总览 Dashboard。

## 文件

| 文件 | 说明 |
| --- | --- |
| `provisioning/datasources/prometheus.yml` | Prometheus 数据源（固定 uid `prometheus`，指向 `http://prometheus:9090`） |
| `provisioning/dashboards/nexus-agent.yml` | file provider：整目录自动加载 Dashboard 到 `Nexus Agent` 文件夹 |
| `dashboards/nexus-agent-overview.json` | 总览 Dashboard（uid `nexus-agent-overview`） |

## 运行方式

```bash
docker run --rm -p 3000:3000 \
  -e GF_SECURITY_ADMIN_PASSWORD__FILE=/run/secrets/grafana_admin_password \
  -v "$(pwd)/deploy/observability/grafana/provisioning:/etc/grafana/provisioning:ro" \
  -v "$(pwd)/deploy/observability/grafana/dashboards:/var/lib/grafana/dashboards:ro" \
  -v "$(pwd)/deploy/secrets:/run/secrets:ro" \
  grafana/grafana:13.1.3
```

需要与 `../prometheus/` 中的 Prometheus 实例在同一网络（数据源 URL
`http://prometheus:9090` 按服务名解析；独立部署时改数据源 URL）。
管理员密码文件必须是单行、无 BOM/尾随换行且至少 16 位；仓库不提供默认密码。

## Dashboard 变量白名单

Dashboard 只允许以下模板变量（全部为多选、含 All）：

- `instance`（`up{job="nexus-agent"}` 的实例标签）
- `provider`（模型提供方，当前为 OPENAI）
- `outcome`（turn / 模型 / 工具三类 outcome 的并集）
- `error_category`（模型失败的低基数错误分类）

禁止加入 tenant、user、conversation、message、ticket 等任何高基数变量；
新增面板也不得在查询里引入此类标签。这是与应用侧标签白名单与
Prometheus 抓取侧 labeldrop 一致的第三层治理。

## 面板覆盖

服务状态/uptime、HTTP 速率与 4xx/5xx 比率及 P50/P95/P99、turn 各
outcome 与 P95、模型调用成功/失败/错误类别/P95、工具五种 outcome、
SSE active 与五种结束事件、Hikari active/idle/pending/max、
JVM heap/non-heap、GC pause、CPU 与线程数。
