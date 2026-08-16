# NexusAgent 生产运行与事故响应手册

本文是 NexusAgent 可观测性与事故响应的统一入口，适用于当前
Docker Compose 部署形态。具体告警的专项步骤位于
[`docs/runbooks/`](runbooks/)；Prometheus、Grafana 与抓取身份的部署方式见
[`deploy/observability/README.md`](../deploy/observability/README.md)。

本手册只描述当前仓库已经实现的能力，不假设 Kubernetes、分布式追踪后端、
自动扩缩容或多模型路由已经存在。

## 1. 值班目标与安全边界

事故处理的优先级依次是：

1. 保护租户数据隔离与凭据安全；
2. 阻止影响面继续扩大；
3. 恢复 API、SSE、模型和工具链路；
4. 保留日志、指标、审计和数据库现场；
5. 完成根因修复与复盘。

任何情况下都不得把以下内容复制到工单、聊天、截图或事故文档：

- `OPENAI_API_KEY`、JWT 密钥、MySQL 密码、Grafana 密码；
- Prometheus Basic Auth 密码或完整 `Authorization` header；
- 完整 JWT、system prompt、用户消息正文、工具输入 JSON；
- 未脱敏的数据库备份或 heap dump。

允许用于跨系统关联的字段只有低敏标识：`requestId`、`traceId`、稳定错误码、
资源类型、资源 ID、告警时间和实例名。资源 ID 仍应只在受控事故渠道中使用。

## 2. 入口与健康基线

默认本机入口：

| 入口 | 地址 | 认证 | 用途 |
| --- | --- | --- | --- |
| Readiness | `http://127.0.0.1:8080/actuator/health/readiness` | 无 | 判断实例能否接收流量 |
| Prometheus | `http://127.0.0.1:9090` | 仅本机 | 查询指标与告警状态 |
| Grafana | `http://127.0.0.1:3000` | Grafana 管理身份 | `Nexus Agent Overview` Dashboard |
| Metrics | `http://127.0.0.1:8080/actuator/prometheus` | metrics Basic Auth 或 ADMIN JWT | 原始抓取检查 |

健康基线：

- `docker compose --profile observability ps` 中四个容器均为 `healthy`；
- `up{job="nexus-agent"} == 1`；
- `nexus_sse_connections_active` 能随连接建立与结束回落；
- `hikaricp_connections_pending` 通常为 0；
- 最近 5 分钟没有持续的 5xx、turn、model 或 tool failure；
- Prometheus Targets 页面中 `nexus-agent` 为 `UP`，Last Scrape 无认证错误。

## 3. 严重级别与响应时间

| 级别 | 定义 | 首次响应 | 更新频率 | 示例 |
| --- | --- | --- | --- | --- |
| critical | 全站/核心链路不可用、数据安全风险、即将 OOM | 5 分钟内 | 每 15 分钟 | `NexusAgentDown`、`ModelAuthenticationFailure`、`JvmHeapUsageHigh` |
| warning | 部分请求失败、容量趋紧、持续退化 | 15 分钟内 | 每 30 分钟 | 5xx、turn failure、限流、SSE 拒绝、Hikari 等待 |

出现以下任一情况时，warning 立即升级为 critical：

- 超过 30% 的 turn 连续 15 分钟失败；
- HTTP 5xx 超过 20% 或多个租户同时受影响；
- 数据库写入/审计出现一致性异常；
- 需要重启、回滚、密钥轮换或删除数据才能恢复；
- 发现跨租户访问、凭据泄漏或审计缺失。

## 4. 标准事故流程

### 4.1 0–5 分钟：确认与止损

1. 记录告警名称、开始时间、实例、severity、当前值和 Dashboard 时间范围。
2. 打开 `Nexus Agent Overview`，同时查看 `up`、HTTP 5xx、Turn outcome、
   Model error category、Tool outcome、SSE active、Hikari pending 和 JVM heap。
3. 运行只读健康检查；不要先重启或删除容器。
4. 从一个失败响应中记录 `X-Request-ID` 和 `X-Trace-ID`，不得记录完整 JWT。
5. 判断影响面：单请求、单实例、单 provider、单工具还是全站。
6. 若仍在扩大，优先采取专项 runbook 中列出的可逆限流、流量暂停或回滚措施。

### 4.2 5–15 分钟：关联证据与定位

1. 用 `traceId` 查应用日志；用 `requestId/traceId` 查 `audit_logs`。
2. 按 Prometheus 标签分解，但只使用低基数标签：`instance`、`provider`、
   `outcome`、`error_category`、`tool`、`status`、`uri`。
