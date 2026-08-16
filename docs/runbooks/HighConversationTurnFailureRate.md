# HighConversationTurnFailureRate

> 通用事故分级、关联查询、恢复检查和禁止操作见
> [生产运行与事故响应手册](../operations-runbook.md)。

- **告警**：`HighConversationTurnFailureRate`
- **severity**：warning
- **指标**：`nexus:turn_failure_ratio:5m`
- **触发条件**：5 分钟滚动 turn 失败率 > 10%
  （MODEL_FAILED / TOOL_FAILED / CLIENT_DISCONNECTED / INTERNAL_FAILED）

## 影响

大量用户会话无法完成，用户可感知的服务质量下降。

## 排查步骤

1. 按 outcome 分解 `nexus_conversation_turn_seconds_count`，
   确定失败主要来自模型、工具、客户端断连还是内部失败。
2. MODEL_FAILED → 转 `ModelRateLimitSpike` / `ModelAuthenticationFailure`
   对应的排查路径。
3. TOOL_FAILED → 转 `ToolExecutionFailureSpike`。
4. CLIENT_DISCONNECTED 占比高 → 检查网络出口、SSE 超时配置
   （`nexus.conversation.streaming.timeout`）。
5. INTERNAL_FAILED → 查应用日志中的完成事务失败堆栈。

## PromQL 与关联定位

```promql
nexus:turn_failure_ratio:5m

sum by (instance, outcome) (
  rate(nexus_conversation_turn_seconds_count[5m])
)

nexus:turn_p95_seconds:5m
```

从失败 SSE 响应保留 `X-Request-ID`、`X-Trace-ID` 和安全错误码，随后按
traceId 查询应用日志，并按 tenantId + requestId/traceId 查询
`audit_logs`。MODEL_FAILED 重点检查 `CONVERSATION_TURN_FAILED`；
TOOL_FAILED 同时检查 `TOOL_EXECUTION_*` 审计及 tool execution 终态。

## 临时止损

- MODEL_FAILED 主导：降低流式并发，暂停非必要流量，按 provider 告警处理；
- TOOL_FAILED 主导：暂时避免触发新工具调用，保留普通文本/查询能力；
- CLIENT_DISCONNECTED 主导：核对代理读超时与客户端重连策略，禁止无限重试；
- INTERNAL_FAILED 主导：回滚到最近通过完整 `verify` 和 smoke 的镜像。

## 根因处理与恢复条件

根因修复应落在对应边界：模型错误映射、工具事务、SSE 传输或数据库提交。
恢复需要失败率连续两个评估窗口低于 10%，P95 回到基线，抽样 turn 成功，
且 USER/ASSISTANT/TOOL 消息、ToolExecution 和审计状态一致。

## 禁止操作

- 禁止直接把 `ASSISTANT/CREATING` 或 ToolExecution 手工改为成功；
- 禁止把模型错误 body、消息正文或 system prompt 写入日志；
- 禁止通过无限增大线程池/队列掩盖模型或数据库故障。

## 升级

失败率超过 30% 持续 15 分钟，升级到值班负责人。
