# Java 路径 · 详细落地方案

> 基于 `agentscope-java`（v2.0.0-SNAPSHOT，已核实框架能力）重建 QwenPaw 为企业私有化多用户平台。新增 `agentscope-saas` 模块树，**不修改 core/harness**。**不含计费。**

总工期：**~30-40 周**（功能对齐私有化版）。

> ⚠️ **工期说明**：Java PDF 原估 17-25 周只覆盖"SaaS 脚手架 + 5 个渠道"，**未计入重建 QwenPaw 全部产品力**（17 渠道、技能系统、自演进记忆、Coding 模式、浏览器工具、技能市场）。本方案据实修正。

---

## 0. 前置认知：什么能复用，什么要重写

框架能力已逐项核实存在（见 [../enterprise-platform/](../enterprise-platform/) 验证结论）。但**"框架有等价物" ≠ "QwenPaw 的 Python 代码能搬"**。

| QwenPaw (Python) | Java 侧 | 真实性质 |
|------------------|---------|----------|
| `QwenPawAgent`(ReActAgent 子类) | `HarnessAgent` + 自定义 Middleware | **Java 重写**（行为移植） |
| `execute_shell_command` 等工具 | harness `ShellExecuteTool`/`FilesystemTool` | 框架自带，**沙箱内执行**（省自建） |
| `browser_use` | 需新建 `BrowserTool`（Java Playwright） | **Java 重写** |
| `ToolGuardMixin` | core `PermissionEngine`+`PermissionRule` | 框架自带（规则要重配） |
| 17 个 channel | Channel SPI（新建）+ 逐个适配器 | **Java 全部重写** |
| `MemoryManager`/自演进记忆 | core `LongTermMemory`+`Memory` | 框架有底座，**演进逻辑 Java 重写** |
| `CronManager`(apscheduler) | extension scheduler（Quartz/XXL-Job） | 框架自带 |
| `ExecutionBackend`/`SandboxBroker` | harness `Sandbox`/`SandboxManager`/`SandboxStateStore` | **框架自带（最大省力点）** |
| Coding 模式（Web IDE/LSP/AST） | 无等价物 | **Java 重写或暂缺** |
| 技能市场/插件加载 | Skill 仓库（Git/MySQL/Nacos…）部分支持 | **大部分 Java 重写** |
| FastAPI / fastapi-users | Spring Boot WebFlux / Spring Security | 框架替换 |

**净省力**：沙箱执行层、文件持久化（无 FUSE）、权限引擎、中间件、AG-UI、调度、多副本分布式锁 —— 这些是 Java 路径相对 Python 路径的真正优势。
**净重写**：QwenPaw 的全部"产品功能"。

---

## 1. 框架已验证能力（可直接用，不自建）

| 能力 | 类/位置（已核实） |
|------|------------------|
| 沙箱 | `Sandbox`/`SandboxClient`/`SandboxManager`(优先级 acquire)/`SandboxIsolationKey`/`SandboxLifecycleMiddleware` @ `agentscope-harness/.../sandbox` |
| 沙箱实现 | `DockerSandbox`(harness) + `KubernetesSandbox`/`DaytonaSandbox`/`AgentRunSandbox`/`E2bSandbox`(extensions) |
| 隔离 | `IsolationScope`{SESSION,**USER**,AGENT,GLOBAL} @ harness |
| 文件系统 | `SandboxBackedFilesystem`/`RemoteFilesystem`+`RemoteFilesystemSpec`/`CompositeFilesystem`/`LocalFilesystem`；两层读 + `BaseStore.putIfVersion()` CAS 写 |
| Store | `JdbcStore`(mysql ext)/`RedisStore`(redis ext) |
| 中间件 | `MiddlewareBase`{onAgent,onReasoning,onActing,onModelCall,onSystemPrompt} @ core |
| 权限 | `PermissionEngine`+`PermissionMode`{DEFAULT,ACCEPT_EDITS,EXPLORE,BYPASS,DONT_ASK} @ core |
| Agent | `HarnessAgent`/`ReActAgent`(`streamEvents→Flux<AgentEvent>`)/`AgentBase` |
| 事件 v2 | `AgentEvent`+`AgentEventType`(25 类型) @ core.event |
| 扩展 | AG-UI starter / scheduler / session-redis / session-mysql / studio / admin-starter(`MetricsRecorder`/`MetricsHook`) / **`RedisSandboxExecutionGuard`** |
| 记忆 | `Memory`/`LongTermMemory` @ core |
| 多 Agent | A2A(client/server)+Nacos / `SubAgentTool`+`SubAgentConfig` |
| Plan | `PlanModeMiddleware`/`PlanModeManager`（非 `PlanNotebook`） |
| 追踪 | `AgentTraceMiddleware` @ harness（OTel 1.61） |
| 栈 | JDK 17 / Spring Boot 4.0.4 / Reactor 2025 |

