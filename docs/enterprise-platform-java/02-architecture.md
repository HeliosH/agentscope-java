# 02 · 系统架构（Java）

## 1. 五层架构

基于 agentscope-java + Spring Boot 4.x WebFlux（Reactor），分管理面与运行面。

```
+======================================================================+
|                  管理面 Management Plane (控制面)                      |
|  +-----------+ +-----------+ +-----------+ +------------------------+ |
|  | Auth/SSO   | | Tenant    | | Quota &   | | Admin Dashboard        | |
|  | (Security) | | Manager   | | Governance| | (扩展 admin-starter)    | |
|  +-----------+ +-----------+ +-----------+ +------------------------+ |
+======================================================================+
                                |  策略下发（配额/权限/模型路由/安全基线）
                  API Gateway (Spring Cloud Gateway / Higress)
                                |
+======================================================================+
|              QwenPaw SaaS Core Engine (数据面 / WebFlux)              |
|                                                                      |
|  TenantContextMiddleware (JWT -> RuntimeContext)                     |
|  QwenPawAgent (= HarnessAgent + 自定义 Middleware)                     |
|    +-- Middleware 链（洋葱模型，全部 onAgent 包裹）：                    |
|    |     TenantContext -> RateLimit -> UsageMetering ->              |
|    |     SandboxLifecycle(框架) -> WorkspaceContext(框架) ->          |
|    |     PermissionEngine(框架) -> AgentTrace(框架)                   |
|    +-- Toolkit (@Tool: ShellExecute / Filesystem / Browser)         |
|    +-- SandboxContext (IsolationScope.USER)                         |
|  ChannelManager (DingTalk/Feishu/Telegram/...)                      |
|  SchedulerService (Quartz / XXL-Job 扩展)                            |
+======================================================================+
                                |
+======================================================================+
|                     Sandbox Runtime Layer                            |
|  SandboxManager + SandboxStateStore (Redis/JDBC)                    |
|    +-- DockerSandbox (harness 已有)                                  |
|    +-- KubernetesSandbox / DaytonaSandbox / AgentRunSandbox / E2bSandbox |
|    +-- CubeSandbox (新建 SandboxClient, E2B SDK 兼容)                 |
+======================================================================+
                                |
+======================================================================+
|                     Persistent Storage Layer                         |
|  PostgreSQL 16 (+pgvector)  |  Valkey            |  MinIO/S3          |
|  orgs/users/agents          |  RedisSession      |  workspace 快照     |
|  chat_sessions/memories     |  RedisStore        |  uploads/exports   |
|  usage_records/audit_logs   |  SandboxState/锁    |                    |
+======================================================================+
        基础设施：Vault/KMS · OTel + Prometheus + Grafana + Loki · 离线镜像仓
```

## 2. 中间件链（核心机制）

agentscope-java 的 `MiddlewareBase`（core）提供洋葱模型 5 拦截点。SaaS 化的横切关注点全部以中间件实现，**不污染 Agent 业务逻辑**：

```
HTTP(JWT) → Controller → RuntimeContext.builder() → agent.call(msg, rc)
  → [1] TenantContextMiddleware   解析 JWT → TenantContext 注入 rc
  → [2] RateLimitMiddleware       按 org 限流（Valkey 计数）
  → [3] UsageMeteringMiddleware   起计量（token/时长，非计费）
  → [4] SandboxLifecycleMiddleware acquire 沙箱（框架已有）
  → [5] WorkspaceContextMiddleware 注入工作区上下文（框架已有）
  → [6] PermissionEngine          工具权限运行时决策（框架已有）
  → [7] AgentTraceMiddleware      OTel span（框架已有）
  → ReActAgent: reasoning + acting（工具在沙箱执行）
  → doFinally: persistState + recordUsage
```

> [4][5][6][7] **框架自带**，[1][2][3] 为 SaaS 新增（`agentscope-saas-core`）。

## 3. 管理面（Management Plane）

| 服务 | 职责 | 实现基座 |
|------|------|----------|
| Auth/SSO | OIDC/SAML + RBAC | Spring Security + OAuth2 Resource Server |
| Tenant Manager | 组织/部门/用户/角色 | 新建 + PostgreSQL |
| Quota & Governance | 配额定义/下发/用量聚合（无计费） | 新建 `agentscope-saas-core` |
| Model Admin | 内网模型注册、密钥录入、路由 | 新建（见 [07](./07-model-gateway.md)） |
| Admin Dashboard | 租户/沙箱/用量/渠道/审计 | 扩展 `admin-starter`（`MetricsRecorder` 现成） |

