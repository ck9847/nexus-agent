# Changelog

本项目的显著变更记录。版本号遵循 [SemVer](https://semver.org/)，
条目格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [Unreleased]

## [0.1.0] - 2026-08-16

首个可部署版本：多租户、可审计的 Agent 会话平台，
带端到端可观测性与压测证明的稳定性基线。

### 核心
- JWT 认证与多租户隔离（tenant/user 维度限流：429 + Retry-After）
- 会话 SSE 流式 turn（两轮模型 + `create_ticket` 工具编排），
  可选 `Idempotency-Key` 防止重复建单（FOR UPDATE 重放/冲突）
- 模型供应商熔断（per-provider，客户端断开不计数）与
  "首个模型事件前"安全重试（指数退避）
- 工具执行状态机（注册→执行→成功/失败/补偿）与审计事件
- 工单生命周期与租户隔离查询

### 可观测性
- Micrometer 指标：turn/model.call（P95/P99 直方图）、
  SSE 生命周期、工具执行、重试、resilience4j_*、Hikari、
  执行器队列深度与排队等待
- Prometheus（抓取专用 Basic-Auth 机器身份）+ Grafana 预置仪表盘
- 13 条告警 + 每告警一套 runbook + 生产运维手册
- 生产就绪：优雅停机、就绪/存活探针、非 root 容器、
  有界线程池 + 显式拒绝（503）

### 供应链与发布
- tag 驱动发布流水线：GHCR 镜像（provenance + cosign keyless 签名）、
  CycloneDX SBOM、Trivy 依赖/镜像扫描（SARIF → Security tab）、
  逐 job 最小权限
- 性能报告（docs/performance-report.md）：32 并发 SSE、
  峰值 40.9 turns/s、失败率 0.014%、幂等并发证明

### 质量基线
- 1,243 单元测试 + 93 集成测试（Testcontainers MySQL）
- CI：Maven verify + Docker 构建 + 可观测性配置校验
- k6 压测套件（smoke / ramp / idempotency）可本地复现