> 命名差异需注意：`K8sSandbox`→`KubernetesSandbox`、`AbstractFilesystem`→`AbstractSandboxFilesystem`、无独立 `SessionKey`/`PlanNotebook`/`Session` 类。

---

## 2. 新增模块树（不改 core/harness）

```
agentscope-java/
├── agentscope-core/        (已有，不改)
├── agentscope-harness/     (已有，不改)
├── agentscope-extensions/  (已有，不改)
└── agentscope-saas/        *** 新增 ***
    ├── agentscope-saas-core/      # tenant 上下文、channel SPI、quota、middleware、audit
    ├── agentscope-saas-sandbox/   # CubeSandboxClient、SandboxBroker、SandboxPool
    ├── agentscope-saas-channels/  # dingtalk/feishu/telegram/discord/web... 适配器
    ├── agentscope-saas-storage/   # MinIO 快照
    ├── agentscope-saas-tools/     # BrowserTool 等 QwenPaw 特有工具的 Java 重写
    ├── agentscope-saas-skill/     # 技能市场（扫描/审核/启用）
    ├── agentscope-saas-app/       # Spring Boot 应用（auth/admin/gateway）
    └── agentscope-saas-spring-boot-starters/
```
依赖：`saas-app → saas-core → harness → core`；channels/sandbox/storage/tools → saas-core。

---

## 3. 多租户上下文（用框架原语，不自建 ThreadLocal）

```java
public record TenantContext(String orgId, String userId, String role,
                            int maxSandboxes, long tokenQuota) {}

public class TenantContextMiddleware implements MiddlewareBase {
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc,
            AgentInput in, Function<AgentInput, Flux<AgentEvent>> next) {
        TenantContext tc = tenantResolver.resolve(rc.get("Authorization"));
        if (tc != null) rc.put(TenantContext.class, tc);
        return next.apply(in);
    }
}
// 文件隔离：NamespaceFactory rc -> List.of("org", tc.orgId(), "user", tc.userId())
// 沙箱隔离：IsolationScope.USER（一用户一沙箱，SandboxIsolationKey 自动解析）
```
完整调用链（全部中间件，注意全部是 onAgent 洋葱包裹）：
```
JWT → Controller → RuntimeContext → agent.call(msg, rc)
  → TenantContextMiddleware → RateLimitMiddleware → UsageMeteringMiddleware(计量,非计费)
  → SandboxLifecycleMiddleware(框架,acquire) → PermissionEngine(框架)
  → AgentTraceMiddleware(框架,OTel) → ReActAgent: reasoning+acting(工具在沙箱)
  → doFinally: persistState + recordUsage
```

---

## 4. 文件持久化（框架原生，无 FUSE）

直接用框架两层读：沙箱活跃→代理到沙箱内；不活跃→`RemoteFilesystem`→`BaseStore`(JDBC/Redis)；大对象归档 MinIO。`NamespaceFactory` 做多租户路径隔离。**无需 JuiceFS/FUSE**（相对 Python 路径的天然优势）。

```java
HarnessAgent.builder()
  .filesystem(new SandboxFilesystemSpec().isolationScope(IsolationScope.USER)
                                         .sandboxClient(cubeClient))
  .session(RedisSession.builder().jedisClient(jedis).build())
  .sandboxContext(SandboxContext.builder().client(cubeClient)
                                          .isolationScope(IsolationScope.USER).build())
  .middleware(new TenantContextMiddleware(tenantResolver))
  .build();
```

---

## 5. 分阶段实施

### Phase 1（4-6 周）— 多租户基础设施
- [ ] `agentscope-saas-core`：`TenantContext`/`TenantResolver`/`TenantContextMiddleware`
- [ ] `RateLimitMiddleware` + `UsageMeteringMiddleware`（计量，无计费）
- [ ] SaaS Spring Boot starter；PostgreSQL schema + Flyway 迁移
- [ ] Spring Security + JWT + SSO（OIDC/SAML）
- [ ] 租户隔离单测（`SandboxManager.acquire` 对 user-a/user-b 返回不同沙箱）
- **DoD**：多租户上下文全链路传播；RLS + 沙箱隔离单测通过

