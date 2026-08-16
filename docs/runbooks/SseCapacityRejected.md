# SseCapacityRejected

> 通用事故分级、关联查询、恢复检查和禁止操作见
> [生产运行与事故响应手册](../operations-runbook.md)。

- **告警**：`SseCapacityRejected`
- **severity**：warning
- **指标**：`nexus_sse_connections_capacity_rejected_total`
- **触发条件**：最近 10 分钟出现线程池拒绝（> 0 次），持续 1 分钟

## 影响

新的会话流式任务被拒绝，用户收到连接失败；已有连接不受影响。

## 排查步骤

1. 确认流式线程池配置：core/max/queue
   （`nexus.conversation.streaming.*`）。
2. 观察活跃连接 gauge `nexus_sse_connections_active` 与
   模型调用耗时 P95，判断是并发过高还是单任务过慢。
3. 检查模型提供方响应是否变慢（转 `ModelRateLimitSpike` 排查）。
4. 短期手段：调高 max-pool-size / queue-capacity；
   长期评估按租户限流与背压策略。
5. 若拒绝伴随 `NexusAgentDown` 级联，先恢复实例健康。

## PromQL 与关联定位

```promql
sum by (instance) (
  increase(nexus_sse_connections_capacity_rejected_total[10m])
)

max by (instance) (nexus_sse_connections_active)

nexus:model_p95_seconds:5m
nexus:turn_p95_seconds:5m
```

确认拒绝发生时 active、model P95、turn P95 和 JVM/Hikari 是否同时升高。
容量拒绝发生在 SSE 提交前，正常情况下不应留下 USER/ASSISTANT 新消息；用失败
请求的 traceId 查询日志和审计，验证没有半写入。

## 临时止损

- 对新 SSE 入口限流，保留已有连接和非流式 API；
- 模型变慢时优先处理 provider，不盲目扩大 worker；
- 经容量计算后小步调整 max-pool-size 或 queue-capacity，并观察 heap/GC；
- 流量突增时暂停批量调用方，避免客户端立即重连。

## 根因处理与恢复条件

修复容量估算、上游限流、模型耗时或任务未释放问题，并补 rejection/并发测试。
恢复要求 capacity rejected 连续两个窗口无新增，active/P95 回到基线，抽样 SSE
成功，且拒绝请求没有留下不完整数据库状态。

## 禁止操作

- 禁止同时无限增大线程池和队列；
- 禁止把 rejection 转成已接受的 200 空流；
- 禁止让客户端无退避重试；
- 禁止通过关闭认证或 owner 校验降低入口压力。

## 升级

拒绝持续 30 分钟且无法扩容，升级到值班负责人。
