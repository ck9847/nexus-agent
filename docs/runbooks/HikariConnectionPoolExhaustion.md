# HikariConnectionPoolExhaustion

> 通用事故分级、关联查询、恢复检查和禁止操作见
> [生产运行与事故响应手册](../operations-runbook.md)。

- **告警**：`HikariConnectionPoolExhaustion`
- **severity**：warning
- **指标**：`hikaricp_connections_pending`
- **触发条件**：连续 5 分钟超过 3 个线程等待数据库连接

## 影响

业务请求排队等待数据库连接，延迟上升，极端情况下线程耗尽。

## 排查步骤

1. 在 MySQL 侧查看 `SHOW PROCESSLIST`，定位长事务与慢查询。
2. 检查应用侧事务边界：是否存在跨模型网络调用持有连接
   （本项目约定模型调用在事务外执行，确认无回归）。
3. 观察 `hikaricp_connections_active/max` 与获取耗时
   `hikaricp_connections_acquire_seconds`。
4. 检查连接池配置（maximum-pool-size、connection-timeout）
   与数据库最大连接数是否匹配。
5. 短期手段：调大池或限流入口；长期优化慢查询与索引。

## PromQL、SQL 与关联定位

```promql
max by (instance, pool) (hikaricp_connections_pending)
max by (instance, pool) (hikaricp_connections_active)
max by (instance, pool) (hikaricp_connections_max)
rate(hikaricp_connections_acquire_seconds_sum[5m])
/
rate(hikaricp_connections_acquire_seconds_count[5m])
```

MySQL 只读诊断：

```sql
SHOW FULL PROCESSLIST;

SELECT trx_mysql_thread_id,
       trx_started,
       trx_state,
       trx_rows_locked,
       trx_query
FROM information_schema.innodb_trx
ORDER BY trx_started;
```

用慢请求的 traceId 查询日志与 `audit_logs`，判断连接等待发生在普通 HTTP、
turn prepare/complete/fail 还是工具事务。模型网络阶段按设计不应持有数据库事务。

## 临时止损

- 降低 SSE 新任务并发或入口请求速率；
- 终止明确确认的失控查询前先记录线程、事务、SQL 和负责人；
- 只有数据库连接上限、实例数和平均事务时长允许时，才小步增加 pool；
- 慢 SQL 回归优先回滚版本或禁用触发路径。

## 根因处理与恢复条件

修复慢查询/缺失索引/长事务边界，并补真实 MySQL IT。恢复要求 pending 连续两个
窗口为 0 或回到基线，连接获取耗时恢复，active 不再长期贴近 max，且 5xx/P95
同步恢复。

## 禁止操作

- 禁止无上限扩大 Hikari pool，造成数据库连接雪崩；
- 禁止随意 `KILL` 未确认用途的数据库线程；
- 禁止在模型 HTTP 调用外层新增长事务；
- 禁止重启/删除 MySQL volume 或修改 Flyway history。

## 升级

等待连接数 > 10 持续 15 分钟，升级到值班负责人。
