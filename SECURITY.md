# Security Policy

## 支持的版本

| 版本 | 支持状态 |
| ---- | -------- |
| 0.1.x | ✅ |

## 报告漏洞

**请勿通过公开 Issue 报告安全漏洞。**

请使用 GitHub 私有漏洞报告
（仓库 Security 标签页 → Report a vulnerability），
或联系维护者。我们会在 72 小时内确认收到，并在修复发布前
与你同步进展。请在报告中包含复现步骤与影响评估。

## 安全设计要点

- **认证**：JWT 资源服务器（HS256，密钥仅经环境变量/secret 注入）；
  指标抓取走独立 Basic-Auth 机器身份（仅 `ROLE_METRICS`，
  无法访问业务 API）
- **多租户隔离**：所有查询/写入以 tenantId 作用域强制过滤
- **密钥管理**：`deploy/secrets/` 仅允许占位文件进 Git；
  CI 含 secrets 泄漏检查；日志与错误消息不携带凭据
- **供应链**：tag 驱动的发布流水线执行 cosign 签名、
  CycloneDX SBOM 与 Trivy 依赖/镜像扫描；workflow 逐 job
  最小权限（默认仅 `contents: read`）
- **容器**：非 root 用户（uid 10001）、多阶段构建、
  无镜像内凭据

## 已知权衡

- HS256 对称签名要求所有实例共享密钥；多实例部署时应通过
  secret 管理系统分发并支持轮换（当前通过环境变量注入）。
