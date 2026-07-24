# 企业私有化 · 个人助手平台（Java 方案）

> 基于 **agentscope-java**（v2.0.0-SNAPSHOT，JDK 17，Spring Boot 4.x，Project Reactor）将 QwenPaw 重建为面向**企业私有化部署**的多用户个人助手平台。**数据不出企业内网，不含计费。**

## 这份方案与 Python 方案的关系

- [`../enterprise-platform/`](../enterprise-platform/) 是 **Python 方案**：保留 QwenPaw 全部 Python 代码，插入 `ExecutionBackend` 抽象微创改造。
- **本目录是 Java 方案**：基于 agentscope-java 框架**重建** QwenPaw 能力，最大化复用框架已内置的 Sandbox / Middleware / Filesystem / Permission / AG-UI / 调度 / 多副本能力。
- 两者**目标架构一致**（管理面/运行面、CubeSandbox、SSO/RBAC、资源治理、审计、离线），差异在**实现基座与改造哲学**。选型见 [../implementation/README.md](../implementation/README.md)。

## 核心判断：框架积木已就位，无需自建执行层

agentscope-java 的关键能力**已逐项核实存在于代码中**（非画饼）：

| 能力 | 框架内置（已核实位置） | 对 QwenPaw 改造的意义 |
|------|----------------------|----------------------|
| 沙箱体系 | `Sandbox`/`SandboxClient`/`SandboxManager`(优先级 acquire)/`SandboxStateStore`/`SandboxLifecycleMiddleware` @ `agentscope-harness` | **无需自建 ExecutionBackend** |
| 沙箱实现 | `DockerSandbox` + `KubernetesSandbox`/`DaytonaSandbox`/`AgentRunSandbox`/`E2bSandbox` | 多后端可选，CubeSandbox 只需补 `SandboxClient` |
| 隔离 | `IsolationScope`{SESSION,**USER**,AGENT,GLOBAL} | USER = 一用户一沙箱，开箱即用 |
| 文件持久化 | `RemoteFilesystem`+`BaseStore`(CAS 写) + `CompositeFilesystem` 两层读 | **无需 JuiceFS/FUSE** |
| 中间件 | `MiddlewareBase`{onAgent/onReasoning/onActing/onModelCall/onSystemPrompt} @ core | 租户/限流/计量/审计/追踪皆挂中间件 |
| 权限 | `PermissionEngine`+`PermissionMode`(5 种) @ core | 替代 tool_guard |
| 事件流 | `AgentEvent`/`AgentEventType`(25 类型)，`ReActAgent.streamEvents→Flux` | 细粒度 SSE 流式 |
| 前端协议 | AG-UI starter（SSE） | Web 渠道零额外开发 |
| 多副本 | `RedisSandboxExecutionGuard`/`RedisStore`/`RedisSession` | 分布式锁/状态现成 |
| 可观测 | `AgentTraceMiddleware`（OTel 1.61）/ admin-starter(`MetricsRecorder`) | 生产级追踪+指标 |
| 调度/记忆/多 Agent | scheduler 扩展 / `LongTermMemory` / A2A+Nacos / `SubAgentTool` | 复用底座 |

## 但必须诚实：这是"重建"，不是"搬运"

QwenPaw 是 **Python 产品**。框架有"等价物" ≠ QwenPaw 代码能直接用。以下**全部需 Java 重写**：17 个消息渠道、技能系统与技能市场、自演进记忆逻辑、Coding 模式（Web IDE）、浏览器工具。这是 Java 方案工期的主要构成（见 [10-roadmap.md](./10-roadmap.md)）。

## 文档导航

