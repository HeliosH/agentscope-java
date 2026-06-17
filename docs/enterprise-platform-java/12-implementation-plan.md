# 12 · 具体实施方案（简单登录 + 完整 QwenPaw 对齐，不含 IM）

> 约束（来自需求确认）：① 不做 SSO，沿用已有的 email/password → JWT 简单登录；② **不做 IM 渠道集成**（Phase 4 的钉钉/飞书/微信/Telegram/Discord 全部跳过）；③ 模型接 **OpenAI 兼容网关**；④ 范围=**完整 QwenPaw 对齐**（浏览器工具 / 技能市场 / 自演进记忆 / Coding 模式）。

## 1. 关键判断：这是"移植集成"，不是"从零重建"

逐项核实代码后，结论与 [10-roadmap.md](./10-roadmap.md) 的"重建"叙事有重大修正：

| 资产 | 现状 | 对方案的意义 |
|------|------|--------------|
| **agentscope-paw 示例** | QwenPaw 的 **完整 Java 移植**：`SessionAgentManager`、技能市场（Git/Nacos）、`AgentCatalog`、`WorkspaceScaffolder`、AI 草稿、子代理、全套 React 前端页面（chat/sessions/skills/workspace/tools/marketplaces/subagents） | **产品层 90% 已存在**，单用户、无 auth、无沙箱、无多租户（`LocalFilesystemWithShell` 直跑宿主） |
| **SaaS app** | 多租户壳齐全：JWT 登录、`TenantContext` 全链路、限流/计量、cube/docker 沙箱（`IsolationScope.USER`）、快照持久化、dev profile | **基础设施 90% 已存在**，但 agent 是极简单例（无技能/记忆/会话持久化），前端只有登录+聊天的最小页 |
| **框架积木** | `LongTermMemory`+`LongTermMemoryTools`+`Mode`、完整技能体系（`SkillCuratorMiddleware` 自演进、`PostgresSkillRepository` 等）、`PermissionEngine`、`ShellExecuteTool`/`FilesystemTool`/`MemoryGetTool`/`MemorySearchTool`/`McpServerRegistrar`/`TaskTool` | 记忆/技能/权限/工具**全部复用**，无需自研 |
| **BrowserTool** | 全仓库无 Playwright | **唯一真正新建**的 QwenPaw 能力 |
| **Coding 模式** | paw 前端有 `WorkspaceEditor`（偏只读）；框架无 LSP | 需扩展为可写 + 沙箱文件 API；LSP 可后置 |

**因此方案主线 = 把 paw 的产品层移植进 SaaS 的多租户壳**：paw 提供"会做什么"，SaaS 提供"给谁、隔离在哪、记在哪"。paw README 自己点明：paw 故意不做多租户/沙箱/横向扩展，"如果需要——sister projects 覆盖"。本方案正是补上这一层。

## 2. API 形态对齐（前置决策）

- paw API 是 **agent 作用域**：`/api/agents/{agentId}/chat/stream`、`/api/agents/{agentId}/sessions/...`、`/api/agents/{agentId}/skills`、`/api/agents/{agentId}/workspace/...`。
- SaaS 当前是 **扁平**：`/api/chat/stream`、`/api/agents`、`/api/sessions`。
- **决策**：统一到 paw 的 agent 作用域形态（`/api/agents/{agentId}/...`），把现有 `SaasChatController`/`SessionController`/`AgentController` 改造为 agent 作用域并注入 `TenantContext`。这样 paw 前端 API 客户端可**最小改动**接入，多租户靠 `TenantContext`（org/user）在服务层 + 沙箱/存储 namespace 隔离，不靠 URL。

## 3. 分阶段实施

每个 Phase 独立可交付、可验证。建议按 A→B→C→D 顺序，A 完成即"可用多用户助手"。

---

### Phase A（1.5–2 周）— 可用多用户助手：登录 + 真实 LLM + 会话持久化

**目标**：不同用户网页登录后，能和真实 LLM 对话、用沙箱跑工具、会话历史持久化且按用户隔离。这是"完整功能"的最小可用底座。

