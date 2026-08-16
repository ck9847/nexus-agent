# ModelRateLimitSpike

> 通用事故分级、关联查询、恢复检查和禁止操作见
> [生产运行与事故响应手册](../operations-runbook.md)。

- **告警**：`ModelRateLimitSpike`
- **severity**：warning
- **指标**：`nexus_model_call_seconds_count{outcome="failure", error_category="RATE_LIMIT"}`
- **触发条件**：限流失败速率 > 0.05/秒（约 15 次/5 分钟）

## 影响

模型调用被提供方限流，会话 turn 失败率随之上升。

## 排查步骤

1. 确认限流来自提供方配额还是应用侧调用量突增：
   `sum by (provider) (rate(nexus_model_call_seconds_count[5m]))`。
2. 核对提供方账号的 RPM/TPM 配额与当前账单用量。
3. 检查是否存在异常重试放大（失败重试策略与退避配置）。
4. 短期手段：降低并发（`nexus.conversation.streaming.max-pool-size`），
   或升级提供方配额；评估是否启用多 provider 路由。
5. 若为营销/批处理引发，与业务方确认流量预期。

## PromQL 与关联定位

```promql
sum by (instance, provider) (
  rate(nexus_model_call_seconds_count{
    outcome="failure", error_category="RATE_LIMIT"
  }[5m])
)

sum by (instance, provider) (
  rate(nexus_model_call_seconds_count[5m])
)

nexus:model_p95_seconds:5m
```

按 provider/instance 判断是账号级配额还是单实例流量异常。使用失败 SSE 的
traceId 关联日志和 `CONVERSATION_TURN_FAILED` 审计；只保留 RATE_LIMIT 类别、
provider status 和时间，不记录原始响应 body。

## 临时止损

- 降低新 SSE 并发和入口速率，禁止立即无退避重试；
- 暂停批量/非交互流量，为用户交互保留配额；
- 在供应商确认后提升配额，或临时关闭真实模型能力；
- 现版本没有多 provider 路由，不得假装已自动故障转移。

## 根因处理与恢复条件

修复调用放大、重试退避、容量规划或账号配额，并增加限流故障测试。恢复要求
RATE_LIMIT 速率连续两个窗口低于阈值，模型 P95 与 turn failure 回到基线，且
没有因客户端重试造成第二次尖峰。

## 禁止操作

- 禁止无限重试或并发重放同一 turn；
- 禁止在数据库事务内等待 provider 限流恢复；
- 禁止把 provider 原始错误正文或账号信息返回客户端；
- 禁止宣称已具备未实现的多 provider 自动切换。

## 升级

连续 30 分钟未缓解且 turn 失败率同步告警，升级到值班负责人。
