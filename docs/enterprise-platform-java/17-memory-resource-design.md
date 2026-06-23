# 17. 记忆与运行时资源设计

## 结论

企业智能助手的记忆不应该由单一组件承载。最合理的方案是把记忆拆成“源数据、工作文件、语义索引、运行状态”四类，各自落在最适合的存储上：

| 层次 | 技术 | 职责 |
|------|------|------|
| 记忆源数据 | PostgreSQL | 对话、事实、用户偏好、同步事件、审计和回放。它是长期记忆的 source of truth。 |
| 工作文件 | 沙箱文件系统 + MinIO snapshot | `MEMORY.md`、任务产物、代码、临时上下文。供智能体在运行时直接读写。 |
| 语义索引 | Mem0 或企业内部向量服务 | 从 PG 事件派生，服务相似度召回。可重建，不作为唯一真相。 |
| 运行状态 | Redis | 分布式锁、限流、短 TTL 状态、沙箱执行保护。不能承载长期记忆。 |

这次实现已把 Mem0 写入前置到 `memory_events` ledger：每次长期记忆投递先写 PG，Mem0 只作为异步投影。这样 Mem0 故障不会中断用户请求，也不会丢失需要重放的源事件。

## 资源生命周期

### 创建

1. Web 登录后建立 tenant/user/session 上下文。
2. Agent 运行时按 `IsolationScope.USER` 或 `SESSION` 获取沙箱。
3. 文件系统挂载用户工作区；必要时从 MinIO snapshot 恢复。
4. LTM middleware 在模型调用前从 Mem0/向量索引召回相关记忆。
5. 调用结束后，把对话消息写入 `memory_events`，再异步投递 Mem0。

### 使用

- 沙箱内只保存“当前任务可操作上下文”和文件产物。
- `MEMORY.md` 是 agent-facing 工作记忆，适合让模型直接阅读，不适合作为审计源。
- PG 记录结构化事件和状态，可用于审计、管理后台、重试和重建索引。
- Redis 只保存可丢失的运行态，所有关键状态必须能从 PG/MinIO 重建。

### 释放

1. 沙箱空闲超过 TTL 后释放运行资源。
2. 释放前将工作区 snapshot 到 MinIO。
3. `memory_events` 保留同步状态：`pending`、`synced`、`failed`。
4. 后台任务可扫描 `pending/failed` 事件重放到 Mem0 或替代向量服务。

## Daytona 与 CubeSandbox

当前企业目标强调内网隔离、统一模板、审计和可控资源池。CubeSandbox 更适合作为主路径：

- 已有企业内网地址和模板 ID，可纳入当前端到端验证链路。
- 更容易和现有 `SandboxClient`、`SandboxLifecycleMiddleware`、snapshot、配额中间件集成。
- 对企业私有化部署来说，少一个外部控制面依赖，风险更低。

Daytona 可以作为后续可选后端，适合多人开发环境、IDE 体验和持久开发工作区，但不应该替代当前智能助手任务运行时的主后端。正确方向是保留 `SandboxBroker/SandboxClient` 抽象，让 CubeSandbox、Docker、本地 K8s 和 Daytona 都能按后端策略接入。

## 已实施

- 新增 `memory_events` 表，PostgreSQL 开启 RLS，H2 保持本地测试兼容。
- 新增 `MemoryEventEntity`、`MemoryEventRepository`。
- 新增 `MemoryLedger` 接口和 `PgMemoryLedger` 实现。
- `SaasLongTermMemoryMiddleware` 先记录 PG ledger，再异步投递 Mem0，并回写 `synced/failed`。
- `AgentConfig` 注入 `MemoryLedger`，未配置时可退化为 no-op。
- 单元测试覆盖 Mem0 成功/失败时 ledger 状态回写。

## 下一步

1. 加后台重试任务：扫描 `memory_events` 中 `pending/failed` 记录，重放到 Mem0/向量服务。
2. 加管理面查询：按 org/user/session 查看记忆事件和同步状态。
3. 把 `MEMORY.md` consolidation 的摘要结果也写入 PG，形成可审计的摘要版本链。
4. 端到端验证 CubeSandbox：网页登录、发起任务、沙箱完成、文件 snapshot、网页返回。
5. 压测资源释放：沙箱 TTL、并发执行保护、MinIO snapshot 恢复、Redis 断连降级。