## 4. 运行面（Runtime Plane）

### 4.1 入口
- **Web（AG-UI）**：框架 `agentscope-agui-spring-boot-starter` 提供 SSE 流式，P0 零额外开发；
- **渠道网关**：Channel SPI（新建）+ 适配器，保持宿主机运行（不进沙箱）；
- **开放 API / A2A**：内网 OpenAPI + A2A 协议（Agent 间协作）+ MCP。

### 4.2 SaaS Core Engine
Spring Boot WebFlux 应用，装配 `HarnessAgent` + SaaS 中间件链。Agent 通过 `agent.call(msg, rc)` 调用，`rc`（RuntimeContext）携带 `sessionId/userId` + `TenantContext`。

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("qwenpaw-agent")
    .model(modelGatewayClient)                     // 指向内网模型网关
    .session(RedisSession.builder().jedisClient(jedis).build())
    .filesystem(new SandboxFilesystemSpec()
        .isolationScope(IsolationScope.USER)
        .sandboxClient(cubeSandboxClient))
    .sandboxContext(SandboxContext.builder()
        .client(cubeSandboxClient)
        .isolationScope(IsolationScope.USER).build())
    .middleware(new TenantContextMiddleware(tenantResolver))
    .middleware(new RateLimitMiddleware(rateLimiter))
    .middleware(new UsageMeteringMiddleware(usageService))
    // SandboxLifecycle / Permission / AgentTrace 由框架自动装配
    .build();
```

### 4.3 后端共享服务
- **PostgreSQL(+pgvector)**：组织/用户/Agent/会话/记忆/沙箱映射/用量/审计；
- **Valkey**：`RedisSession`、`RedisStore`（文件元数据/小文件）、`SandboxStateStore`、限流计数、`RedisSandboxExecutionGuard`（多副本锁）；
- **模型网关**：所有 LLM 出口，密钥托管+配额+路由+计量（见 [07](./07-model-gateway.md)）；
- **Sandbox 运行时**：CubeSandbox/Docker/K8s 集群（`SandboxManager` 统一管理）；
- **MinIO**：工作区快照、上传、导出归档。

## 5. 关键架构决策

| # | 决策 | 理由 |
|---|------|------|
| 1 | 沙箱复用 harness `Sandbox` 体系 | Docker/K8s/Daytona/AgentRun 已有完整实现 + 优先级 acquire |
| 2 | 多租户经 `RuntimeContext` + `TenantContext` 中间件 | 框架推荐模式，Session/Store/NamespaceFactory 全支持 |
| 3 | `IsolationScope.USER` 默认隔离级 | 同用户跨 session 共享沙箱 = QwenPaw「一用户一沙箱」 |
| 4 | `RemoteFilesystem` + `BaseStore`(CAS) 做持久化 | 框架两层读 + CAS 写，**无需 FUSE** |
| 5 | 渠道层保持宿主机 | 渠道是消息入口，不执行用户代码 |
| 6 | Spring Boot WebFlux | 与框架 Reactor 生态一致，AG-UI starter 原生支持 |
| 7 | AG-UI 作前端协议 | 框架完整适配器 + starter |
| 8 | CubeSandbox 作 `SandboxClient` 实现 | 兼容 E2B REST，仅需实现接口 |

## 6. 技术选型

| 层 | 选型 | 许可证 |
|----|------|--------|
| Agent 框架 | agentscope-java v2.0.0 | Apache 2.0 |
| 运行时 | JDK 17 + Spring Boot 4.0.4 + Reactor 2025 | Apache |
| 沙箱 | Docker / K8s / **CubeSandbox** | Apache 2.0 |
| 对象存储 | MinIO | AGPL 3.0 |
| 结构化数据 | PostgreSQL 16+（+pgvector） | PostgreSQL |
| 缓存/锁/会话 | Valkey | BSD-3 |
| 消息 | RocketMQ / PG LISTEN-NOTIFY | Apache |
| 密钥 | Vault / 内网 KMS | — |
| 网关 | Higress / Spring Cloud Gateway | Apache |
| 调度 | Quartz / XXL-Job（框架扩展） | Apache |
| 可观测 | OTel + Prometheus + Grafana + Loki | Apache/AGPL |

> 全部可自部署、可离线。各子系统细节见后续分册。
