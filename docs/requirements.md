# NexusAgent V0.1

## 项目目标

构建一个多租户企业智能工单 Agent 平台。
用户可以通过自然语言描述问题，Agent 可以调用业务工具创建工单。

## 核心流程

1. 用户登录系统
2. 用户发送消息
3. Agent 分析用户意图
4. Agent 调用 create_ticket 工具
5. 后端校验工具参数和用户权限
6. 后端创建工单
7. 保存工具执行记录和审计日志
8. Agent 向用户返回工单编号

## 核心实体

- Tenant：租户
- User：用户
- Role：角色
- Agent：Agent 配置
- Conversation：会话
- Message：消息
- Ticket：工单
- ToolExecution：工具执行记录
- AuditLog：审计日志

## V0.1 功能

- 用户注册和登录
- JWT 身份认证
- 创建、查询工单
- 创建和查询会话
- 大模型流式响应
- create_ticket 工具调用
- 对话记录持久化
- 工具执行记录
- 审计日志

## 验收标准

用户输入：

“服务器无法连接，请帮我提交一个高优先级工单。”

系统最终应当：

1. Agent 调用 create_ticket
2. MySQL 中新增一条工单
3. 返回真实工单编号
4. 保存用户消息和 Agent 消息
5. 保存工具名称、参数、结果和耗时
6. 保存操作人及审计记录