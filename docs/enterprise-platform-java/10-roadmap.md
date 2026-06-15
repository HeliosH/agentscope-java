# 10 · 实施路线图（Java）

## 1. 总体策略

新增 `agentscope-saas` 模块树，**不改 core/harness**。先用框架积木跑通"沙箱+多租户+AG-UI"骨架，再逐个**重建** QwenPaw 产品功能。

**总工期：~30-40 周**（功能对齐私有化版）。

> ⚠️ **务必清醒**：框架积木省掉了执行层/文件/权限/多副本的自研，但 QwenPaw 的产品力（17 渠道、技能系统、自演进记忆、Coding 模式、浏览器工具）是 **Java 从零重建**。这是工期主体，也是 Java 路径比 Python 路径慢的根因。

## 2. 新增模块树
```
agentscope-saas/
├── agentscope-saas-core/      # tenant/middleware/channel SPI/quota/audit/config
├── agentscope-saas-sandbox/   # CubeSandboxClient/SandboxBroker/SandboxPool
├── agentscope-saas-channels/  # dingtalk/feishu/telegram/discord/web 适配器
├── agentscope-saas-storage/   # MinIO 快照
├── agentscope-saas-tools/     # BrowserTool 等 QwenPaw 特有工具 Java 重写
├── agentscope-saas-skill/     # 技能市场（审核/扫描/启用）
├── agentscope-saas-app/       # Spring Boot 应用（auth/admin/gateway）
└── agentscope-saas-spring-boot-starters/
```

## 3. 里程碑

### Phase 1（4-6 周）— 多租户基础设施
- [ ] `TenantContext`/`TenantResolver`/`TenantContextMiddleware`
- [ ] `RateLimitMiddleware` + `UsageMeteringMiddleware`（计量，无计费）
- [ ] SaaS Spring Boot starter；PostgreSQL schema + Flyway（含 `tier_policies`，无 subscriptions）
- [ ] Spring Security + JWT + SSO（OIDC/SAML）
- [ ] 租户隔离单测（`SandboxManager.acquire` 对不同 user 返回不同沙箱）
- **DoD**：多租户上下文全链路传播；RLS + 沙箱隔离单测通过

### Phase 2（3-4 周）— 沙箱集成
- [ ] `CubeSandboxClient implements SandboxClient`（E2B 兼容 REST）
- [ ] `SandboxBroker` + DB 映射（`sandboxes` 表）
- [ ] `IsolationScope.USER` 配置；`SandboxStateStore` + Redis
- [ ] `RedisSandboxExecutionGuard`（多副本锁，框架自带）
- [ ] 集成测试：多用户并发沙箱
- **DoD**：一用户一沙箱，resume <60ms(Cube)/<2s(Docker)，多副本无冲突

### Phase 3（6-8 周）— 工具与 Agent 行为重建 ⭐
- [ ] `QwenPawAgent` 行为用 `HarnessAgent` + Middleware 重建（人设/技能加载/记忆挂载）
- [ ] shell/file/grep/glob 用 harness 工具（沙箱内）
- [ ] **新建 `BrowserTool`**（Java Playwright，沙箱内）
- [ ] 自演进记忆用 `LongTermMemory` 重建（反思/沉淀/召回）
- [ ] `PermissionEngine` 规则按 QwenPaw tool_guard 语义重配
- [ ] （评估）Coding 模式 Web IDE / LSP 是否首期纳入
- **DoD**：Java Agent 在沙箱内完成 shell/文件/浏览器/记忆全流程，行为对齐 QwenPaw

### Phase 4（6-8 周）— 渠道系统重建 ⭐
- [ ] `Channel` SPI + `ChannelManager` + `MessageRouter`
- [ ] P0：Web(AG-UI starter 现成) + Console
- [ ] P1：DingTalk / Feishu / Telegram 适配器（Java 重写）
- [ ] P2+：Discord / WeChat 按需
- [ ] Channel starter；外部身份 ↔ user 绑定；账号分片
- **DoD**：≥5 主要渠道可用，多租户路由正确