3. 检查是否存在级联告警。例如 Hikari 等待会导致 5xx，模型限流会导致
   turn failure，SSE 泄漏会导致 capacity rejected。
4. 查看最近部署、配置和密钥轮换记录，确认时间是否吻合。
5. 把“观察到的事实”和“推测的根因”分开记录。

### 4.3 15–30 分钟：恢复与升级

1. 优先执行最小、可逆的恢复操作：回滚版本、恢复正确配置、降低入口并发、
   暂停真实模型流量或扩充已验证容量。
2. 需要重启前先保存最近日志、Prometheus 查询结果和必要的线程/堆证据。
3. 每次只改变一个关键变量，并记录操作时间与结果。
4. 达到升级条件时通知值班负责人；涉及密钥或数据安全时同步安全负责人。
5. 不因告警短暂恢复就立即关闭事故，必须满足第 9 节恢复条件。

## 5. requestId / traceId / 审计关联

### 5.1 从 HTTP 响应取得关联 ID

所有 HTTP 响应都会返回：

```http
X-Request-ID: <request-id>
X-Trace-ID: <trace-id>
```

客户端也可以提交格式合法的同名 header；服务端会拒绝非法值并生成安全值。
异步 SSE worker 会继承 correlation，任务结束后清理线程池 ThreadLocal/MDC。

### 5.2 查询应用日志

PowerShell 示例：

```powershell
$traceId = "replace-with-trace-id"
docker logs nexus-agent-app --since 30m 2>&1 |
  Select-String -SimpleMatch $traceId
```

日志格式中的 `[requestId|traceId]` 用于定位同一次同步请求和异步 worker。
不要按消息正文、邮箱、system prompt 或 API Key 搜索。

### 5.3 查询审计记录

先确认租户，再执行参数化只读 SQL：

```sql
SELECT id,
       tenant_id,
       actor_type,
       actor_id,
       action,
       resource_type,
       resource_id,
       tool_execution_id,
       result,
       request_id,
       trace_id,
       error_code,
       created_at
FROM audit_logs
WHERE tenant_id = ?
  AND (request_id = ? OR trace_id = ?)
ORDER BY created_at, id;
```

工具链路可继续查询：

```sql
SELECT id,
       conversation_id,
       agent_id,
       tool_call_id,
       tool_name,
       status,
       result_entity_type,
       result_entity_id,
       error_code,
       trace_id,
       started_at,
       completed_at,
       duration_ms
FROM tool_executions
WHERE tenant_id = ?
  AND trace_id = ?
ORDER BY created_at, id;
```

审计查询只用于定位状态和稳定错误码。不要把 `before_json`、`after_json`、
`input_json` 或消息正文复制到普通事故渠道。

## 6. 通用 PromQL

```promql
# 实例抓取状态
up{job="nexus-agent"}

# 5 分钟 HTTP 5xx 比例
nexus:http_5xx_ratio:5m

# HTTP / Turn / Model P95
nexus:http_p95_seconds:5m
nexus:turn_p95_seconds:5m
nexus:model_p95_seconds:5m

# Turn outcome 分布
sum by (instance, outcome) (
  rate(nexus_conversation_turn_seconds_count[5m])
)

# 模型失败类别
sum by (instance, provider, error_category) (
  rate(nexus_model_call_seconds_count{outcome="failure"}[5m])
)

# 工具 outcome
sum by (instance, tool, outcome) (
  rate(nexus_tool_execution_total[5m])
)

# SSE 建立与结束是否大致守恒
sum by (instance) (increase(nexus_sse_connections_established_total[30m]))
sum by (instance) (
  increase(nexus_sse_connections_completed_total[30m])
  + increase(nexus_sse_connections_error_total[30m])
  + increase(nexus_sse_connections_client_disconnect_total[30m])
  + increase(nexus_sse_connections_timeout_total[30m])
)

# 数据库连接池
max by (instance, pool) (hikaricp_connections_active)
max by (instance, pool) (hikaricp_connections_pending)
```

## 7. 告警目录