**A1 · 接 OpenAI 兼容网关**
- `ModelConfig` 已支持 `type=gateway`（[ModelConfig.java](../../agentscope-saas/agentscope-saas-app/src/main/java/io/agentscope/saas/app/config/ModelConfig.java)），仅需配置：`SAAS_MODEL_TYPE=gateway`、`SAAS_MODEL_BASE_URL`、`SAAS_MODEL_API_KEY`、`SAAS_MODEL_NAME`。
- 新增 `application-gateway.yml` profile（H2 或 Pg + gateway + 沙箱按需），固化一套可复现的验证环境。
- DoD：`curl` 带 JWT 调 `/api/agents/{id}/chat/stream`，返回真实模型流式文本。

**A2 · 会话与消息持久化**
- 新增 Flyway `V5__chat_messages.sql`：`chat_messages` 表（`id, org_id, user_id, session_id, agent_id, role, content_json, created_at`），org/user 索引。
- `SaasChatController` 改为 agent 作用域 + 在请求开始/结束时落 `chat_sessions`（首条消息自动建会话、置 title）+ 逐条落 `chat_messages`（用户消息入库即发，助手消息在 `TEXT_MESSAGE_END`/`run_finished` 时入库）。
- `SessionController` 扩展：`GET /api/agents/{agentId}/sessions`（inbox，按 org+user 过滤，已存在但需接通写入）、`GET /api/agents/{agentId}/sessions/{id}/messages`（回放历史）、`DELETE` 会话。
- DoD：刷新浏览器/重登录后历史会话与消息可见；alice 看不到 bob 的会话（service 层 org+user 过滤）。

**A3 · 简单注册（可选）**
- 现有 seed 已有 3 个演示账号。新增 `POST /api/auth/register`（email/password/displayName → 建用户 + 默认 org 或指定 org invite）以支撑"不同用户自助登录"。
- DoD：新用户注册后可登录、获得独立沙箱与命名空间。

**A4 · 前端会话列表与历史**
- 现有 [frontend/](../../agentscope-saas/agentscope-saas-app/frontend/) 是最小登录+聊天页。本阶段在它之上加：左侧会话列表（inbox）、点击加载历史消息、新建会话。
- 决策点：本阶段**先扩最小前端**跑通端到端；Phase B 起切换到 paw 前端（见 B0）。避免一次性大改。
- DoD：浏览器内完成 登录→新建会话→多轮对话→刷新后历史仍在→切换会话。

**Phase A DoD**：3 个用户各自登录，独立会话历史，真实 LLM 对话 + 沙箱 shell 工具可用，数据按用户隔离。**这一步即满足"不同用户网页简单登录后的完整功能"的最小闭环。**

---

### Phase B（3–5 周，已据代码核实修正）— Agent 能力装配 ⭐

> **代码核实后的关键修正**（2026-06-17）：原计划的 B1「移植 `SessionAgentManager` + 每租户 agent 实例」**基本不必要**。框架已内建多租户隔离：
> - `WorkspaceSkillRepository` 通过 `Supplier<RuntimeContext>` **按请求**解析技能（非 build-time）；
> - `WorkspaceManager.workspaceFor(userId, sessionId)` 返回按请求隔离的工作区视图；
> - 沙箱 `IsolationScope.USER` 已给每用户独立沙箱；
> - SaaS 的 `TenantContext` 已经在 `RuntimeContext` 里全链路传播。
>
> 因此**单例 `HarnessAgent` + per-request `RuntimeContext` 天然支持多租户技能/工作区/沙箱**，无需每租户 agent 实例。Phase A 的会话/消息持久化已覆盖 paw `SessionStore` 的职责。Phase B 真正的工作是「显式装配能力 + API + 前端 + 可选 LTM」，而非移植 runtime。

**目标**：在现有单例 agent 上显式启用 QwenPaw 式能力（技能自演进、权限、工作区、可选记忆），并补齐 REST API 与前端。

