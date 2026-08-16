# ToolExecutionFailureSpike

> 通用事故分级、关联查询、恢复检查和禁止操作见
> [生产运行与事故响应手册](../operations-runbook.md)。

- **告警**：`ToolExecutionFailureSpike`
- **severity**：warning
- **指标**：`nexus_tool_execution_total{outcome=~"FAILED|CONFLICT"}`
- **触发条件**：失败速率 > 0.02/秒（约 6 次/5 分钟）

## 影响

`create_ticket` 等工具执行失败，会话中的工单创建链路中断；
可能伴随工单与 tool execution 状态补偿。

## 排查步骤

1. 按 outcome 分解：CONFLICT 占比高说明幂等冲突，检查重试方
   与 idempotency key 生成是否有回归。
2. FAILED 占比高：查应用日志中
   `CREATE_TICKET_TOOL_FAILED` / `INVALID_TOOL_INPUT` 的分布。
3. 检查 MySQL 与工单表的写入健康（Hikari 告警是否同时触发）。
4. 确认最近是否发布了工具相关变更（状态机、事务、codec）。
5. 指标本身异常不影响业务：确认指标计数器注册是否报错。

## PromQL、SQL 与关联定位

```promql
sum by (instance, tool, outcome) (
  rate(nexus_tool_execution_total[5m])
)

nexus:tool_failure_ratio:5m
```

```sql
SELECT id,
       conversation_id,
       tool_call_id,
       tool_name,
       idempotency_key,
       status,
       result_entity_type,
       result_entity_id,
       error_code,
       trace_id,
       started_at,
       completed_at
FROM tool_executions
WHERE tenant_id = ?
  AND trace_id = ?
ORDER BY created_at, id;
```

继续查询同一 `tool_execution_id` 的审计记录，验证 Ticket、TOOL message、
后续 ASSISTANT 占位和 ToolExecution SUCCEEDED 是否同事务收敛。不要输出
`input_json/output_json` 或用户消息正文。

## 临时止损

- FAILED 主导：暂停新工具调用，保留普通文本/查询能力；
- CONFLICT 主导：停止异常重试方，不生成新的 toolCallId 绕过幂等；
- 数据库/Hikari 故障：先按数据库 runbook 限流并恢复依赖；
- 新版本状态机回归：回滚最近镜像，禁止手工补成功状态。

## 根因处理与恢复条件

修复 codec/schema、双唯一键幂等、事务或补偿逻辑，并增加并发及审计失败 IT。
恢复要求 failure/conflict 速率连续两个窗口低于阈值，新工具链路恰好创建一个
Ticket，ToolExecution/消息/审计一致，重复请求只重放结果而不重复副作用。

## 禁止操作

- 禁止删除失败 execution 后重试以绕开幂等；
- 禁止手工把 RUNNING/PENDING 改为 SUCCEEDED；
- 禁止复用新 toolCallId 制造第二张 Ticket；
- 禁止关闭双唯一键、租户 owner 校验或审计事务。

## 升级

失败速率 > 0.1/秒持续 15 分钟，升级到值班负责人。
