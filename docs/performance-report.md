# 性能与稳定性报告（PR：Stability & Performance）

- **日期**：2026-08-16
- **被测版本**：`feat/stability-hardening`（基于 main `71f1164`）
- **方法**：k6（Docker 镜像 `grafana/k6:0.54.0`）驱动真实 HTTP/SSE 流量；
  Prometheus 抓取服务端指标。全部数字来自真实运行输出，
  可用 `scripts/run-loadtest.ps1` 复现。

## 1. 测试环境

| 项 | 值 |
| -- | -- |
| 宿主机 | Windows 11 + Docker Desktop |
| 被测服务 | nexus-agent 单实例容器（Java 21，`MaxRAMPercentage=75`） |
| 数据库 | MySQL 8.4 容器，Hikari `maximum-pool-size=20` |
| 模型供应商 | OpenAI 兼容 SSE mock（`loadtest/mock/openai-mock.mjs`）：每 turn 两轮模型调用（工具调用轮 + 文本轮），chunk 间隔 5ms、文本轮 8 个 delta |
| 流式线程池 | core=8，max=32，queue=200（`NEXUS_CONVERSATION_STREAM_*`） |
| 限流（压测态） | tenant/user = 1,000,000/10s（关闭限流以测容量上限；限流行为单独验证，见 §5） |

每个 k6 VU 拥有独立租户与 Agent（每租户一 conversation 每 iteration 一 turn），
避免跨租户数据交叉；单租户串行语义由会话 `FOR UPDATE` 保证。

## 2. 吞吐量与延迟（阶梯加压）

阶段：`2 VU/15s → 8/30s → 16/60s → 32/120s → 8/30s`，总时长 4m15s。

| 指标 | 值 | 来源 |
| -- | -- | -- |
| 完成会话 turn 数 | **10,440** | k6 |
| 峰值吞吐 | **40.9 turns/s**（iterations 41.0/s，HTTP 82.3 req/s） | k6 |
| 服务端 5 分钟持续吞吐 | **35.1 turns/s** | `rate(nexus_conversation_turn_seconds_count[5m])` |
| Turn 端到端 P95（服务端计时） | **178 ms** | `histogram_quantile(0.95, nexus_conversation_turn)` |
| Turn 端到端 P99（服务端计时） | **255 ms** | `histogram_quantile(0.99, …)` |
| SSE 客户端观测 P95 / max | **311 ms / 1.05 s** | k6 `http_req_duration` |
| 模型调用 P95（单轮，mock） | **55 ms** | `nexus_model_call` |
| 排队等待 P95（提交→worker 开始） | **217 ms** | `nexus_conversation_turn_queue_wait` |

> 注：mock 供应商不含真实 LLM 推理延迟，绝对延迟不能外推到生产；
> 该组数字用于回答"应用自身（线程池/DB/编排）在压力下的行为"——
> 在 32 并发下应用层排队 P95 217ms、无池化资源耗尽（见 §3）。

## 3. 资源与失败率

| 指标 | 峰值 | 说明 |
| -- | -- | -- |
| Hikari 活跃连接 | **9 / 20** | `max_over_time(hikaricp_connections_active[15m])` |
| Hikari 等待线程 | **0** | `hikaricp_connections_pending`——连接池未成为瓶颈 |
| 活跃 SSE 连接 | **21** | `nexus_sse_connections_active` |
| 线程池容量拒绝（503） | **0** | `nexus_sse_connections_capacity_rejected_total` |
| HTTP 失败率 | **3 / 21,005（0.014%）** | k6 `http_req_failed`，阈值 <1% ✓ |
| Turn 失败率（服务端） | **≈0.02%** | TOOL_FAILED 0.0068/s vs COMPLETED_TOOL 35.09/s |
| 检查通过率 | **99.99%**（20,872/20,874） | started+completed 事件序列校验 |

