# ModelAuthenticationFailure

> 通用事故分级、关联查询、恢复检查和禁止操作见
> [生产运行与事故响应手册](../operations-runbook.md)。

- **告警**：`ModelAuthenticationFailure`
- **severity**：critical
- **指标**：`nexus_model_call_seconds_count{outcome="failure", error_category="AUTHENTICATION"}`
- **触发条件**：最近 10 分钟出现认证失败（> 0 次），持续 1 分钟

## 影响

模型凭据失效或被撤销，所有依赖模型的能力（会话、工具续写）失败。

## 排查步骤

1. 核对提供方 API Key 是否过期、被吊销或配额被冻结。
2. 检查密钥轮换流程：新密钥是否已注入
   （`OPENAI_API_KEY` / 模型配置）并完成重启。
3. 确认密钥没有泄漏：检查代码仓库、日志、前端请求中是否出现明文。
4. 轮换后观察 `error_category="AUTHENTICATION"` 是否归零。
5. 若为多环境共享账号，确认其他环境密钥轮换的影响面。

## PromQL 与关联定位

```promql
sum by (instance, provider) (
  increase(nexus_model_call_seconds_count{
    outcome="failure", error_category="AUTHENTICATION"
  }[10m])
)

sum by (instance, provider, outcome) (
  rate(nexus_model_call_seconds_count[5m])
)
```

从失败 SSE 的 traceId 查询日志与 `CONVERSATION_TURN_FAILED` 审计，只记录
`AUTHENTICATION` 稳定类别，禁止查看或打印 provider 原始响应 body。确认失败
是否覆盖所有实例与 provider，避免只修复单个容器。

## 临时止损

- 暂停真实模型 turn 入口，保留身份、Ticket、历史查询等非模型能力；
- 从批准的秘密管理来源重新注入新密钥并滚动重启；
- 新密钥验证成功后立即撤销旧密钥；
- 若错误来自配置回归，回滚配置/镜像而不是把密钥写进配置文件。

## 根因处理与恢复条件

修复密钥轮换、环境注入或 provider 权限配置，并记录轮换负责人和有效期。
恢复要求所有实例认证失败增量连续两个窗口为 0，真实但无敏感输出的模型 smoke
成功，turn failure 同步恢复，旧密钥已撤销。

## 禁止操作

- 禁止在日志、Git、截图、命令历史或事故文档中粘贴密钥；
- 禁止临时关闭 TLS 校验或把 provider 错误正文返回客户端；
- 禁止长期复用多环境共享密钥；
- 禁止为了恢复而打开未认证的模型代理。

## 升级

认证失败持续存在（密钥修复前），立即通知值班负责人与密钥管理员。