**B1′ · 技能自演进装配（已实现首切片）**
- 框架 `HarnessAgent.builder().build()` 默认已装配 `HarnessSkillMiddleware` + `WorkspaceSkillRepository` + `PermissionEngine`（paw 即依赖这些默认值）。SaaS 只需显式开启自演进。
- [AgentConfig](../../agentscope-saas/agentscope-saas-app/src/main/java/io/agentscope/saas/app/config/AgentConfig.java)：沙箱启用时 `.enableSkillManageTool(true)` + `.enableSkillCurator(SkillCuratorConfig.defaults())`，由 `saas.agent.skills.self-evolution`（默认 true）开关控制。✅ 已落地、编译+测试通过。
- 技能树按用户隔离：`WorkspaceSkillRepository` 从每用户沙箱工作区 `<workspace>/skills/` 读取，快照持久化已就绪。
- DoD：agent 在沙箱内可 propose/promote 技能，curator 后台整合；技能按用户隔离。

**B2′ · 工作区文件系统（无 Docker 场景）**
- 沙箱开启时工作区 = 沙箱 fs（已就绪）。沙箱关闭时（gateway profile 默认）无 fs → 技能/工作区不可用。
- 方案：沙箱关闭时接线 `RemoteFilesystemSpec`（`IsolationScope.USER`，按用户 namespace）+ `distributedStore`（Redis `RedisStore`，复用现有 Valkey），无需 Docker/FUSE 即可提供按用户工作区 + 技能。
- DoD：gateway profile（无 Docker）下技能/工作区文件可用、按用户隔离。

**B3′ · LongTermMemory（可选，需外置服务）**
- 框架 LTM 为接口，实现均为外置服务：`Mem0LongTermMemory` / `ReMeLongTermMemory` / `BailianLongTermMemory`（extensions 模块）。
- 按 `saas.memory.long-term.*` 配置门控：未配置则不接线（降级为仅 `MEMORY.md` 文件记忆，已通过快照跨沙箱存活）；配置了 Mem0/ReMe 端点则 `LongTermMemoryMode.BOTH`（自动 record/retrieve + agent 可控），按 `userName=org:user` namespace。
- DoD（启用时）：跨会话记住用户偏好；agent 主动检索记忆。

**B4′ · PermissionEngine 按 tool_guard 重配**
- 框架 `PermissionEngine` + `PermissionMode`（5 种）已默认装配。按 QwenPaw `tool_guard` 语义配规则（shell/网络/写盘白名单与确认）。
- DoD：危险命令触发人机确认（AG-UI `REQUIRE_USER_CONFIRM` CUSTOM 事件）。

**B5′ · 技能/工作区 REST API + 前端**
- 移植 paw 的 `AgentSkillsController` / `AgentWorkspaceController` / `AgentToolsController` / `MarketplacesController`，加多租户过滤（org+user）。
- B0 前端切换：fork paw 前端，加 JWT auth-store + LoginPage，去掉"无登录"假设。
- DoD：Web 内管理技能、浏览/编辑工作区、安装市场技能，全程按用户隔离。

**Phase B DoD**：Java Agent 在沙箱内完成 shell/文件/技能（自演进）全流程，行为对齐 QwenPaw，全程多租户隔离；LTM 按需启用。
- 移植 paw 的 `marketplace/`（`ClawMarketplaceRegistry`、`MarketplacesController`）+ 前端 `SkillsMarketplacesPanel`，加多租户过滤。
- DoD：agent 可从市场安装技能、运行中沉淀新技能（curator 审核后提升），技能按租户隔离。

**B3 · 工作区 + 快照**
- paw 工作区是宿主目录；SaaS 已有 `SandboxFilesystemSpec` + `PgRemoteSnapshotClient` 快照。把 paw 的 `WorkspaceScaffolder` + `AgentWorkspaceController`（文件树/读写）接到**沙箱内**文件系统 + 快照。
- 工作区路径按 (org,user,agent) namespace，快照表已有（`V4__sandbox_snapshots`）。
- DoD：用户在 Web 看到自己沙箱工作区的文件树、读写文件，沙箱被驱逐后下次恢复。