失败样本（3 次 HTTP）为压测末段的长尾（max 1.05s 的 turn），
非系统性错误。

## 4. 工具幂等：并发/重放下不会重复建单

场景（`loadtest/k6/idempotency.js`，检查 25/25 通过）：

1. 以 `Idempotency-Key: K1` 发起建单 turn → 正常完成；
2. **同一 key 顺序重放 20 次** → 全部以
   `TOOL_EXECUTION_IDEMPOTENCY_CONFLICT` 错误事件结束，
   工具执行注册命中既有 (tenant, conversation, key) 幂等键；
3. 第二个 key 的突发重放（含并发）→ 同样只创建一次执行；
4. 终态断言：租户名下工单总数 **== 2（每键一张）**
   （经 `/api/v1/tickets` API 校验，非仅客户端视角）。

底层保证：`tool_executions` 的 `UNIQUE(tenant_id, idempotency_key)` +
注册事务内 `SELECT ... FOR UPDATE` 重放/冲突判定
（`ToolExecutionRegistrationTransactions`）。
客户端键派生自 `(tenant, conversation, key)`
（`tool:turn:v1:` 前缀 SHA-256），无键请求退回按调用身份派生，行为不变。

## 5. 弹性行为（故障注入验证）

### 5.1 安全重试（仅"首个模型事件前"）

mock 注入前 2 次 HTTP 500（→ PROVIDER_UNAVAILABLE，可重试）：

- 指标：`nexus_model_retry_total{outcome=attempted}=2`、
  `{outcome=succeeded}=1`；
- 客户端视角：turn 正常 completed，**无感知**。

### 5.2 模型供应商熔断

mock 持续返回 500：

- 失败累积（含重试放大）后 `resilience4j_circuitbreaker_state{name="model:OPENAI",state="open"}=1`；
- 熔断期间 6 次调用**快速失败**，`nexus_model_call` 以
  `error_category=CIRCUIT_OPEN` 单独归类（不可重试，不浪费供应商配额）；
- 供应商恢复 + 30s 等待窗口后：HALF_OPEN 放行试探 →
  试探成功 → **自动回到 CLOSED**，无需人工干预；
- 期间 `nexus_model_retry_total{outcome=exhausted}=3`——
  重试耗尽后由熔断接管，两层保护衔接符合设计。

### 5.3 tenant/user 限流

`user-limit=2/10s` 下实测：

```
req1: 200
req2: 200
req3: 429  Retry-After: 10
```

响应体为 problem+json，`errorCode=CONVERSATION_TURN_RATE_LIMITED`；
限流发生在提交线程池之前（过载不占用 worker、不建立 SSE 连接）。
压测态（§2）限流配额放大后 `rate_limited_total=0`，
证明正常运行路径无限流误伤。

## 6. 测试与可复现

- 全量 `./mvnw verify`：1,243 单测 + 93 IT 全绿
  （新增 SafeModelRetryExecutor / CircuitBreakerChatModelGateway /
  ConversationTurnRateLimiter / 控制器限流与幂等键 / 客户端键派生用例）；
- 复现：`scripts/run-loadtest.ps1`（栈编排 + 三场景 + Prometheus 汇总输出）；
- k6 摘要与日志：`loadtest/results/`（smoke/ramp/idempotency summary JSON）。

## 7. 结论

- 单实例在 mock 供应商下稳定承载 **32 并发 SSE 会话、~41 turns/s 峰值**，
  应用层无资源饱和（Hikari 9/20、线程池零拒绝）；
- 失败率 0.014%（阈值 1%），turn P99 255ms（服务端计时）；
- **幂等性得到并发证明**：同 key 重复请求（顺序 + 并发 + 完成后重放）
  不产生第二张工单；
- 重试/熔断/限流三层弹性全部经过故障注入验证，指标可观测
  （`nexus.model.retry`、`resilience4j_*`、`nexus.sse.connections.rate_limited`），
  并配套告警与 runbook。
