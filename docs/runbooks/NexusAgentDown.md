# NexusAgentDown

> 通用事故分级、关联查询、恢复检查和禁止操作见
> [生产运行与事故响应手册](../operations-runbook.md)。

- **告警**：`NexusAgentDown`
- **severity**：critical
- **指标**：`up{job="nexus-agent"}`
- **触发条件**：实例连续 1 分钟抓取失败

## 影响

应用可能已宕机或指标端点不可达，全部业务请求可能失败。

## 排查步骤

1. 确认容器/进程状态：`docker ps` 或 `systemctl status`，检查是否重启循环。
2. 直接探测指标端点：
   `curl -u prometheus:<password> http://app:8080/actuator/prometheus`。
3. 查看应用日志最近 5 分钟的错误与 OOM 迹象（`NEXUS_*` 配置、MySQL 连接）。
4. 检查依赖：MySQL 是否可用（Hikari 连接池告警可能同时触发）。
5. 若为滚动发布，确认新版本健康检查是否通过（`/actuator/health/readiness`）。

## PromQL 与诊断命令

```promql
up{job="nexus-agent"}
```

```powershell
docker compose --profile observability -f deploy/compose.yaml ps -a
docker logs nexus-agent-app --since 30m --tail 500
Invoke-WebRequest http://127.0.0.1:8080/actuator/health/readiness `
  -SkipHttpErrorCheck
```

如果 Readiness 为 UP 而 `up==0`，重点检查 Prometheus Targets 中的 DNS、网络和
Basic Auth 错误；如果二者都失败，检查容器 exit code、OOM、Flyway、JWT 配置和
MySQL 连接。不要把 metrics 密码直接写在命令或事故记录中。

## 临时止损

- 抓取认证故障：修复 metrics secret/环境变量一致性，不重启数据库；
- 应用启动回归：回滚最近通过 CI 的镜像；
- MySQL 不可达：保持应用停止接收新写入，先恢复数据库；
- 单实例异常：保存日志后滚动重启，避免同时清空全部实例。

## 根因处理与恢复条件

修复启动配置、镜像、数据库依赖或抓取身份，并补 ProductionRuntime/安全回归。
恢复要求 target 连续两个窗口 UP、Readiness 为 UP、抽样业务读写和审计成功，
容器不再 restart loop，且没有残留 critical 告警。

## 禁止操作

- 禁止执行 `down --volumes` 或删除 MySQL 数据卷；
- 禁止为恢复抓取而公开 `/actuator/prometheus`；
- 禁止修改/删除 Flyway schema history；
- 禁止未保存日志和 exit/OOM 证据就连续重启。

## 升级

实例持续不可用超过 10 分钟，升级到值班负责人；若是全集群不可用，
立即按事故流程处理。