**B4 · 自演进记忆**
- paw **未**用 `LongTermMemory`（已核实）。本阶段接线框架 `LongTermMemory`：选 `Mem0LongTermMemory`（外置 Mem0 服务）或框架内置实现，`LongTermMemoryMode.BOTH`（自动 record/retrieve + agent 可控）。
- 接 `MemoryFlushMiddleware` + `MemoryMaintenanceMiddleware`（反思/沉淀/召回），记忆按 `userName=org:user` namespace。
- 工作区 `MEMORY.md` 已通过快照跨沙箱存活（[SandboxConfig](../../agentscope-saas/agentscope-saas-app/src/main/java/io/agentscope/saas/app/config/SandboxConfig.java) 已实现）。
- DoD：跨会话记住用户偏好；agent 主动检索记忆；反思后沉淀新记忆。

**B5 · PermissionEngine 按 tool_guard 重配**
- 复用框架 `PermissionEngine` + `PermissionMode`（5 种）+ `PermissionRule`，按 QwenPaw `tool_guard` 语义配规则（shell/网络/写盘的白名单与确认）。
- DoD：危险命令触发人机确认（AG-UI `REQUIRE_USER_CONFIRM` CUSTOM 事件，前端已有 `ConfirmCard` 设计）。

**Phase B DoD**：Java Agent 在沙箱内完成 shell/文件/记忆/技能全流程，行为对齐 QwenPaw（对应 roadmap Phase 3 DoD），且全程多租户隔离。

---

### Phase C（3–5 周）— 工具补全：浏览器 + Coding 模式

**C1 · BrowserTool（Java Playwright）⭐ 唯一新建**
- 新建 `agentscope-saas-tools` 模块（roadmap 规划但尚不存在）。
- 用 `com.microsoft.playwright:playwright`，**在 cube 沙箱内**跑 headless chromium（沙箱模板需含 chromium；或 Playwright connect 到沙箱内 browser server）。
- 工具方法：`navigate/open/navigate/click/type/screenshot/extract_text/evaluate`，对齐 QwenPaw 浏览器能力。注册为 `@Tool` 进 agent toolkit。
- 安全：网络出站受 `PermissionEngine` 规则约束；截图/内容回传。
- DoD：agent 能打开网页、点击、提取文本、截图，全部在沙箱内执行。

**C2 · MCP / 子代理工具接线**
- 复用框架 `McpServerRegistrar` + `SubagentsMiddleware`/`AgentSpawnTool`。移植 paw 的 `SubagentPanel` + 配置。
- DoD：agent 可挂载 MCP server、派生子代理执行子任务。

**C3 · Coding 模式 Web IDE**
- 基于 paw 前端 `WorkspaceEditor` + `WorkspaceFileTree` 扩展为**可写**（接 B3 的沙箱文件 API：创建/编辑/保存/删除）。
- 文件树实时反映沙箱工作区；编辑保存触发快照。
- LSP（语言服务）：首期**后置**，先交付 Monaco 编辑器 + 语法高亮 + 保存；roadmap 已允许 Coding 模式分期。
- DoD：用户在 Web 内浏览/编辑自己沙箱工作区代码并保存，agent 可接续操作。

**Phase C DoD**：浏览器工具 + Coding IDE 可用，QwenPaw 标志性能力补齐。

---

### Phase D（3–4 周）— 控制面 + 生产化（roadmap Phase 5 子集，剔除 IM）

**D1 · Admin 控制器**：`/admin/tenants`、`/admin/sandboxes`（实时沙箱池，复用 `SandboxBroker` 数据）、`/admin/usage`、`/admin/audit`、`/admin/quota`。移植 paw 的 admin 页骨架。
**D2 · 模型网关治理**：密钥托管（Vault/配置加密）+ token 配额（接 `UsageMeteringMiddleware` 计量数据）+ 路由（多模型）。复用 `OpenAIChatModel`，加配额中间件。
**D3 · RLS 落地**（补 Phase 1 遗留）：为所有 tenant 表加 `ENABLE ROW LEVEL SECURITY` + `org_id` 策略 + Flyway `V6__rls.sql`，作为 service 层隔离的纵深防御。
**D4 · 可观测**：接线 `AgentTraceMiddleware`（OTel 1.61）+ Micrometer + `TenantTraceMiddleware`；Grafana 面板。
**D5 · 优雅降级 + 离线交付**：`DegradationManager`（沙箱不可用降级为无工具对话）+ Docker Compose（Pg/Valkey/MinIO/网关/应用）+ Helm chart。MinIO 快照后端替换 Pg 兜底（`PgRemoteSnapshotClient` → MinIO 适配，`RemoteSnapshotSpec` 可插拔）。

