# ConversationTurnRateLimitedSpike

> 通用事故分级、关联查询、恢复检查和禁止操作见
> [生产运行与事故响应手册](../operations-runbook.md)。

- **告警**：`ConversationTurnRateLimitedSpike`
- **severity**：warning
- **指标**：`nexus_sse_connections_rate_limited_total`
- **触发条件**：10 分钟内限流拒绝 > 20 次

## 影响

超限的客户端收到 429 + Retry-After；租户内其他用户不受
单个用户超限影响（tenant/user 两级独立计量）。

## 排查步骤

1. 定位是哪个租户/用户超限（限流器按
   `conversation-turn:tenant:{id}` 命名）：
   ```promql
   topk(10, resilience4j_ratelimiter_available_permissions{
     name=~"conversation-turn:.*"
   })
   ```
2. 判断是业务预期内（营销/批处理/压测）还是异常客户端：
   - 预期内：评估上调
     `nexus.resilience.rate-limit.tenant-limit-for-period` /
     `user-limit-for-period`（注意容量：线程池 + 数据库连接 +
     供应商配额都要同评估）；
   - 异常：联系租户方处理失控客户端；429 + Retry-After
     已提供退避信号。
3. 确认限流器本身健康（fail-open 语义：限流器故障放行，
   不会误拒）。
4. 与 `SseCapacityRejected` 区分：那是线程池饱和（503），
   这是配额超限（429）；两者同时高说明容量规划需要整体调整。

## PromQL 与关联定位

```promql
sum by (instance) (rate(nexus_sse_connections_rate_limited_total[5m]))

sum by (instance) (
  increase(nexus_sse_connections_capacity_rejected_total[10m])
)
```