### Phase 5（4-6 周）— 存储/技能市场/控制面/生产化
- [ ] `RemoteFilesystemSpec` + `JdbcStore`/`RedisStore` + `NamespaceFactory` 多租户
- [ ] MinIO 快照；Memory/会话历史持久化 PostgreSQL
- [ ] 技能市场：扫描/审核/组织启用（Skill 仓库扩展 + 自建审核）
- [ ] 模型网关：内网 vLLM/Ollama 路由 + 密钥托管 + token 配额
- [ ] Admin（扩展 admin-starter）：`/admin/tenants`、`/admin/sandboxes`、`/admin/usage`、`/admin/channels`、`/admin/audit`
- [ ] 优雅降级 `DegradationManager` + 健康检查
- [ ] OTel(`TenantTraceMiddleware`) + Micrometer + Grafana；负载测试；离线交付(Compose + Helm)
- **DoD**：可自助使用、可观测、可离线私有化交付、跨沙箱数据持久化完整

## 4. 工作量评估（相对）

| 模块 | 复用程度 | 工作量 | 说明 |
|------|----------|--------|------|
| 沙箱执行层 | 高（框架自带） | **低** | 仅补 CubeSandboxClient |
| 文件持久化（无 FUSE） | 高（框架自带） | **低** | RemoteFilesystem 现成 |
| 多租户/中间件/权限 | 高（框架原语） | 中 | 写 SaaS 中间件 |
| 多副本分布式 | 高（框架自带锁/会话） | **低** | RedisSandboxExecutionGuard |
| **Agent 行为重建** | 中 | **高** | QwenPawAgent 行为 Java 重写 |
| **浏览器工具** | 无 | 高 | Java Playwright 新建 |
| **自演进记忆** | 中（底座） | 高 | 演进逻辑 Java 重写 |
| **17 渠道** | 无 | **高** | SPI + 逐个适配器 |
| **技能市场** | 中 | 高 | 审核/扫描/启用 |
| Coding 模式 | 无 | 高 | Java 重写或后置 |
| SSO/RBAC/Admin | 低 | 中 | Spring 生态 |
| 模型网关 | 中 | 中-高 | 新建 |

> **对比 Python 路径**：Python 把"沙箱/文件"列为中-高工作量（自建），但"渠道/技能/记忆/Coding"是**直接保留**（零重建）。Java 反之。净结果：Java 总工期约为 Python 的 1.5 倍。

## 5. 风险与对策

| 风险 | 影响 | 对策 |
|------|------|------|
| **框架 v2.0.0-SNAPSHOT + Spring Boot 4** | 生产稳定性 | 锁 commit；抽象层隔离；推动/等待 GA；建内部镜像仓 |
| **重建 QwenPaw 功能被低估** | 工期失控 | 据实排 Phase 3/4（12-16 周）；先骨架后功能；Coding 模式可后置 |
| 命名/抽象差异（无 SessionKey/PlanNotebook） | 集成返工 | 以核实结果编码，不照搬命名 |
| 渠道 SDK 依赖（C 库） | 适配复杂 | 渠道适配器作 sidecar |
| CubeSandbox 部署（K8s+KVM） | 上线延迟 | 部署自动化；Docker 沙箱降级 |
| PostgreSQL 单点 | 核心中断 | Patroni HA + WAL 备份 MinIO |
| 行为对齐 QwenPaw | 体验回归 | 建 QwenPaw 行为验收用例集，逐项比对 |

## 6. 关键设计原则
1. **复用优先**：框架已有的（沙箱/文件/权限/中间件/多副本/可观测）直接用，不重造。
2. **不改 core/harness**：只新增 `agentscope-saas`，通过 SPI/中间件扩展。
3. **多租户一等公民**：`TenantContext` 经 `RuntimeContext` + Middleware 全链路传播。
4. **后端可插拔**：沙箱/存储/渠道均 SPI，按需替换。
5. **资源治理替代计费**：计量服务于配额/公平/审计，无账单。
6. **诚实排期**：把"重建 QwenPaw 功能"作为独立、占主体的工作量。

> 与 Python 路径的完整对比与选型见 [../implementation/README.md](../implementation/README.md)。
