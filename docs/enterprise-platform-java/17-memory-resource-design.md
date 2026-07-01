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
6. 后台 consolidation 成功重写 `MEMORY.md` 后，把新摘要版本写入 `memory_events`，形成可审计版本链。

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
- `MemoryConsolidator` 暴露 successful consolidation sink，SaaS 层将 `MEMORY.md` 摘要版本记录为 `source=workspace`、`event_type=memory_consolidation` 的 PG 事件。
- `AgentConfig` 注入 `MemoryLedger`，未配置时可退化为 no-op。
- sandbox release 前的 remote projection 已从 upload-only 升级为 manifest-based reconciliation：只删除上次由沙箱投影产生、这次 archive 中已不存在的文件，不扫描或清空非沙箱物化的 session/task mirror。
- 单元测试覆盖 Mem0 成功/失败时 ledger 状态回写，以及 `MEMORY.md` consolidation 审计入库。

## 下一步

1. 压测资源释放：E2B 沙箱 TTL、并发执行保护、MinIO snapshot 重复恢复、Redis/MinIO 短暂故障降级。
2. 补生产运维闭环：projection reconciliation 审计、资源泄漏巡检、降级策略。
3. CubeSandbox 作为同类后端保留配置切换能力；本轮按要求不再验证 CubeSandbox，E2B 验证通过即可推进后续流程。

## 2026-06-25 更新

- sandbox remote projection 已补齐 release 前 workspace tar 投影；shell/execute 生成的普通文件会 upload-only 同步到 BaseStore，call 外网页端可读。
- 当前未做删除同步，原因是全量清空 remote projection 会误删不在沙箱 workspace 内的 session mirror/任务文件；需要下一步做带路径作用域的 reconciliation。
- `cube-e2e-smoke.sh` 已参数化为通用 sandbox smoke，可用同一套 app 通过 `SAAS_SANDBOX_TYPE=cube|e2b|docker` 验证命令执行和 release 后文件下载。2026-06-25 发现 E2B 当前可用 platform endpoint 是 `https://api.e2b.dev`；旧默认 `https://api.e2b.app` 会返回 401/不可用。已用 E2B 验证登录、创建 agent、HITL、沙箱执行、SSE 返回、release 后网页下载文件全链路：`GET /workspace/file/download?path=generated/report.txt` 返回 200，内容为 `sandbox-smoke-ok`。

## 2026-06-26 更新

- 新增 `MemoryReplayJob`：后台扫描 `memory_events` 中 `pending/failed` 以及超时卡在 `syncing` 的 Mem0 投影事件，重放到 Mem0，成功标记 `synced`，失败回写 `failed/last_error` 并累加尝试次数。
- 重放任务使用 admin/bypass DataSource 做系统级跨租户扫描；请求路径仍使用 RLS-wrapped primary DataSource，业务访问继续 fail-closed。
- 应用入口已启用 Spring scheduling，沙箱 TTL 回收与 memory replay 任务会实际运行。
- 新增 `GET /api/admin/memory-events` 管理查询接口：org 内 admin 可按 `userId`、`sessionId`、`syncStatus` 查看记忆事件、同步状态、错误原因和原始 payload；org 维度仅取 JWT，不能通过参数跨租户查询。
- `e2e-full.sh` 的旧说明已更新：release 后 workspace download 是硬断言，不能再把 `No active sandbox` 视作正常。
- `MEMORY.md` consolidation 结果已通过 framework sink 写入 PG：文件仍是 agent-facing 工作记忆，PG 记录每次成功摘要的完整版本、时间、水位和字符统计，用于审计、回放和版本追溯。
- workspace remote projection 已增加 manifest-based reconciliation：支持沙箱内删除/移动文件后同步清理远程投影，同时只作用于上次沙箱投影 manifest 中的文件，避免误删外部持久化数据。

## 2026-06-29 更新

