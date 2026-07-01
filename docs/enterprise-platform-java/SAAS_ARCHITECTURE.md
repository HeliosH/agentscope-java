# QwenPaw Java SaaS 架构设计方案

> **目标**: 将 QwenPaw 从单用户本地部署的 Python 产品改造为基于 agentscope-java-1 框架的多租户 SaaS 平台，最大化复用框架已有的 Session、Middleware、Sandbox、Workspace、Permission 等构建块。
>
> **基础框架**: agentscope-java-1 v2.0.0-SNAPSHOT (JDK 17+, Spring Boot 4.x, Project Reactor)
>
> **参考文档**: [QwenPaw Python SaaS 架构方案](../../QwenPaw/docs/SAAS_ARCHITECTURE.md)

## 目录

1. [概述](#1-概述)
2. [现状分析与能力映射](#2-现状分析与能力映射)
3. [目标架构](#3-目标架构)
4. [Maven 模块设计](#4-maven-模块设计)
5. [多租户上下文传播](#5-多租户上下文传播)
6. [数据模型设计](#6-数据模型设计)
7. [沙箱与执行层](#7-沙箱与执行层)
8. [工作区与文件持久化](#8-工作区与文件持久化)
9. [流式传输与并发控制](#9-流式传输与并发控制)
10. [消息频道集成层](#10-消息频道集成层)
11. [SaaS 控制面](#11-saas-控制面)
12. [可观测性](#12-可观测性)
13. [安全设计](#13-安全设计)
14. [优雅降级](#14-优雅降级)
15. [部署架构](#15-部署架构)
16. [测试策略](#16-测试策略)
17. [实施路线图](#17-实施路线图)
18. [风险与缓解](#18-风险与缓解)

---

## 1. 概述

### 1.1 文档定位

本文档描述 QwenPaw SaaS 的 **Java 实现方案**。与 Python 版 SaaS 方案的核心差异：

| 维度 | Python 方案 | Java 方案 (本文) |
|------|-----------|----------------|
| 基础框架 | AgentScope Python 2.0 + FastAPI | **agentscope-java-1** v2.0.0 + Spring Boot 4.x |
| 多租户原语 | 需自建 ExecutionBackend + ContextVar | **框架内置**: SessionKey/RuntimeContext/IsolationScope/NamespaceFactory |
| 沙箱系统 | E2B SDK (Python) | **harness 内置**: Docker/K8s/Daytona/AgentRun Sandbox + SandboxManager |
| 工作区 | E2BWorkspace (Python) | **harness 内置**: AbstractFilesystem (Local/Sandbox/Remote/Composite/Overlay) |
| 权限控制 | ToolGuardEngine (自建) | **core 内置**: PermissionEngine + PermissionRule |
| 中间件 | 无 | **core 内置**: MiddlewareBase (onAgent/onReasoning/onActing/onModelCall/onSystemPrompt) |
| 消息频道 | 17 个 Python channel 类 | 需新建 Java SPI + 适配器 |
| 前端 | 无 | **扩展内置**: AG-UI starter (SSE streaming) |
| 调度器 | APScheduler | **扩展内置**: Quartz / XXL-Job starter |
| 会话存储 | 自建 PostgreSQL | **扩展内置**: RedisSession / MysqlSession |

### 1.2 设计原则

1. **最大化复用**: agentscope-java 已有的构建块直接使用，不重复造轮子
2. **渐进式改造**: 新增 `agentscope-saas` 模块树，不修改 core/harness 代码
3. **多租户一等公民**: TenantContext 通过 RuntimeContext + Middleware 全链路传播
4. **后端可插拔**: 沙箱、存储、频道均为 SPI 接口，按需替换实现

---

## 2. 现状分析与能力映射

### 2.1 QwenPaw Python 组件 → agentscope-java 等价物

| QwenPaw (Python) | agentscope-java 等价物 | 复用程度 | 需要新建 |
|------------------|----------------------|---------|---------|
| `QwenPawAgent` (extends ReActAgent) | `HarnessAgent` + 自定义 Middleware | **直接复用** | QwenPaw 特有 Middleware |
| `MultiAgentManager` | Spring Bean + `AgentRegistry` (admin starter) | **部分复用** | SaaS 版多 Agent 管理 |
| `Toolkit` (shell/file/browser/grep/glob) | `Toolkit` + `@Tool` 注解方法 | **直接复用** | 沙箱化执行适配 |
| `ToolGuardEngine` | `PermissionEngine` + `PermissionRule` | **直接复用** | SaaS 级规则扩展 |
| `ChannelManager` (17 channels) | 无直接等价物 | **需新建** | Channel SPI + 适配器 |
| `MemoryManager` (InMemory/PG) | `Memory` + `LongTermMemory` + Session | **直接复用** | 多租户隔离配置 |
| `CronManager` (APScheduler) | `agentscope-extensions-scheduler` (Quartz/XXL-Job) | **直接复用** | 多租户调度适配 |
| `Workspace` (QwenPaw 层) | `WorkspaceManager` + `AbstractFilesystem` | **直接复用** | NamespaceFactory 多租户 |
| `ExecutionBackend` (Python 自建) | `Sandbox` + `SandboxClient` + `SandboxManager` | **直接复用** | 无 |
| `SandboxBroker` (Python 自建) | `SandboxManager` + `SandboxStateStore` | **直接复用** | DB 映射层 |
| Config (~1000行 Pydantic) | `AgentscopeProperties` + `@ConfigurationProperties` | **部分复用** | SaaS 配置扩展 |
| FastAPI App | Spring Boot WebFlux | **框架替换** | - |
| Auth (fastapi-users) | Spring Security + OAuth2 | **框架替换** | - |

### 2.2 agentscope-java 已有但 Python 方案未涉及的强大能力

| 能力 | 说明 | SaaS 价值 |
|------|------|----------|
| **Middleware 洋葱模型** | 5 个拦截点 (onAgent/onReasoning/onActing/onModelCall/onSystemPrompt) | 租户限流、审计、prompt 注入 |
| **AG-UI 协议** | SSE 流式前端协议，完整 adapter + starter | 标准化前端集成 |
| **A2A 协议** | Agent-to-Agent 通信 + Nacos 服务发现 | 多 Agent 协作 |
| **Skill 仓库** | Git/MySQL/PostgreSQL/Nacos/Classpath 五种来源 | 集中化 Skill 管理 |
| **SubAgent** | `SubAgentTool` + `SubAgentConfig` | Agent 嵌套调用 |
| **Plan Mode** | `PlanNotebook` + `PlanModeMiddleware` | 结构化任务管理 |
| **OpenTelemetry** | `AgentTraceMiddleware` + Otel | 生产级可观测性 |
| **分布式友好** | `RedisStore`/`JdbcStore` + `RedisSandboxExecutionGuard` | 多副本部署 |

---

## 3. 目标架构

### 3.1 五层架构总览

```
+======================================================================+
|                    SaaS Control Plane (控制面)                          |
|  +----------+ +----------+ +----------+ +--------------------------+ |
|  | Auth     | | Tenant   | | Billing  | | Admin Dashboard          | |
|  | Service  | | Manager  | | Engine   | | (admin-starter)          | |
|  +----------+ +----------+ +----------+ +--------------------------+ |
+======================================================================+
                          |
                    API Gateway (Spring Cloud Gateway / Higress)
                          |
+======================================================================+
|                 QwenPaw SaaS Core Engine (数据面)                       |
|                                                                        |
|  Spring Boot Application (WebFlux)                                     |
|    +-- TenantContextMiddleware (JWT -> RuntimeContext)                 |
|    +-- QwenPawAgent (HarnessAgent)                                     |
|    |   +-- Middleware Chain:                                           |
|    |   |   TenantIsolation -> RateLimit -> SandboxLifecycle ->         |
|    |   |   WorkspaceContext -> Permission -> UsageMetering ->          |
|    |   |   AgentTrace                                                  |
|    |   +-- Toolkit: ShellExecute / Filesystem / Memory / Browser       |
|    |   +-- SandboxContext (IsolationScope.USER)                        |
|    |   +-- PermissionEngine                                            |
|    +-- ChannelManager (DingTalk/Feishu/Telegram/Discord/...)           |
|    +-- SchedulerService (Quartz / XXL-Job)                             |
+======================================================================+
                          |
+======================================================================+
|                    Sandbox Runtime Layer                                |
|                                                                        |
|  SandboxManager + SandboxStateStore (Redis/JDBC)                       |
|    +-- DockerSandbox    (harness 已有)                                  |
|    +-- K8sSandbox       (harness 已有)                                  |
|    +-- DaytonaSandbox   (harness 已有)                                  |
|    +-- AgentRunSandbox  (harness 已有)                                  |
|    +-- CubeSandbox      (新建 SandboxClient, E2B SDK 兼容)              |
+======================================================================+
                          |
+======================================================================+
|                    Persistent Storage Layer                             |
|  +-----------------+  +------------------+  +----------------+        |
|  | PostgreSQL 16   |  | Redis/Valkey     |  | MinIO/S3       |        |
|  | users/orgs      |  | RedisSession     |  | workspace      |        |
|  | agent_configs   |  | RedisStore       |  | archives       |        |
|  | chat_history    |  | SandboxState     |  | user uploads   |        |
|  | memories        |  | RateLimit        |  |                |        |
|  | usage_records   |  | JuiceFS metadata |  |                |        |
|  +-----------------+  +------------------+  +----------------+        |
+======================================================================+
```

### 3.2 关键架构决策

| # | 决策 | 理由 |
|---|------|------|
| 1 | **沙箱复用 harness Sandbox 体系** | Docker/K8s/Daytona/AgentRun 已有完整实现，SandboxManager 已有优先级 acquire 逻辑 |
| 2 | **多租户通过 RuntimeContext + TenantSessionKey** | 框架文档明确推荐此模式，Session/Store/NamespaceFactory 全部支持 |
| 3 | **IsolationScope.USER 作为默认沙箱隔离级别** | 同一用户跨 session 共享沙箱，符合 QwenPaw「一用户一沙箱」策略 |
| 4 | **RemoteFilesystemSpec + JdbcStore 做文件持久化** | 框架已有两层读架构 + CAS 写入，无需自建 FUSE |
| 5 | **频道层保持宿主机运行** | 与 Python 方案一致：频道是消息入口，不执行用户代码 |
| 6 | **Spring Boot WebFlux 替换 FastAPI** | 与框架的 Reactor 生态一致，AG-UI starter 原生支持 |
| 7 | **AG-UI 作为前端协议** | 框架已有完整的 AG-UI 适配器和 starter |
| 8 | **新增 CubeSandbox 作为 SandboxClient 实现** | 兼容 E2B SDK REST API，仅需实现 SandboxClient 接口 |

---

## 4. Maven 模块设计

### 4.1 新增模块树

在 agentscope-java-1 项目根目录下新增 `agentscope-saas` 顶层模块：

```
agentscope-java-1/
|-- agentscope-core/                  (已有，不修改)
|-- agentscope-harness/               (已有，不修改)
|-- agentscope-extensions/            (已有，不修改)
|-- agentscope-saas/                  *** 新增 ***
|   |-- agentscope-saas-core/         SaaS 核心抽象 (tenant, channel SPI, billing, middleware)
|   |-- agentscope-saas-channels/     消息频道适配器 (dingtalk, feishu, telegram, discord, ...)
|   |-- agentscope-saas-sandbox/      沙箱扩展 (CubeSandbox, SandboxPool, SandboxBroker)
|   |-- agentscope-saas-storage/      存储扩展 (MinIO, JuiceFS)
|   |-- agentscope-saas-app/          SaaS Spring Boot 应用 (auth, admin, gateway)
|-- agentscope-saas-spring-boot-starters/
    |-- agentscope-saas-spring-boot-starter/
    |-- agentscope-saas-channel-starter/
    |-- agentscope-saas-sandbox-starter/
```

### 4.2 模块依赖关系

```
agentscope-saas-app
    |-- agentscope-saas-core --> agentscope-harness --> agentscope-core
    |-- agentscope-saas-channels/* --> agentscope-saas-core
    |-- agentscope-saas-sandbox --> agentscope-saas-core + agentscope-harness
    |-- agentscope-saas-storage --> agentscope-saas-core
    |-- agentscope-extensions-session-redis (已有)
    |-- agentscope-extensions-agui (已有)
    |-- agentscope-extensions-scheduler (已有)
```

---

## 5. 多租户上下文传播

### 5.1 框架已有的多租户构建块

agentscope-java 已提供完整的多租户构建块，**无需自建 ContextVar 或 ThreadLocal**：

| 构建块 | 位置 | 作用 |
|--------|------|------|
| `SessionKey` | core.state | 标记接口，文档推荐 `TenantSessionKey(tenantId, userId, sessionId)` |
| `RuntimeContext` | core.agent | 每次调用携带 sessionId + userId + typed attributes |
| `IsolationScope` | harness.agent | SESSION/USER/AGENT/GLOBAL 四级隔离 |
| `NamespaceFactory` | harness.agent.store | `rc -> List<String>` 动态命名空间 |
| `SandboxIsolationKey` | harness.agent.sandbox | 自动从 RuntimeContext 解析沙箱槽位 |

### 5.2 TenantSessionKey 实现

```java
public record TenantSessionKey(
    String orgId, String userId, String agentId, String sessionId
) implements SessionKey {
    @Override
    public String toIdentifier() {
        return orgId + ":" + userId + ":" + agentId + ":" + sessionId;
    }
}
```

### 5.3 TenantContext + Middleware 注入

```java
public record TenantContext(
    String orgId, String userId, String plan,
    int maxSandboxes, long tokenQuota
) {}

public class TenantContextMiddleware implements MiddlewareBase {
    private final TenantResolver tenantResolver;

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        if (agent instanceof AgentBase ab) {
            RuntimeContext rc = ab.getRuntimeContext();
            String auth = rc != null ? rc.get("Authorization") : null;
            TenantContext tc = tenantResolver.resolve(auth);
            if (tc != null && rc != null) {
                rc.put(TenantContext.class, tc);
                rc.put(TenantSessionKey.class, new TenantSessionKey(
                    tc.orgId(), tc.userId(), agent.getName(), rc.getSessionId()));
            }
        }
        return next.apply(input);
    }
}
```

### 5.4 NamespaceFactory 多租户文件隔离

```java
NamespaceFactory workspaceNs = rc -> {
    TenantContext tc = rc.get(TenantContext.class);
    return tc == null ? List.of("anonymous")
        : List.of("workspaces", tc.orgId(), tc.userId());
};
```

### 5.5 完整调用链路

```
HTTP Request (JWT) --> Spring Controller --> RuntimeContext builder
    --> agent.call(msg, rc)
        --> [1] TenantContextMiddleware   (JWT -> TenantContext)
        --> [2] RateLimitMiddleware       (per-org rate check)
        --> [3] UsageMeteringMiddleware   (start counter)
        --> [4] SandboxLifecycleMiddleware (acquire sandbox, harness已有)
        --> [5] WorkspaceContextMiddleware (inject context, harness已有)
        --> [6] PermissionEngine          (tool permissions, core已有)
        --> [7] AgentTraceMiddleware      (Otel span, harness已有)
        --> ReActAgent: reasoning + acting (tools in sandbox)
        --> doFinally: persistState + record usage
```

---

## 6. 数据模型设计

### 6.1 租户与用户

```sql
CREATE TABLE orgs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(64) UNIQUE NOT NULL,
    plan VARCHAR(32) DEFAULT 'free',
    status VARCHAR(20) DEFAULT 'active',
    settings JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL REFERENCES orgs(id),
    email VARCHAR(255) UNIQUE NOT NULL,
    hashed_password VARCHAR(255),
    display_name VARCHAR(255),
    role VARCHAR(20) DEFAULT 'member',
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE api_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL REFERENCES orgs(id),
    user_id UUID NOT NULL REFERENCES users(id),
    key_hash VARCHAR(64) UNIQUE NOT NULL,
    name VARCHAR(255),
    scopes TEXT[] DEFAULT '{}',
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
```

### 6.2 Agent 与沙箱

```sql
CREATE TABLE agents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL REFERENCES orgs(id),
    user_id UUID NOT NULL REFERENCES users(id),
    name VARCHAR(255) NOT NULL,
    config JSONB NOT NULL DEFAULT '{}',
    model_config JSONB NOT NULL DEFAULT '{}',
    status VARCHAR(20) DEFAULT 'active',
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(org_id, user_id, name)
);

CREATE TABLE sandboxes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL,
    user_id UUID NOT NULL,
    agent_id UUID NOT NULL REFERENCES agents(id),
    sandbox_type VARCHAR(32) NOT NULL,
    sandbox_state JSONB,
    status VARCHAR(20) DEFAULT 'creating',
    last_active_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(org_id, agent_id)
);
```

### 6.3 会话、记忆与用量

```sql
CREATE TABLE chat_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL, user_id UUID NOT NULL,
    agent_id UUID NOT NULL REFERENCES agents(id),
    title VARCHAR(500), summary TEXT,
    message_count INTEGER DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE memories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL, user_id UUID NOT NULL,
    agent_id UUID NOT NULL REFERENCES agents(id),
    content TEXT NOT NULL,
    memory_type VARCHAR(32) DEFAULT 'fact',
    embedding vector(1536),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE usage_records (
    id BIGSERIAL PRIMARY KEY,
    org_id UUID NOT NULL, user_id UUID NOT NULL,
    metric VARCHAR(64) NOT NULL,
    value BIGINT NOT NULL,
    recorded_at TIMESTAMPTZ DEFAULT NOW()
) PARTITION BY RANGE (recorded_at);

CREATE TABLE subscriptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL REFERENCES orgs(id),
    plan VARCHAR(64) NOT NULL,
    max_agents INTEGER DEFAULT 3,
    max_sandboxes INTEGER DEFAULT 2,
    monthly_token_quota BIGINT DEFAULT 1000000,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
```

---

## 7. 沙箱与执行层

### 7.1 复用 agentscope-java 已有沙箱体系

agentscope-java harness 已提供完整的沙箱抽象，**不需要自建 ExecutionBackend**：

| 组件 | 位置 | 作用 |
|------|------|------|
| `Sandbox` 接口 | `harness.agent.sandbox` | 生命周期: start/stop/shutdown/exec |
| `SandboxClient` | `harness.agent.sandbox` | 工厂: create/resume/serializeState |
| `SandboxManager` | `harness.agent.sandbox` | acquire 优先级: external > persisted > create |
| `SandboxStateStore` | `harness.agent.sandbox` | 状态持久化 (Session/Workspace/Redis) |
| `SandboxIsolationKey` | `harness.agent.sandbox` | RuntimeContext + IsolationScope -> 唯一键 |
| `SandboxLifecycleMiddleware` | `harness.agent.middleware` | 自动 acquire/release |
| `DockerSandbox` | `impl.docker` | Docker 容器沙箱 |
| `K8sSandbox` | `impl.k8s` | K8s Pod 沙箱 |
| `DaytonaSandbox` | `impl.daytona` | Daytona 开发环境沙箱 |
| `AgentRunSandbox` | `impl.agentrun` | AgentRun 云端沙箱 |
### 7.2 IsolationScope.USER - 一用户一沙箱

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("qwenpaw-agent")
    .model(model)
    .workspace(workspace)
    .session(RedisSession.builder().jedisClient(jedis).build())
    .filesystem(new SandboxFilesystemSpec()
        .isolationScope(IsolationScope.USER)
        .sandboxClient(sandboxClient))
    .sandboxContext(SandboxContext.builder()
        .client(sandboxClient)
        .isolationScope(IsolationScope.USER)
        .build())
    .middleware(new TenantContextMiddleware(tenantResolver))
    .middleware(new RateLimitMiddleware(rateLimiter))
    .middleware(new UsageMeteringMiddleware(usageService))
    .build();
```

SandboxIsolationKey 解析流程:
```
IsolationScope.USER + RuntimeContext(userId="alice")
  -> SandboxIsolationKey{scope=USER, value="alice"}
  -> SandboxManager.acquire()
      -> Priority 3: stateStore.load() -> resume existing sandbox
      -> Priority 4: client.create() -> create new sandbox
```

### 7.3 CubeSandbox 集成 (新建 SandboxClient)

```java
public class CubeSandboxClient implements SandboxClient<CubeSandboxClientOptions> {
    private final String apiUrl;  // http://cube.internal:8080

    @Override
    public Sandbox create(WorkspaceSpec spec, SnapshotSpec snapshot,
                          CubeSandboxClientOptions options) {
        // POST /sandboxes - E2B SDK compatible REST API
    }

    @Override
    public Sandbox resume(SandboxState state) {
        // POST /sandboxes/{id}/resume
    }
}
```

### 7.4 SandboxBroker - DB 映射层

```java
public class SandboxBroker {
    private final SandboxRepository sandboxRepo;

    public SandboxState getOrCreateMapping(
            String orgId, String userId, String agentId) {
        return sandboxRepo.findByOrgIdAndAgentId(orgId, agentId)
            .filter(e -> !"terminated".equals(e.getStatus()))
            .map(e -> client.deserializeState(
                e.getSandboxState().toString()))
            .orElse(null);  // null = SandboxManager will create new
    }
}
```

### 7.5 沙箱生命周期状态机

```
            +------+
            | NONE |
            +--+---+
               | create (~30-60s)
               v
          +----------+
          | CREATING |
          +----+-----+
               v
     +-------------------+
     |     RUNNING       |
     +---+-----------+---+
         |           |
    idle TTL      manual
         v           v
    +--------+  +-----------+
    | PAUSED |  | TERMINATED|
    +---+----+  +-----------+
        |
    user returns -> resume (~3-5s E2B, <60ms Cube)
        v
    +---------+
    | RUNNING |
    +---------+
```

### 7.6 TTL 策略 (按套餐分级)

| 套餐 | 空闲超时 | 最大生命周期 | 最大沙箱数 |
|------|---------|-------------|-----------|
| Free | 10 min | 24 hours | 1 |
| Pro | 1 hour | 7 days | 3 |
| Team | 2 hours | 30 days | 5 |
| Enterprise | 4 hours | Unlimited | Custom |

---

## 8. 工作区与文件持久化

### 8.1 复用 harness 文件系统

| 组件 | SaaS 角色 |
|------|----------|
| SandboxBackedFilesystem | 工具在沙箱内执行文件操作 |
| RemoteFilesystem | 跨调用持久化 (MEMORY.md, sessions) |
| CompositeFilesystem | 两层读: Remote + Local template |
| BaseStore (Redis/JDBC) | 小文件高频读写 |
| NamespaceFactory | 多租户路径隔离 |

### 8.2 两层读架构 (复用框架设计)

```
read_file("MEMORY.md")
  -> SandboxBackedFilesystem (proxy)
      |-- sandbox active: sandbox.exec("cat /workspace/MEMORY.md")
      |-- no sandbox: CompositeFilesystem
          |-- RemoteFilesystem -> BaseStore.get(namespace, key)
          |-- LocalFilesystem -> workspace template fallback
```

### 8.3 MinIO 集成 (大对象归档)

```
minio://qwenpaw-prod/
|-- snapshots/{org_id}/{user_id}/{agent_id}/{timestamp}.tar.gz
|-- uploads/{org_id}/{user_id}/
|-- templates/skills/
|-- exports/{org_id}/{user_id}/
```

### 8.4 生产配置

```java
BaseStore store = JdbcStore.builder(dataSource)
    .initializeSchema(true).build();

RemoteFilesystemSpec remoteFs = new RemoteFilesystemSpec(store)
    .isolationScope(IsolationScope.USER)
    .anonymousUserId("_system")
    .namespaceFactory(rc -> {
        TenantContext tc = rc.get(TenantContext.class);
        return tc == null ? List.of("_anonymous")
            : List.of("org", tc.orgId(), "user", tc.userId());
    });
```

---

## 9. 流式传输与并发控制

### 9.1 AgentEvent 流式架构 (v2 事件系统)

agentscope-java v2 引入了细粒度的 `AgentEvent` 类型系统（28 种事件类型），替代了 v1 的粗粒度 `Event`：

```java
// io.agentscope.core.event.AgentEvent — 28 种类型
AgentEventType:
  AGENT_START / AGENT_END
  MODEL_CALL_START / MODEL_CALL_END
  TEXT_BLOCK_START / TEXT_BLOCK_DELTA / TEXT_BLOCK_END
  THINKING_BLOCK_START / THINKING_BLOCK_DELTA / THINKING_BLOCK_END
  TOOL_CALL_START / TOOL_CALL_DELTA / TOOL_CALL_END
  TOOL_RESULT_START / TOOL_RESULT_TEXT_DELTA / TOOL_RESULT_DATA_DELTA / TOOL_RESULT_END
  EXCEED_MAX_ITERS / REQUIRE_USER_CONFIRM / REQUIRE_EXTERNAL_EXECUTION
  USER_CONFIRM_RESULT / EXTERNAL_EXECUTION_RESULT / REQUEST_STOP
```

`ReActAgent.streamEvents(List<Msg>)` 返回 `Flux<AgentEvent>`：

```java
Flux<AgentEvent> events = agent.streamEvents(msgs);
events.subscribe(event -> {
    switch (event.getType()) {
        case TEXT_BLOCK_DELTA -> forwardToSSE(event);  // 实时文本
        case THINKING_BLOCK_DELTA -> forwardToSSE(event);  // 思考过程
        case TOOL_CALL_DELTA -> forwardToSSE(event);  // 工具调用
        case TOOL_RESULT_TEXT_DELTA -> forwardToSSE(event);  // 工具结果
        case AGENT_END -> completeSSE();
    }
});
```

### 9.2 Sandbox 边界流式转发

Agent 在沙箱内执行时，`Flux<AgentEvent>` 需要穿越 sandbox 边界回到宿主机：

```
用户浏览器 / DingTalk / Discord
    │
    │ SSE (AG-UI 协议)
    ▼
┌──────────────────────────────────────────────┐
│            QwenPaw App (宿主机)                │
│                                                │
│  AguiWebFluxHandler                            │
│    └─ Flux<ServerSentEvent<AguiEvent>>         │
│         ▲                                      │
│         │ AgentEventBridge                     │
│         │ (HTTP SSE 或 gRPC stream)            │
│                                                │
└────────┼───────────────────────────────────────┘
         │
         ▼
┌──────────────────────────────────────────────┐
│            Sandbox (Docker / KVM μVM)          │
│                                                │
│  ReActAgent.streamEvents()                     │
│    └─ Flux<AgentEvent>                         │
│         └─ StreamForwardingMiddleware          │
│              └─ HTTP POST → App endpoint       │
│                                                │
└──────────────────────────────────────────────┘
```

```java
// StreamForwardingMiddleware — 在沙箱内运行，转发事件到宿主机
public class StreamForwardingMiddleware implements MiddlewareBase {
    private final String appStreamUrl;

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        Flux<AgentEvent> events = next.apply(input);
        // 转发每个事件到宿主机，同时向下游传递
        return events.doOnNext(event ->
            httpClient.post(appStreamUrl)
                .bodyValue(event)
                .subscribe()
        );
    }
}
```

### 9.3 并发控制：同一 Sandbox 多请求

同一用户可能同时发送多个请求（直接输入 + Cron 触发 + 后台任务）。harness 已有的 `RedisSandboxExecutionGuard` 提供分布式锁，但需要一个请求队列来管理并发：

```java
public class SandboxRequestQueue {
    private final SandboxExecutionGuard guard;
    private final Map<String, Queue<PendingRequest>> queues;

    /**
     * 提交请求到沙箱执行队列。
     * 如果沙箱正忙，请求排队等待。
     * 高优先级请求可中断低优先级任务。
     */
    public Mono<Msg> submit(String isolationKey, Msg msg,
                            RuntimeContext rc, int priority) {
        return Mono.create(sink -> {
            PendingRequest req = new PendingRequest(msg, rc, priority, sink);
            queues.computeIfAbsent(isolationKey, k -> new PriorityQueue<>())
                  .add(req);
            tryProcessNext(isolationKey);
        });
    }

    private void tryProcessNext(String key) {
        Queue<PendingRequest> q = queues.get(key);
        if (q == null || q.isEmpty()) return;

        SandboxLease lease = guard.tryEnter(
            SandboxIsolationKey.of(IsolationScope.USER, key));
        if (lease == null) return;  // 沙箱正忙，等待释放

        PendingRequest req = q.poll();
        executeRequest(req, lease);
    }
}
```

**优先级规则：**

| 优先级 | 来源 | 可否被中断 |
|--------|------|-----------|
| 1 (最高) | 用户直接输入 | 不可 |
| 2 | SubAgentTool 调用 | 不可 |
| 3 | Cron 触发任务 | 可被 P1 中断 |
| 4 (最低) | 后台 Skill 任务 | 可被 P1-P3 中断 |

**中断抢占** — 使用框架已有的 `Agent.interrupt()` 协作中断机制：

```java
private void preemptIfHigherPriority(String key, int newPriority) {
    PendingRequest current = currentRequests.get(key);
    if (current != null && newPriority < current.priority()) {
        current.agent().interrupt();  // 协作中断，保留上下文
        // 当前任务中断后，lease 释放，tryProcessNext 被触发
    }
}
```

### 9.4 心跳与健康检查

```java
public class SandboxHealthChecker {
    private final SandboxManager sandboxManager;

    @Scheduled(fixedRate = 30_000)  // 每 30s
    public Mono<Void> checkAll() {
        return Flux.fromIterable(activeSandboxes)
            .flatMap(sandbox -> sandbox.exec(
                RuntimeContext.empty(), "echo ok", 5)
                .timeout(Duration.ofSeconds(10))
                .map(result -> result.exitCode() == 0
                    ? HealthStatus.HEALTHY : HealthStatus.UNHEALTHY)
                .onErrorReturn(HealthStatus.UNREACHABLE)
                .doOnNext(status -> {
                    if (status != HealthStatus.HEALTHY) {
                        unhealthyCount.merge(sandbox.id(), 1, Integer::sum);
                        if (unhealthyCount.get(sandbox.id()) >= 3) {
                            rebuildSandbox(sandbox);
                        }
                    }
                })
            ).then();
    }
}
```

### 9.5 性能 SLA 目标

| 指标 | 目标值 | 备注 |
|------|--------|------|
| API 响应 p50 | < 200ms | 非 Agent 调用 (认证、配置读取) |
| Agent 首 token 延迟 | < 3s | 含 sandbox resume + LLM 首 token |
| Agent 流式 chunk 间隔 | < 100ms | TEXT_BLOCK_DELTA 间隔 |
| Sandbox resume (Docker) | < 2s | Docker start |
| Sandbox resume (CubeSandbox) | < 200ms | KVM μVM resume |
| Sandbox resume (E2B) | < 5s | E2B Cloud resume |
| Sandbox 冷启动 (新创建) | < 30s | 含 workspace init + skill 部署 |
| 文件操作 (RemoteFilesystem) | < 50ms | 单次 read/write (JDBC/Redis) |
| Sandbox 可用率 | > 99.5% | 月度 |
| 数据持久化成功率 | > 99.99% | MinIO 纠删码保证 |
| 并发用户数 | 1000+ | 单集群 |

---

## 10. 消息频道集成层

### 10.1 Channel SPI

QwenPaw Python 版有 17 个频道实现。Java 版需新建 Channel SPI:

```java
public interface Channel {
    String getType();  // "dingtalk", "feishu", "telegram", etc.
    Mono<Void> start(ChannelContext context);
    Mono<Void> stop();
    Mono<Void> send(ChannelMessage message);
    boolean isActive();
}

public record ChannelContext(
    String orgId, String agentId,
    Map<String, Object> config,
    MessageRouter router
) {}

public record ChannelMessage(
    String channelType, String channelId,
    String senderId, String senderName,
    String content, String messageType,
    Map<String, Object> metadata
) {}
```

### 10.2 ChannelManager

```java
public class ChannelManager {
    private final Map<String, ChannelFactory> factories;
    private final Map<String, List<Channel>> activeChannels;

    public Mono<Void> startChannelsForAgent(
            String orgId, String agentId,
            List<AgentChannelConfig> configs) {
        return Flux.fromIterable(configs)
            .flatMap(cfg -> {
                Channel ch = factories.get(cfg.channelType()).create(cfg);
                return ch.start(buildContext(orgId, agentId, cfg))
                    .then(Mono.fromRunnable(() ->
                        activeChannels
                            .computeIfAbsent(orgId, k -> new ArrayList<>())
                            .add(ch)));
            }).then();
    }
}
```

### 10.3 MessageRouter - 消息路由到 Agent

```java
public class MessageRouter {
    public Mono<Msg> route(ChannelMessage msg) {
        Agent agent = agentRegistry.resolveAgent(
            msg.channelType(), msg.channelId());

        RuntimeContext rc = RuntimeContext.builder()
            .sessionId(msg.channelId() + ":" + msg.senderId())
            .userId(msg.senderId())
            .build();

        Msg userMsg = Msg.builder()
            .role(MsgRole.USER)
            .content(TextBlock.of(msg.content()))
            .build();

        return agent.call(userMsg, rc)
            .flatMap(response -> channelManager
                .send(msg.channelType(), msg.channelId(), response)
                .thenReturn(response));
    }
}
```

### 10.4 频道优先级

| 优先级 | 频道 | 说明 |
|--------|------|------|
| P0 | Web (AG-UI) | 已有 starter, 零额外开发 |
| P0 | Console | 最简单, 用于测试 |
| P1 | DingTalk | 企业用户 |
| P1 | Feishu | 企业用户 |
| P1 | Telegram | 海外用户 |
| P2 | Discord | 社区 |
| P2 | WeChat/WeCom | 中国用户 |
| P3 | Matrix/Mattermost/MQTT | 开源社区 |
| P3 | QQ/OneBot | 中国社区 |
| P4 | Voice/SIP | 语音交互 |

---

## 11. SaaS 控制面

### 11.1 认证授权 (Spring Security)

```java
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {
    @Bean
    public SecurityWebFilterChain filterChain(ServerHttpSecurity http) {
        return http
            .authorizeExchange(ex -> ex
                .pathMatchers("/api/auth/**").permitAll()
                .pathMatchers("/api/admin/**").hasRole("ADMIN")
                .anyExchange().authenticated())
            .oauth2ResourceServer(oauth2 ->
                oauth2.jwt(jwt -> jwt.jwtDecoder(jwtDecoder())))
            .build();
    }
}
```

### 11.2 TenantResolver

```java
@Component
public class JwtTenantResolver implements TenantResolver {
    private final SubscriptionRepository subscriptionRepo;

    @Override
    public TenantContext resolve(String authHeader) {
        Claims claims = parseJwt(authHeader);
        Subscription sub = subscriptionRepo.findByOrgId(claims.getOrgId());
        return new TenantContext(
            claims.getOrgId(), claims.getUserId(),
            sub.getPlan(), sub.getMaxSandboxes(),
            sub.getMonthlyTokenQuota());
    }
}
```

### 11.3 计费集成

```java
public class StripeBillingService implements BillingService {
    @Override
    public Mono<Void> recordUsage(String orgId, String metric, long value) {
        return usageRepo.insert(orgId, metric, value)
            .then(stripeClient.reportMeteredUsage(orgId, metric, value));
    }

    @Override
    public Mono<Boolean> checkQuota(String orgId, String metric) {
        return usageRepo.getMonthlyTotal(orgId, metric)
            .zipWith(subscriptionRepo.findByOrgId(orgId))
            .map(t -> t.getT1() < t.getT2().getQuota(metric));
    }
}
```

### 11.4 Admin API (复用 agentscope-admin-starter)

已有 Endpoint (直接复用):
- `/admin/agents` - Agent 列表/配置
- `/admin/sessions` - 会话管理
- `/admin/tools` - 工具注册表
- `/admin/models` - 模型配置
- `/admin/status` - 系统状态
- `/admin/usage` - 用量统计
- `/admin/doctor` - 健康检查

SaaS 新增:
- `/admin/tenants` - 租户管理 (CRUD)
- `/admin/sandboxes` - 沙箱池状态
- `/admin/billing` - 计费管理
- `/admin/channels` - 频道管理

---

## 12. 可观测性

### 12.1 分布式追踪 (复用框架已有能力)

agentscope-java 已内置 OpenTelemetry 集成，SaaS 版仅需配置多租户 context propagation：

```
Trace ID: abc-123 (跨 App → Sandbox → LLM)
│
├─ Span: tenant.resolve           (TenantContextMiddleware)
├─ Span: rate_limit.check         (RateLimitMiddleware)
├─ Span: sandbox.acquire          (SandboxLifecycleMiddleware)
│   └─ Span: sandbox.exec         (Sandbox 内)
│       ├─ Span: agent.reasoning  (ReActAgent → LLM API)
│       ├─ Span: agent.tool_call  (ReActAgent → Tool)
│       └─ Span: agent.acting     (ReActAgent → Tool execution)
├─ Span: usage.record             (UsageMeteringMiddleware)
└─ Span: stream.forward           (StreamForwardingMiddleware)
```

**实现** — 复用 `AgentTraceMiddleware`（harness 已有），SaaS 层添加 tenant attributes：

```java
public class TenantTraceMiddleware implements MiddlewareBase {
    @Override
    public Flux<AgentEvent> onAgent(Agent agent, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        Span span = Span.current();
        RuntimeContext rc = ((AgentBase) agent).getRuntimeContext();
        TenantContext tc = rc.get(TenantContext.class);
        if (tc != null) {
            span.setAttribute("tenant.org_id", tc.orgId());
            span.setAttribute("tenant.user_id", tc.userId());
            span.setAttribute("tenant.plan", tc.plan());
        }
        return next.apply(input);
    }
}
```

### 12.2 监控指标 (Micrometer + Actuator)

复用 `agentscope-admin-starter` 已有的 `MetricsRecorder` 和 `MetricsHook`，新增 SaaS 指标：

| 指标名 | 类型 | 标签 | 说明 |
|--------|------|------|------|
| `saas.sandbox.pool.size` | Gauge | type, status | 当前沙箱池状态 |
| `saas.sandbox.acquire.duration` | Timer | type, source | 沙箱 acquire+start 端到端耗时 |
| `saas.sandbox.request.queue_depth` | Gauge | type, scope | 等待 execution guard 的请求数 |
| `saas.sandbox.execution.active` | Gauge | type, scope | 正在持有 execution guard 的请求数 |
| `saas.sandbox.queue.wait.duration` | Timer | type, scope, outcome | 等待 execution guard 的耗时 |
| `saas.sandbox.queue.timeouts` | Counter | type, scope | execution guard 等待超时次数 |
| `saas.sandbox.lifecycle.events{event=force_evicted}` | Counter | type, event | 管理员人工驱逐 tracking row |
| `saas.agent.call.duration` | Timer | model | Agent 调用耗时 |
| `saas.llm.token.usage` | Counter | model, type | LLM token 消耗 |
| `saas.channel.messages` | Counter | channel_type, direction | 频道消息数 |
| `saas.storage.filesystem.ops` | Counter | op, namespace | 文件系统操作数 |

```java
@Component
public class SaasMetrics {
    private final MeterRegistry registry;

    public void recordSandboxAcquire(String type, String source, Duration duration) {
        registry.timer("saas.sandbox.acquire.duration",
            "type", type, "source", source)
            .record(duration);
    }

    public void recordTokenUsage(String model, String type, long tokens) {
        registry.counter("saas.llm.token.usage",
            "model", model, "type", type)
            .increment(tokens);
    }
}
```

运行时 Prometheus 指标不带 `org_id`/`user_id`，避免高基数拖垮指标后端；租户级排查走 Admin API、业务表和审计日志。

`/api/admin/sandboxes/{sandboxId}/force-evict` 是资源泄漏恢复接口：只允许 org-admin 操作本 org 记录，将非终态 tracking row 标记为 `evicted` 以释放配额。它不假定 provider stop 已完成；真实 E2B/Cube 后端清理由正常 release 链路或基础设施 GC 负责。

### 12.3 Grafana Dashboard

复用 Grafana + Prometheus + Loki 技术栈。关键面板：

| 面板 | 数据来源 | 用途 |
|------|---------|------|
| Sandbox Pool Overview | `saas.sandbox.pool.size` | 实时监控沙箱数量/状态 |
| Request Latency | `saas.agent.call.duration` | p50/p95/p99 延迟 |
| Token Usage by Org | `saas.llm.token.usage` | 按租户统计 token 消耗 |
| Channel Activity | `saas.channel.messages` | 各频道消息吞吐量 |
| Error Rate | `saas.agent.errors` | 按 org/type 的错误率 |
| Sandbox Health | `saas.sandbox.health_status` | 沙箱健康状态矩阵 |

---

## 13. 安全设计

### 13.1 多层安全

```
Layer 1: 沙箱隔离 (KVM microVM / Docker container)
  |-- 每个用户独立沙箱
  |-- 无法访问宿主机或其他沙箱
  |-- 网络隔离

Layer 2: PermissionEngine (core 框架, 直接复用)
  |-- PermissionRule: ALLOW/DENY/ASK per tool
  |-- PermissionMode: DEFAULT/BYPASS/EXPLORE
  |-- 每次工具调用运行时决策

Layer 3: API 认证/授权
  |-- JWT token + org_id 验证
  |-- 按 org 限流
  |-- API key 管理

Layer 4: 数据加密
  |-- API keys: AES-256-GCM 服务端加密
  |-- Memory 数据: DB 级加密 (PG TDE)
  |-- 存储: MinIO SSE-S3 (AES-256)
  |-- 传输: TLS 1.3
```

### 13.2 网络架构

```
Internet -> CDN/API Gateway -> QwenPaw App (host)
                                |-> Sandbox (user A)
                                |-> Sandbox (user B)
                                |-> PostgreSQL (internal)
                                |-> Redis/Valkey (internal)
                                |-> MinIO (internal)

Sandbox 出站: 仅允许 HTTPS to LLM API + internal MinIO
```

---

## 14. 优雅降级

### 14.1 基础设施故障降级策略

当基础设施组件故障时，系统自动降级而非直接报错：

| 故障组件 | 影响 | 降级策略 |
|----------|------|----------|
| **CubeSandbox/Docker 不可用** | 无法执行沙箱化工具 | 自动降级到 `LocalFilesystemSpec`（无隔离），告警管理员 |
| **MinIO 不可用** | 大文件持久化中断 | `RemoteFilesystem` 回退到 `JdbcStore` 存储（仅限小文件） |
| **PostgreSQL 不可用** | 认证/配置读取失败 | 只读缓存继续服务，写操作排队等待恢复 |
| **Redis/Valkey 不可用** | 沙箱状态/限流失效 | `SandboxStateStore` 降级到 `SessionSandboxStateStore`；限流降级到内存计数 |
| **LLM API 超时** | 模型调用失败 | 指数退避重试 (1s → 2s → 4s)，3 次失败返回友好提示 |
| **频道 Webhook 失败** | 消息无法送达 | 消息持久化到 PG `outbox` 表，频道恢复后重发 |

### 14.2 DegradationManager 实现

```java
@Component
public class DegradationManager {
    private final SandboxManager sandboxManager;
    private final HealthIndicator cubeHealth;
    private final HealthIndicator pgHealth;
    private final HealthIndicator redisHealth;

    public FilesystemSpec resolveFilesystem(TenantContext tc) {
        if (cubeHealth.health().getStatus() == Status.UP) {
            return new SandboxFilesystemSpec()
                .isolationScope(IsolationScope.USER)
                .sandboxClient(cubeSandboxClient);
        }
        // 降级到本地执行（无隔离）+ 告警
        alertService.sendAlert("sandbox_unavailable",
            "Falling back to local execution for org: " + tc.orgId());
        return new LocalFilesystemSpec()
            .mode(LocalFsMode.ROOTED)
            .pathPolicy(PathPolicy.of(Path.of("/workspace")));
    }

    public SandboxStateStore resolveStateStore() {
        if (redisHealth.health().getStatus() == Status.UP) {
            return new RedisSandboxStateStore(redisClient);
        }
        // 降级到 JDBC 存储
        return new SessionSandboxStateStore(jdbcStore);
    }
}
```

---

## 15. 部署架构

### 15.1 最小化部署 (PoC / 1-50 用户)

```
+--------------------------------------------------+
|       Single Linux Server (16C32G)                |
|                                                   |
|  QwenPaw App    Docker Engine    PostgreSQL       |
|  Valkey          MinIO            Traefik          |
+--------------------------------------------------+

Docker Compose:
  docker compose up -d
  |-- qwenpaw-app:    Spring Boot WebFlux app
  |-- postgres:       PostgreSQL 16
  |-- valkey:         Valkey 8 (Redis fork)
  |-- minio:          MinIO Server
  |-- traefik:        TLS + routing
```

### 15.2 生产级部署 (1000+ 用户)

```
+------------------------------------------------------------+
|                  K8s Cluster (3+ nodes)                      |
|                                                             |
|  QwenPaw App      Sandbox Runtime       PostgreSQL          |
|  (Deploy x3)      (DockerSandbox /       (Patroni HA        |
|                    K8sSandbox per pod)    primary+standby)   |
|                                                             |
|  Valkey Cluster   MinIO Cluster          JuiceFS CSI        |
|  (Sentinel, 3+)   (4+ nodes, NVMe)       (per-node daemon)  |
|                                                             |
|  Grafana + Prometheus + Loki           Ingress Controller   |
+------------------------------------------------------------+
```

### 15.3 开源中间件选型

| 层级 | 组件 | 选型 | 许可证 |
|------|------|------|--------|
| Sandbox | Docker / K8s / CubeSandbox | Apache 2.0 |
| Object Storage | MinIO | AGPL 3.0 |
| Database | PostgreSQL 16+ | PostgreSQL License |
| Cache | Valkey (Redis fork) | BSD 3-Clause |
| Monitoring | Grafana + Prometheus + Loki | AGPL / Apache |
| Gateway | Traefik / Higress | MIT / Apache |
| Scheduling | Quartz / XXL-Job | Apache 2.0 |

### 15.4 不用什么

| 方案 | 不用原因 |
|------|----------|
| MySQL | PG JSONB 更灵活 (agent config); R2DBC 响应式驱动成熟度不如 PG |
| MongoDB | 需要 ACID (计费/用户); PG 可同时做关系型和文档型 |
| Redis (6+) | 许可证变化 (RSAL/SSPL); Valkey BSD license 完全兼容 |
| Ceph | 运维太重; MinIO 对中小规模足够 |
| Kafka | 消息量不需要; RocketMQ / PG LISTEN/NOTIFY 可顶 |

---

## 16. 测试策略

### 16.1 测试金字塔

```
           ┌────────────┐
           │  E2E Tests  │  ← Playwright: 注册 → 创建 Agent → 对话 → 查看结果
           ├────────────┤
           │Integration │  ← 多租户隔离、沙箱生命周期、流式转发
           │   Tests    │
           ├────────────┤
           │ Unit Tests │  ← Middleware、TenantContext、RequestQueue
           └────────────┘
```

### 16.2 关键测试场景

| 测试场景 | 类型 | 验证内容 |
|----------|------|----------|
| **多租户隔离** | Integration | 用户 A 无法访问用户 B 的文件、Memory、Sandbox |
| **Sandbox TTL 回收** | Integration | 空闲 sandbox 自动 stop，用户回归自动 resume |
| **并发请求排队** | Unit | SandboxRequestQueue 正确排序和串行执行 |
| **流式事件转发** | Integration | Sandbox → App → AG-UI 的 SSE 事件完整性 |
| **优雅降级** | Integration | 关闭 Docker → 验证自动降级到 LocalFilesystemSpec |
| **数据持久化** | Integration | Sandbox 销毁 → 重建 → 文件恢复 |
| **冷启动性能** | Performance | sandbox 创建 < 30s，resume < 2s (Docker) |
| **LLM API 故障** | Unit | LLM 超时 → 重试 → 3 次失败后友好提示 |
| **权限控制** | Integration | 跨 org 的 API 调用被拒绝 |
| **SubAgent 隔离** | Integration | SubAgentTool 在正确的 tenant sandbox 中执行 |

### 16.3 Sandbox 测试基础设施

```java
@ExtendWith(MockitoExtension.class)
class TenantIsolationIntegrationTest {
    @Mock private SandboxClient sandboxClient;
    @Mock private SandboxStateStore stateStore;

    @Test
    void tenantA_cannot_access_tenantB_sandbox() {
        // Setup: Two isolated tenants
        TenantContext tenantA = new TenantContext("org-a", "user-a", "free", 1, 1_000_000);
        TenantContext tenantB = new TenantContext("org-b", "user-b", "free", 1, 1_000_000);

        SandboxManager manager = new SandboxManager(sandboxClient, stateStore);

        // Tenant A acquires sandbox
        SandboxIsolationKey keyA = SandboxIsolationKey.of(IsolationScope.USER, "user-a");
        SandboxAcquireResult resultA = manager.acquire(ctxA, rcA);

        // Tenant B should get a DIFFERENT sandbox
        SandboxIsolationKey keyB = SandboxIsolationKey.of(IsolationScope.USER, "user-b");
        SandboxAcquireResult resultB = manager.acquire(ctxB, rcB);

        assertThat(resultA.sandbox().getState().getSessionId())
            .isNotEqualTo(resultB.sandbox().getState().getSessionId());
    }
}
```

---

## 17. 实施路线图

### Phase 1: 基础架构 (4-6 周)

**目标**: 多租户上下文传播, 基础 SaaS 设施

```
[ ] TenantSessionKey + TenantContext 实现
[ ] TenantContextMiddleware
[ ] RateLimitMiddleware + UsageMeteringMiddleware
[ ] SaaS Spring Boot starter (core)
[ ] PostgreSQL schema + Flyway migrations
[ ] Spring Security + JWT 认证
[ ] 租户隔离单元测试
```

### Phase 2: 沙箱集成 (3-4 周)

**目标**: 多租户沙箱生命周期

```
[ ] CubeSandboxClient (SandboxClient 实现)
[ ] SandboxBroker + DB 映射
[ ] IsolationScope.USER 配置
[ ] SandboxStateStore + Redis backend
[ ] RedisSandboxExecutionGuard (多副本)
[ ] 集成测试: 多用户并发沙箱
```

### Phase 3: 频道系统 (4-6 周)

**目标**: 至少 5 个主要频道可用

```
[ ] Channel SPI + ChannelManager
[ ] MessageRouter 实现
[ ] P0: Web (AG-UI) 频道
[ ] P0: Console 频道
[ ] P1: DingTalk 频道适配器
[ ] P1: Feishu 频道适配器
[ ] P1: Telegram 频道适配器
[ ] Channel Spring Boot starter
```

### Phase 4: 存储与持久化 (2-3 周)

**目标**: 跨沙箱生命周期完整数据持久化

```
[ ] RemoteFilesystemSpec + JdbcStore/RedisStore
[ ] NamespaceFactory 多租户隔离
[ ] MinIO snapshot 集成
[ ] Memory 持久化 (LongTermMemory -> PostgreSQL)
[ ] Chat session 历史持久化
```

### Phase 5: 控制面与生产化 (4-6 周)

**目标**: 可自助使用的 SaaS 平台

```
[ ] 计费集成 (Stripe/Paddle)
[ ] Admin dashboard (扩展 admin-starter)
[ ] 监控: Grafana dashboards
[ ] 负载测试 (并发沙箱创建/销毁)
[ ] 文档与 API 参考
[ ] 部署自动化 (Docker Compose + Helm chart)
```

**总工期估算**: 17-25 周 (约 4-6 个月, 3-4 人团队)

---

## 18. 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| **CubeSandbox 部署复杂度** | 需要 K8s + KVM 基础设施 | Phase 2 额外投入 1 周做部署自动化; Docker 作为降级方案 |
| **频道 SDK 兼容性** | 部分频道需要 C 库 | 频道适配器作为 sidecar 容器运行 |
| **agentscope-java API 变化** | 集成断裂 | 锁定版本 (2.0.0-SNAPSHOT); 抽象层隔离 |
| **PostgreSQL 单点故障** | 核心业务中断 | Patroni HA (primary + standby); WAL 备份到 MinIO |
| **沙箱资源泄漏** | 资源耗尽 | TTL 强制回收; max sandboxes per org 配额 |
| **冷启动延迟** | 用户体验差 | 预测性预热; 付费用户 keep-warm |
| **现有功能回归** | SaaS 破坏单用户模式 | IsolationScope.SESSION 保持向后兼容; 全量回归测试 |
| **LLM API key 泄露** | 用户 API key 被盗 | 服务端加密存储; 每沙箱独立 env var 隔离 |
| **MinIO 集群故障** | 持久化文件不可用 | MinIO 纠删码保证单节点不丢; JuiceFS fallback 到直连 S3 |

---

## 附录 A: QwenPaw 功能 -> agentscope-java 映射速查

| QwenPaw (Python) | agentscope-java 等价物 | 备注 |
|------------------|----------------------|------|
| QwenPawAgent | HarnessAgent + Middleware | 直接复用 |
| execute_shell_command | ShellExecuteTool (harness) | 沙箱内执行 |
| read_file / write_file | FilesystemTool (harness) | 沙箱内执行 |
| browser_use | 需新建 BrowserTool | 沙箱内 Playwright |
| grep_search / glob_search | FilesystemTool (harness) | 沙箱内执行 |
| ToolGuardEngine | PermissionEngine (core) | 直接复用 |
| ChannelManager (17 channels) | Channel SPI (新建) | 需逐个适配 |
| MemoryManager | LongTermMemory (core) | 直接复用 |
| CronManager (APScheduler) | scheduler extension (Quartz/XXL) | 直接复用 |
| ExecutionBackend (自建) | Sandbox + SandboxClient (harness) | 直接复用 |
| SandboxBroker (自建) | SandboxManager + SandboxStateStore | 直接复用 |
| Config (Pydantic) | @ConfigurationProperties | Spring 标准 |

## 附录 B: 新建代码清单

```
agentscope-saas/
|-- agentscope-saas-core/
|   |-- tenant/TenantContext.java              租户上下文
|   |-- tenant/TenantSessionKey.java           多租户 SessionKey
|   |-- tenant/TenantResolver.java             JWT -> TenantContext
|   |-- middleware/TenantContextMiddleware.java 注入租户信息
|   |-- middleware/RateLimitMiddleware.java     限流
|   |-- middleware/UsageMeteringMiddleware.java 用量计量
|   |-- channel/Channel.java                   频道 SPI
|   |-- channel/ChannelManager.java            频道管理器
|   |-- channel/ChannelFactory.java            频道工厂 SPI
|   |-- channel/MessageRouter.java             消息路由
|   |-- billing/BillingService.java            计费服务
|   |-- billing/UsageRecord.java               用量记录
|   |-- config/SaasProperties.java             SaaS 配置
|
|-- agentscope-saas-sandbox/
|   |-- cube/CubeSandboxClient.java            CubeSandbox 集成
|   |-- broker/SandboxBroker.java              DB-backed 映射
|   |-- pool/SandboxPool.java                  沙箱池管理
|
|-- agentscope-saas-channels/
|   |-- dingtalk/DingTalkChannel.java          钉钉适配器
|   |-- feishu/FeishuChannel.java              飞书适配器
|   |-- telegram/TelegramChannel.java          Telegram 适配器
|   |-- discord/DiscordChannel.java            Discord 适配器
|   |-- web/WebChannel.java                    Web/AG-UI 适配器
|
|-- agentscope-saas-storage/
|   |-- minio/MinioSnapshotSpec.java           MinIO 快照
|
|-- agentscope-saas-app/
|   |-- auth/SecurityConfig.java               Spring Security
|   |-- admin/TenantAdminController.java       租户管理 API
|   |-- QwenPawSaasApplication.java            主应用
```

---

*文档版本: v2.0*
*创建日期: 2026-06-10*
*更新日期: 2026-06-10*
*v1.0 → v2.0 变更: 新增第 9 节（流式传输与并发控制，含 AgentEvent v2 类型系统、Sandbox 边界转发、并发队列、心跳健康检查、性能 SLA）、新增第 12 节（可观测性，含分布式追踪、Micrometer 指标、Grafana 面板）、新增第 14 节（优雅降级策略）、新增第 16 节（测试策略）、章节总数 15→18*
*基础框架: agentscope-java-1 v2.0.0-SNAPSHOT*
