# HighHttp5xxRate

> 通用事故分级、关联查询、恢复检查和禁止操作见
> [生产运行与事故响应手册](../operations-runbook.md)。

- **告警**：`HighHttp5xxRate`
- **severity**：warning
- **指标**：`nexus:http_5xx_ratio:5m`
- **触发条件**：5 分钟滚动 5xx 比例 > 5%

## 影响

部分租户请求失败；持续升高会影响全站可用性。

## 排查步骤

1. 在 Grafana 用 `http_server_requests_seconds_count` 按
   `status`、`uri` 分解 5xx，确认集中路径。
2. 查看应用日志中对应时间段的异常堆栈（`INTERNAL_FAILED` 等）。
3. 检查是否存在级联告警（JVM 堆、Hikari 连接池、模型/工具失败）。
4. 若 5xx 来自模型或工具链路，转对应 runbook。
5. 确认最近是否发布过变更；必要时回滚。

## PromQL 与关联定位

```promql
nexus:http_5xx_ratio:5m

sum by (instance, uri, status) (
  rate(http_server_requests_seconds_count{status=~"5.."}[5m])
)

nexus:http_p95_seconds:5m
```

从失败响应获取 `X-Request-ID`、`X-Trace-ID`，按 traceId 查询容器日志；
涉及写操作时再按 tenantId + requestId/traceId 查询 `audit_logs`，确认业务写入
与审计是同时提交还是同时回滚。不要在事故记录中粘贴请求正文或 JWT。

## 临时止损

- 单一路径回归：回滚最近版本或临时停止调用该入口；
- 模型/工具依赖引发：保留查询 API，降低或暂停 SSE 流量；
- Hikari/JVM 引发：转对应专项 runbook，先控制入口并发；
- 全站失败：优先恢复上一稳定镜像，不在故障版本上连续试错。

## 根因处理与恢复条件

修复对应的 Controller/Service/SQL/依赖配置，并增加能复现该错误的回归测试。
恢复要求 5xx 比例连续两个窗口低于 5%，Readiness/Prometheus target 正常，
抽样写请求的数据库状态与审计一致，且没有新的级联告警。

## 禁止操作

- 禁止关闭 JWT、租户过滤或审计事务来降低 5xx；
- 禁止只依赖重启而不保存堆栈和关联 ID；
- 禁止将 5xx 改成 200/空响应掩盖业务失败。

## 升级

比例超过 20% 持续 15 分钟，升级到值班负责人。
