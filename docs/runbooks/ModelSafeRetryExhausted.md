# ModelSafeRetryExhausted

> 通用事故分级、关联查询、恢复检查和禁止操作见
> [生产运行与事故响应手册](../operations-runbook.md)。

- **告警**：`ModelSafeRetryExhausted`
- **severity**：warning
- **指标**：`nexus_model_retry_total{outcome="exhausted"}`
- **触发条件**：10 分钟内重试耗尽 > 5 次

## 影响

重试耗尽意味着安全重试（仅"尚未转发首个模型事件"时）
用尽 `max-attempts` 后仍失败，turn 以原始异常失败。
持续的 exhausted 说明供应商故障在重试窗口内没有恢复。

## 排查步骤

1. 查看重试漏斗：
   ```promql
   sum by (provider, outcome) (rate(nexus_model_retry_total[10m]))
   ```
   - `attempted` 多、`succeeded` 也多：重试在起作用，
     属于供应商抖动；
   - `attempted` 多、`exhausted` 比例高：持续性故障，
     关注 `ModelCircuitOpen` 是否即将触发；
   - `blocked_by_first_event` 多：失败发生在内容已开始输出后
     （不可安全重试），若同时大量出现说明供应商流中断严重。
2. 结合 `nexus.model.call` 失败分类确认根因。
3. 评估退避参数
   （`nexus.resilience.model-retry.*`）：
   指数退避过短会在供应商限流窗口内无效重试，
   过长会拖高 turn P99。
4. 重试与熔断的叠加语义：每次重试都会经过熔断器计数，
   持续失败会更快打开熔断——这是预期行为，
   不应通过调小重试次数来"避免熔断"。

## PromQL 与关联定位

```promql
sum by (provider, outcome) (rate(nexus_model_retry_total[10m]))

sum by (provider, error_category) (
  rate(nexus_model_call_seconds_count{outcome="failure"}[10m])
)

histogram_quantile(0.95, sum by (le) (
  rate(nexus_model_call_seconds_bucket[10m])
))
```
