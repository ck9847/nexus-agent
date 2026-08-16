# JvmHeapUsageHigh

> 通用事故分级、关联查询、恢复检查和禁止操作见
> [生产运行与事故响应手册](../operations-runbook.md)。

- **告警**：`JvmHeapUsageHigh`
- **severity**：critical
- **指标**：`jvm_memory_used_bytes{area="heap"}` / `jvm_memory_max_bytes{area="heap"}`
- **触发条件**：堆使用率连续 5 分钟 > 90%

## 影响

存在 OOM 风险；Full GC 频率上升，接口延迟抖动，严重时实例宕机。

## 排查步骤

1. 先保存 JVM、容器和 GC 指标。当前生产镜像是 JRE；若需要 `jcmd`，必须从
   受控 JDK 诊断容器/宿主机 attach，不能假设应用容器内自带该命令。
2. 观察 `jvm_gc_pause_seconds` 与 GC 频率，确认是否处于 GC 风暴。
3. 用 MAT/VisualVM 分析堆 dump，定位大对象（常见：模型流式缓冲、
   SSE 事件队列、审计 JSON 序列化）。
4. 检查是否有 `SseActiveConnectionLeakSuspected` 同时触发
   （连接泄漏常伴随内存泄漏）。
5. 短期手段：重启实例 + 调大堆；长期修复泄漏点并按容量规划堆大小。

## PromQL 与关联定位

```promql
sum by (instance) (jvm_memory_used_bytes{area="heap"})
/
sum by (instance) (jvm_memory_max_bytes{area="heap"})

sum by (instance) (rate(jvm_gc_pause_seconds_sum[5m]))

max by (instance) (nexus_sse_connections_active)
```

对照 Turn P95、模型 P95、SSE active、线程数与 GC pause 判断是流量峰值还是持续
泄漏。用异常时间窗内的 traceId 定位长时间 turn；heap dump 属于敏感产物，必须
存放在受控位置，分析完成后按安全策略删除。

## 临时止损

- 先降低新 SSE 并发和队列压力；
- 在保存必要现场后滚动重启单实例，避免同时重启全部实例；
- 仅在容器内存上限允许时临时调整堆比例；
- 若与新版本强相关，回滚到最近稳定镜像。

## 根因处理与恢复条件

修复对象留存、无界缓冲、连接未释放或不合理缓存，并增加长流/断连/压力回归。
恢复要求堆使用连续两个窗口低于 80%，GC pause 回到基线，SSE active 可回落，
且没有 OOM/restart loop。

## 禁止操作

- 禁止公开上传 heap dump 或在普通工单中附带对象内容；
- 禁止只增大堆而不确认容器 memory limit；
- 禁止未保存现场就反复重启；
- 禁止用 `System.gc()` 作为长期修复。

## 升级

使用率 > 95% 或已发生一次 Full GC 后仍不回落，立即按事故流程处理。