| # | 文档 | 内容 |
|---|------|------|
| 01 | [总体方案与目标](./01-overview.md) | 背景、目标、能力映射（复用 vs 重写） |
| 02 | [系统架构](./02-architecture.md) | 五层架构、Spring Boot WebFlux、中间件链 |
| 03 | [执行层与沙箱](./03-execution-and-sandbox.md) | 框架 Sandbox 体系、CubeSandbox、IsolationScope.USER |
| 04 | [身份与隔离](./04-iam-and-isolation.md) | TenantContext/RuntimeContext、NamespaceFactory、RLS |
| 05 | [资源治理](./05-resource-management.md) | 配额中间件、沙箱池（无计费） |
| 06 | [运行时与数据模型](./06-runtime-and-data.md) | HarnessAgent、Session、Memory、数据模型 |
| 07 | [模型网关](./07-model-gateway.md) | 内网 LLM 网关：密钥托管、配额、路由、计量 |
| 08 | [渠道 / 技能 / 安全审计](./08-channels-skills-security.md) | Channel SPI、技能市场、PermissionEngine、审计 |
| 09 | [部署与可观测性](./09-deployment-observability.md) | K8s 拓扑、OTel、Micrometer、SLA |
| 10 | [实施路线图](./10-roadmap.md) | 分阶段（含重建工作量）、风险 |
| 11 | [前端改造方案](./11-frontend-migration.md) | 基于 agentscope Web UI 扩展、AG-UI 协议对接、Admin Dashboard、分期计划 |
| 12 | [具体实施方案](./12-implementation-plan.md) | 简单登录+完整 QwenPaw 对齐（不含 IM）：paw 产品层移植进 SaaS 多租户壳、分阶段 A–D |
| 17 | [记忆与运行时资源设计](./17-memory-resource-design.md) | PG 记忆账本、文件记忆、Mem0/向量投影、Redis 运行态、CubeSandbox/Daytona 取舍 |
| 18 | [企业岗位档案与个人记忆实施方案](./18-enterprise-memory-profile-implementation.md) | 企业维护岗位主数据、个人记忆卡片、Mem0 投影、任务前检索与分阶段实施 |
| 19 | [企业智能助手运行框架优化落地方案](./19-runtime-orchestration-optimization-plan.md) | 持久化任务编排、可靠子 Agent、部署时可切换沙箱 Provider、恢复、验证与实施计划 |

## 一页速读

```
┌───────────────────────────────────────────────────────────────────────┐
│                      管理面 Management Plane                             │
│  Spring Security/OAuth2 · 组织/部门/成员 · 配额治理 · 模型治理 · admin-starter │
└────────────────────────────────┬──────────────────────────────────────┘
                                 │ 策略下发
┌────────────────────────────────┼──────────────────────────────────────┐
│              运行面 Runtime Plane（Spring Boot WebFlux，企业内网）          │
│  Web(AG-UI)/渠道 → API Gateway(Higress/SCG) → QwenPaw SaaS Core Engine    │
│    └ HarnessAgent + Middleware 链：                                       │
│        TenantContext→RateLimit→UsageMetering→SandboxLifecycle            │
│        →WorkspaceContext→PermissionEngine→AgentTrace                     │
│    └ Toolkit(@Tool: Shell/Filesystem/Browser) · ChannelManager · Scheduler│
│                                  │ IsolationScope.USER                    │
│                                  ▼                                       │
│   ┌──────────┐  ┌──────────────┐  ┌───────────────┐  ┌──────────────┐    │
│   │PostgreSQL│  │ Valkey        │  │  模型网关       │  │ Sandbox 运行时 │    │
│   │+pgvector │  │RedisStore/    │  │密钥·配额·路由   │  │ Cube/Docker/K8s│   │
│   │          │  │Session/Guard  │  │·计量(非计费)   │  │ (SandboxManager)│  │
│   └──────────┘  └──────────────┘  └──────┬────────┘  └──────┬───────┘    │
│                          RemoteFilesystem ▼ 内网模型           ▼ 归档       │
│                          (BaseStore,无FUSE) vLLM/Ollama  ┌──────────────┐ │
│                                                          │ MinIO（快照）  │ │
│                                                          └──────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
```

- **全开源、可离线、数据不出内网**：Docker/K8s/CubeSandbox + MinIO + PostgreSQL + Valkey + 内网模型。
- **不计费**：资源治理 = 配额 + 公平调度 + 审计。
- **框架积木**：沙箱/文件(无 FUSE)/权限/中间件/多副本/可观测全部复用 agentscope-java。