**Phase D DoD**：可自助使用、可观测、可离线私有化交付，跨沙箱数据持久化完整。

## 4. 模块落地结构

```
agentscope-saas/
├── agentscope-saas-core/         # 已有：tenant/middleware/quota/audit；+ 移植 paw session/catalog
├── agentscope-saas-sandbox/      # 已有：broker/quota/tracking/eviction
├── agentscope-saas-storage/      # 已有：Pg 快照；+ MinIO 适配（D5）
├── agentscope-saas-runtime/      # 新建：paw runtime 移植（SessionAgentManager/SessionStore/marketplace）【B1-B2】
├── agentscope-saas-tools/        # 新建：BrowserTool（Playwright）【C1】
└── agentscope-saas-app/          # 已有：auth/admin/gateway/controller + frontend
    └── frontend/                 # B0 起 = paw 前端 + 多租户 auth 改造
```

## 5. 复用映射（诚实清单）

| 能力 | 来源 | 工作量 |
|------|------|--------|
| 登录(JWT) | 已实现 | 极低（A3 注册可选） |
| 沙箱执行/隔离/快照 | 框架+SaaS 已实现 | 零（B1 接线） |
| shell/file/memory 工具 | 框架 `ShellExecuteTool` 等 | 零 |
| 会话管理 | paw `SessionAgentManager` 移植 | 中（多租户化） |
| 技能系统 + 自演进 | 框架 `SkillCuratorMiddleware` + paw 市场 | 中 |
| 自演进记忆 | 框架 `LongTermMemory`（paw 未用，需新接） | 中 |
| 权限(tool_guard) | 框架 `PermissionEngine` 重配 | 低-中 |
| 工作区/文件树 | paw controller + 沙箱文件系统 | 中 |
| 子代理/MCP | 框架 + paw | 低 |
| AI 草稿/模板/目录 | paw `AgentCatalog`/`TemplateRegistry` | 低（移植） |
| **浏览器工具** | **无，新建 Playwright** | **高** |
| **Coding IDE** | paw `WorkspaceEditor` 扩展为可写 | 中-高 |
| Admin/可观测/RLS/离线 | 框架 admin-starter + 新建 | 中 |
| 前端 | paw 前端 + auth 改造 | 中 |

## 6. 风险与对策

| 风险 | 对策 |
|------|------|
| paw 移植的多租户化返工（SessionStore/技能仓库/工作区路径全要加 org+user 维度） | B1 先定 namespace 规范（`{org}:{user}:{agent}`），所有存储统一走它 |
| cube 沙箱模板缺 chromium（C1） | 提前定制 cube template 含 Playwright + chromium；或浏览器在沙箱外独立 sidecar |
| LongTermMemory 依赖外置 Mem0 | 评估框架是否有内置 store；否则 Mem0 作可选组件，降级为仅 MEMORY.md |
| 前端 paw↔SaaS API 形态差异 | A4 用最小前端先验证后端，B0 再切 paw 前端，降低一次性风险 |
| 工期 | A=2 周（先交付可用闭环）；B/C/D 按 DoD 增量，每 Phase 可独立演示 |

## 7. 建议起步

**先做 Phase A**（2 周内交付可用多用户助手闭环），它是后续一切的地基，且单独就有价值。Phase A 完成后再决定 B/C/D 的优先级与排期。

---
*与 [10-roadmap.md](./10-roadmap.md) 的关系：本方案是基于代码核实后的修正版路线图——把 roadmap 的"从零重建 QwenPaw"修正为"移植 paw 产品层进 SaaS 多租户壳"，工期与风险显著降低；剔除 IM 渠道（Phase 4）；保留浏览器/Coding/技能市场/记忆全量对齐。*
