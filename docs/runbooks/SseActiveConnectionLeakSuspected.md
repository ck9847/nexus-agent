# SseActiveConnectionLeakSuspected

> 通用事故分级、关联查询、恢复检查和禁止操作见
> [生产运行与事故响应手册](../operations-runbook.md)。

- **告警**：`SseActiveConnectionLeakSuspected`
- **severity**：warning
- **指标**：`nexus_sse_connections_active`
- **触发条件**：活跃 SSE 连接连续 30 分钟 > 50

## 影响

疑似连接泄漏：连接只增不减，最终耗尽线程池与文件描述符，
触发 `SseCapacityRejected` 并拖垮实例。

## 排查步骤

1. 对比 `nexus_sse_connections_active` 与
   `nexus_sse_connections_established/error/client_disconnect/timeout`
   计数器，确认结束路径是否成比例。
2. 检查应用日志中 STREAM_INTERRUPTED 与
   `ConversationTurnSseEventWriter` 的断开处理是否正常。
3. 查看网关/代理层（如 Nginx）的 keepalive 与读超时配置，
   确认客户端断连能及时传导到应用。
4. 用线程 dump 确认流式 worker 线程是否被模型调用长时间占用。
5. 必要时重启实例释放连接，并在修复前临时调大线程池。

## PromQL 与关联定位

```promql
max by (instance) (nexus_sse_connections_active)

sum by (instance) (increase(nexus_sse_connections_established_total[30m]))

sum by (instance) (
  increase(nexus_sse_connections_completed_total[30m])
  + increase(nexus_sse_connections_error_total[30m])
  + increase(nexus_sse_connections_client_disconnect_total[30m])
  + increase(nexus_sse_connections_timeout_total[30m])
)
```

比较建立数与四类终止数，并检查 `ConversationTurnSseEventWriter` 的 transport
failure/timeout 日志。使用长时间 turn 的 traceId 关联模型调用和最终 message
状态，确认没有永久 `CREATING` 占位。

## 临时止损

- 降低新连接并发、缩短经过验证的代理/应用超时；
- 对异常客户端实施入口限流，避免重连风暴；
- 保存线程与指标现场后滚动重启单实例释放连接；
- 不要同时增加 max pool 和 queue，避免把泄漏放大为内存压力。

## 根因处理与恢复条件

修复断连检测、取消 hook、模型网关 interrupt 或代理 timeout，并增加真实/模拟
断连测试。恢复要求 active 连续两个窗口回落到基线，建立与结束增量大致守恒，
无 capacity rejection，相关 ASSISTANT 消息最终为 COMPLETED 或 FAILED。

## 禁止操作

- 禁止只把告警阈值调高；
- 禁止无限扩大 worker/queue；
- 禁止忽略客户端断连后继续提交 COMPLETED；
- 禁止手工修改残留消息状态绕过 CAS/审计。

## 升级

连接数持续增长且接近线程池上限，升级到值班负责人。