- 按当前策略只验证 E2B，不再验证 CubeSandbox。验证环境为本地 PostgreSQL、Redis、MinIO Docker 容器，应用以 `SAAS_SANDBOX_TYPE=e2b`、MinIO snapshot backend、scripted model 启动。
- `cube-e2e-smoke.sh` 已在 E2B 模式跑通：健康检查、注册登录、创建 agent、SSE chat、HITL 确认、E2B 沙箱执行、release 后网页下载文件全部通过，结果 `PASS=7 FAIL=0`。
- 额外完成两轮 E2B snapshot/projection 验证：第一轮在 E2B 内生成 `generated/old.txt` 和 `generated/keep.txt`；第二轮从 MinIO snapshot 恢复第一轮工作区，删除 `old.txt`，把 `keep.txt` 移动为 `generated/moved.txt`，并生成 `generated/new.txt`。验证结果 `PASS=10 FAIL=0`。
- 删除/移动同步已验证：release 后 `generated/old.txt` 下载返回 404，`generated/moved.txt` 和 `generated/new.txt` 可通过网页端 workspace download 读取，说明 manifest-based reconciliation 对 E2B 同样生效。
- MinIO bucket `agentscope-saas-e2b` 观测到新的 snapshot object；PostgreSQL `agentscope_sandbox_snapshots` 元数据表有对应记录。结论：E2B 可替代 CubeSandbox 完成当前运行时闭环验证，后续重点转入资源释放压测和运维可观测性。
- 新增 `scripts/e2b-runtime-lifecycle.sh` 作为可复用 gate：脚本只验证 E2B，不触碰 CubeSandbox；覆盖健康检查、注册、agent 创建、两轮 E2B 执行、HITL、snapshot restore、release projection 下载、删除/移动 reconciliation、MinIO snapshot object 增长、agent 清理。2026-06-29 本地 PG/Redis/MinIO + E2B 实跑结果 `PASS=15 FAIL=0`。
- 新增 sandbox 运行时 Micrometer 指标：`saas.sandbox.lifecycle.events{type,event}` 记录 registered/released/evicted/quota_rejected/tracking failure，`saas.sandbox.run.duration{type,signal}` 记录沙箱 tracking row 活跃期间的 agent run 时长；`/actuator/metrics` 已暴露，指标标签不带 org/user，避免高基数。
- 新增 `GET /api/admin/sandboxes` 运维查询接口：org admin 可按 `userId`、`status`、`sandboxType`、`expiredOnly` 查看本 org sandbox tracking row、过期状态和外部 backend id；org scope 仅取 JWT，不能通过参数跨租户查询。
- 沙箱 lifecycle 已增加 observer 钩子并在 SaaS 层接入 Micrometer：release 前 remote projection 成功/失败、state persist 失败、stop/snapshot 失败、shutdown 失败、backend release 失败和 acquire/start 失败都会进入 `saas.sandbox.lifecycle.events{type,event}`，不再只能依赖日志发现。
- 新增 sandbox inventory gauges：`saas.sandbox.pool.size{type,status}` 统计 tracking row 池状态，`saas.sandbox.pool.expired_active{type}` 统计已过期但仍 active 的行；eviction job 每轮刷新，便于对资源泄漏/回收延迟做 Prometheus 告警。

## 2026-07-01 更新

- 新增 `saas.sandbox.acquire.duration{type,source}` Timer，记录 acquire+start 端到端等待；`source=create|resume|external|unknown`，覆盖 execution guard、state store、provider create/resume、workspace setup/restore。
- `SandboxAcquireResult` 现在携带低基数 acquisition source，避免后续运维指标误加 org/user 高基数标签；租户级排查继续通过 Admin API、tracking 表和审计表完成。

## 2026-07-01 队列背压更新

- Redis sandbox execution guard 新增 `maxWait`，SaaS 默认 `SAAS_SANDBOX_GUARD_MAX_WAIT_SECONDS=60`；同一 isolation slot 被占用超过等待预算时会抛出明确的 sandbox execution timeout，避免请求线程无限挂起。
- 新增 `MeteredSandboxExecutionGuard`：暴露 `saas.sandbox.request.queue_depth{type,scope}`、`saas.sandbox.execution.active{type,scope}`、`saas.sandbox.queue.wait.duration{type,scope,outcome}`、`saas.sandbox.queue.timeouts{type,scope}`。
- 新增 Prometheus 告警 `SaasSandboxQueueTimeouts` 和 `SaasSandboxQueueDepthHigh`，用于发现重复提交、长任务堆积或运行时容量不足。