### Phase 2（3-4 周）— 沙箱集成
- [ ] `CubeSandboxClient implements SandboxClient`（E2B 兼容 REST）
- [ ] `SandboxBroker` + DB 映射（`sandboxes` 表）
- [ ] `IsolationScope.USER` 配置；`SandboxStateStore` + Redis backend
- [ ] `RedisSandboxExecutionGuard`（多副本分布式锁，框架自带）
- [ ] 集成测试：多用户并发沙箱
- **DoD**：一用户一沙箱，resume <60ms(Cube)/<2s(Docker)，多副本无冲突

### Phase 3（6-8 周）— 工具与 Agent 行为重建 ⭐（PDF 低估的重头）
- [ ] `QwenPawAgent` 行为用 `HarnessAgent` + 自定义 Middleware 重建（人设/技能加载/记忆挂载）
- [ ] 工具：shell/file/grep/glob 直接用 harness 工具（沙箱内）
- [ ] **新建 `BrowserTool`**（Java Playwright，沙箱内）
- [ ] 自演进记忆逻辑用 `LongTermMemory` 重建（反思/沉淀）
- [ ] `PermissionEngine` 规则按 QwenPaw tool_guard 语义重配
- [ ] （可选）Coding 模式 Web IDE / LSP —— 评估是否首期纳入
- **DoD**：Java Agent 在沙箱内完成 shell/文件/浏览器/记忆全流程，行为对齐 QwenPaw

### Phase 4（6-8 周）— 渠道系统重建 ⭐
- [ ] `Channel` SPI + `ChannelManager` + `MessageRouter`
- [ ] P0：Web(AG-UI，starter 现成) + Console
- [ ] P1：DingTalk / Feishu / Telegram 适配器（Java 重写）
- [ ] P2+：Discord / WeChat / 其余按需
- [ ] Channel Spring Boot starter；外部身份 ↔ user 绑定
- **DoD**：≥5 主要渠道可用，多租户路由正确

### Phase 5（4-6 周）— 存储/技能市场/控制面/生产化
- [ ] `RemoteFilesystemSpec` + `JdbcStore`/`RedisStore` + `NamespaceFactory` 多租户
- [ ] MinIO 快照集成；Memory/会话历史持久化到 PostgreSQL
- [ ] 技能市场：扫描/审核/组织启用（Skill 仓库扩展 + 自建审核）
- [ ] 模型网关：内网 vLLM/Ollama 路由 + 密钥托管 + token 配额
- [ ] Admin（扩展 admin-starter）：`/admin/tenants`、`/admin/sandboxes`、`/admin/usage`、`/admin/channels`、`/admin/audit`
- [ ] 优雅降级（`DegradationManager`：Cube 不可用→`LocalFilesystemSpec`；Redis 不可用→`SessionSandboxStateStore`）
- [ ] OTel(`TenantTraceMiddleware`) + Micrometer 指标 + Grafana；负载测试；离线交付(Docker Compose + Helm)
- **DoD**：可自助使用、可观测、可离线私有化交付、跨沙箱数据持久化完整

---

## 6. 风险与对策

| 风险 | 影响 | 对策 |
|------|------|------|
| **框架 v2.0.0-SNAPSHOT + Spring Boot 4** | 生产稳定性 | 锁定 commit/版本，抽象层隔离；等待/推动框架 GA；建内部镜像仓 |
| **重建 QwenPaw 功能被低估** | 工期失控 | 据实排 Phase 3/4（12-16 周）；先骨架后搬功能；Coding 模式可后置 |
| 命名/抽象差异（无 SessionKey/PlanNotebook） | 集成返工 | 以核实结果为准编码，不照搬 PDF 命名 |
| 渠道 SDK 依赖（C 库） | 适配复杂 | 渠道适配器作 sidecar 容器 |
| CubeSandbox 部署（K8s+KVM） | 上线延迟 | 部署自动化；Docker 沙箱降级 |
| PostgreSQL 单点 | 核心中断 | Patroni HA + WAL 备份 MinIO |
| 现有功能回归 | —— | 无（全新 Java 实现），但需对齐 QwenPaw 行为的验收用例集 |

---

## 7. 与 Python 路径的本质区别（一句话）
**Java 路径地基最干净——沙箱/文件(无 FUSE)/权限/中间件/多副本全免费——但 QwenPaw 的全部产品功能要用 Java 从零重建，是一次完整重写，工期约为 Python 路径的 1.5 倍，且当前押注 SNAPSHOT 框架。** 适合 Java 技术栈、要长期自研平台的企业。要快、要保住现成功能选 [Python 路径](./python-path-implementation.md)。