| 告警 | 级别 | 首要判断 | 专项手册 |
| --- | --- | --- | --- |
| NexusAgentDown | critical | 进程宕机还是抓取认证/网络故障 | [Runbook](runbooks/NexusAgentDown.md) |
| HighHttp5xxRate | warning | 哪个 URI/status/依赖贡献 5xx | [Runbook](runbooks/HighHttp5xxRate.md) |
| HighConversationTurnFailureRate | warning | MODEL/TOOL/CLIENT/INTERNAL 哪类主导 | [Runbook](runbooks/HighConversationTurnFailureRate.md) |
| ModelRateLimitSpike | warning | provider 配额还是异常并发 | [Runbook](runbooks/ModelRateLimitSpike.md) |
| ModelAuthenticationFailure | critical | 密钥过期、撤销还是注入错误 | [Runbook](runbooks/ModelAuthenticationFailure.md) |
| ToolExecutionFailureSpike | warning | FAILED 还是 CONFLICT 主导 | [Runbook](runbooks/ToolExecutionFailureSpike.md) |
| SseCapacityRejected | warning | worker、queue 还是模型耗时饱和 | [Runbook](runbooks/SseCapacityRejected.md) |
| SseActiveConnectionLeakSuspected | warning | 活跃连接是否无法随终态回落 | [Runbook](runbooks/SseActiveConnectionLeakSuspected.md) |
| HikariConnectionPoolExhaustion | warning | 慢 SQL、长事务还是池容量不匹配 | [Runbook](runbooks/HikariConnectionPoolExhaustion.md) |
| JvmHeapUsageHigh | critical | 持续泄漏还是短时峰值/GC 风暴 | [Runbook](runbooks/JvmHeapUsageHigh.md) |

## 8. 可逆止损与根因修复原则

允许的临时止损必须满足“可回滚、留记录、不破坏数据”：

- 回滚到最近已通过 CI 和 smoke 的镜像；
- 暂停真实模型入口或降低 SSE 并发，保留普通查询能力；
- 在数据库和 provider 容量允许的前提下，小步调整线程池/连接池；
- 轮换失效密钥，并确认旧密钥撤销；
- 对异常调用方实施入口限流，而不是删除其业务数据。

根因修复必须通过：单元测试、相关 Testcontainers IT、完整 Maven `verify`、
容器构建和 observability smoke。修复指标或告警时还必须运行 `promtool`、
Dashboard JSON 与低基数标签检查。

## 9. 恢复条件

告警进入 resolved 之后，必须同时满足：

1. 对应表达式至少连续两个评估窗口低于阈值；
2. Readiness 为 `UP`，Prometheus target 为 `UP`；
3. HTTP 5xx、turn/model/tool failure 没有新的级联异常；
4. SSE active 能回落，Hikari pending 恢复到基线；
5. 抽样业务请求成功，审计和数据库状态一致；
6. 关联日志不再出现相同稳定错误码；
7. 临时配置已登记负责人和恢复计划。

关闭事故前记录：根因、影响窗口、受影响能力、处置时间线、最终修复、
验证证据、遗留任务和负责人。

## 10. 禁止操作

- 禁止执行 `docker compose down --volumes`、删除 MySQL volume 或 Flyway history；
- 禁止直接把 tool execution、message、ticket 状态“改成成功”绕过状态机；
- 禁止对生产库执行 Flyway `clean`、`repair` 或未经评审的手工 DDL；
- 禁止在未保存现场时反复重启实例掩盖内存/线程/连接泄漏；
- 禁止盲目无限增大线程池、队列、Hikari pool 或 JVM heap；
- 禁止关闭租户过滤、JWT/RBAC、审计事务或 metrics 认证来临时恢复；
- 禁止把失败请求原文、模型异常 body 或密钥写进日志；
- 禁止在不知道当前事务状态时手工重放 `create_ticket` 副作用。

## 11. 事故记录模板

```text
标题：<告警/用户影响> - <环境> - <开始时间>
级别：critical / warning
负责人：
开始/发现/恢复时间：
影响能力与范围：
告警值与 Dashboard 时间窗：
关联 requestId / traceId（无密钥、无正文）：
已确认事实：
待验证假设：
处置时间线：
临时止损：
根因：
永久修复及 PR：
恢复验证：
遗留任务/负责人/截止日期：
```

## 12. 本地演练与配置校验

```powershell
# 完整应用测试
cd backend
.\mvnw.cmd --batch-mode --no-transfer-progress verify

# 生产监控栈 smoke（会自动启动并默认关闭容器）
cd ..
pwsh scripts/observability-smoke.ps1
```

CI 还会执行 Compose 配置、Prometheus 配置/规则、Grafana JSON、变量白名单、
高基数标签和 secrets 目录检查。任何一项失败都不得合并监控配置。
