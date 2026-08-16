# ModelCircuitOpen

> 通用事故分级、关联查询、恢复检查和禁止操作见
> [生产运行与事故响应手册](../operations-runbook.md)。

- **告警**：`ModelCircuitOpen`
- **severity**：critical
- **指标**：`resilience4j_circuitbreaker_state{name=~"model:.*", state="open"}`
- **触发条件**：任一供应商熔断器进入 OPEN（立即触发）

## 影响

该供应商的所有新模型调用被快速失败
（`CHAT_MODEL_CIRCUIT_OPEN`，不可重试），会话 turn 大面积失败。
保护目的：避免向已恶化的供应商持续堆积超时请求、
占用流式线程池与数据库连接。

## 排查步骤

1. 确认熔断统计与失败构成：
   ```promql
   resilience4j_circuitbreaker_calls{name=~"model:.*"}
   sum by (error_category) (
     rate(nexus_model_call_seconds_count{outcome="failure"}[10m])
   )
   ```
2. 常见根因：
   - 供应商 5xx/网络超时突增（CONNECTION/TIMEOUT/PROVIDER_UNAVAILABLE）；
   - 供应商限流持续触发（RATE_LIMIT）——关联
     `ModelRateLimitSpike` 告警；
   - API Key 失效（AUTHENTICATION）——熔断不该由 4xx 打开，
     若出现说明分类映射有误，属缺陷。
3. 供应商侧恢复后，熔断器会在
   `nexus.resilience.circuit-breaker.wait-duration-in-open-state`
   （默认 30s）后进入 HALF_OPEN 并放行
   `permitted-number-of-calls-in-half-open-state`（默认 3）个试探；
   试探成功率回升则自动 CLOSED，无需人工干预。
4. 需要立即恢复时（确认供应商已健康）：
   重启应用会重置熔断状态（状态仅存内存）；
   或临时调大 wait-duration 让 HALF_OPEN 更快到来。
5. 客户端断开绝不触发本告警（消费侧异常被熔断忽略）；
   若怀疑误触发，核对 ignore 谓词是否被修改。

## 禁止操作

- 不要为"止血"把 failure-rate-threshold 调到 100：
  熔断是保护数据库连接与线程池的手段，不是障碍。
- 不要在熔断打开期间放大客户端重试频率。

## PromQL 与关联定位

```promql
resilience4j_circuitbreaker_state{name=~"model:.*"}

sum by (provider, error_category) (
  rate(nexus_model_call_seconds_count{outcome="failure"}[10m])
)

sum by (provider, outcome) (rate(nexus_model_retry_total[10m]))
```
