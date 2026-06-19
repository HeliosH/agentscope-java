# 14 · 方案合理性评审与下一阶段实施方案

> 目的：回答两个问题——① `docs/enterprise-platform-java/` 现有技术方案是否合理、哪里需修正；② 修正后，下一阶段给 Claude Code 实现用的**具体实施依据**。
> 评审基于实际代码核实（agentscope-paw 69 个 Java 类 / 10 套测试 / 16 前端页；agentscope-saas 58 个 Java 类；agentscope-harness 框架能力；QwenPaw-main 原项目对标）。

---

## 第一部分：方案合理性判断

### 1.1 总体结论：方案**基本合理**，但文档群内部有一处**根本性叙事矛盾**必须消除

整套文档的技术选型（Spring Boot + agentscope-harness 框架复用 + 沙箱 cube/docker/e2b + PG/Valkey/MinIO + 无-FUSE 两层读 + 多租户 TenantContext）**方向正确、与框架能力吻合**。但 12 份文档存在两条互相冲突的主线，会直接误导工期与人力决策：

| 叙事 | 出处 | 主张 | 核实结论 |
|------|------|------|----------|
| **A. 从零重写** | 01 / 02 / 10-roadmap / java-path-implementation | QwenPaw 产品力（渠道/技能/记忆/Coding/浏览器）**Java 从零重建，30–40 周** | ❌ **过时/误导**。写于 paw 移植完成之前 |
| **B. 移植集成** | 12-implementation-plan | `agentscope-paw` 已是 QwenPaw **90% 完整 Java 移植**，SaaS 只是"把 paw 产品层移进多租户壳"，**~15 周** | ✅ **与代码一致，应作为权威** |

**核实证据**（叙事 B 为真）：
- `agentscope-examples/agents/agentscope-paw/` 实存 69 个 Java 类 + 10 套测试 + 16 React 页 + 20+ 组件。
- 已具备：`SessionAgentManager`/`SessionStore`（会话编排）、`ClawMarketplaceRegistry`/`GitClawMarketplace`/`NacosClawMarketplace`（技能市场）、`AgentCatalog`/`AgentDraft`/`Template`（AI 草稿/目录/模板）、`WorkspaceScaffolder`+文件 API、`SubagentPanel`（子代理）、5 渠道扩展（钉钉/飞书/WeCom/GitHub/GitLab）。
- 框架（harness）已提供：`SkillCuratorMiddleware`/`WorkspaceSkillRepository`（技能自演进）、`PermissionEngine`、`McpServerRegistrar`、`SubagentsMiddleware`/`AgentSpawnTool`、`LongTermMemory`+`Mem0/ReMe/Bailian` 实现、`ShellExecuteTool`/`FilesystemTool`、`Sandbox`/`SandboxManager`。

**结论**：QwenPaw 产品面 Java 侧（paw + harness）已实现约 **85–90%**。真正缺失只有两项：**BrowserTool（Playwright，0%）** 和 **Coding 模式 LSP（编辑器 UI 只读、LSP 缺失，~40%）**。所谓"30–40 周从零重写"不成立。

### 1.2 需修正的具体问题（按严重度）

| # | 问题 | 严重度 | 修正动作 |
|---|------|--------|----------|
| **P1** | **叙事矛盾**：01/02/10/java-path 的"从零重写 30–40 周" vs 12 的"移植 ~15 周" | 高 | 在 01/02/10/java-path 顶部加注："工期与范围以 [12-implementation-plan](./12-implementation-plan.md) 为准；本文'从零重建'叙事写于 paw 移植完成前，仅作框架能力背景参考。" 不删原文（保留历史），但明确权威源。 |
| **P2** | **存储层缺 MinIO**：方案三后端（PG/Valkey/MinIO），代码仅 PG+Valkey，快照塞进 Postgres BYTEA（`PgRemoteSnapshotClient`，自述 dev 兜底） | 高 | 见 [13-storage-upgrade](./13-storage-upgrade.md) S1（MinIO 适配）。已立项。 |
| **P3** | **API 形态未对齐**：12-plan §2 决策"统一到 paw 的 agent 作用域 `/api/agents/{agentId}/...`"，但当前 SaaS 仍是扁平 `/api/sessions`、`/api/chat/stream` | 中 | 下阶段 B 收口时统一（见第三部分 T2）。这是 paw 前端能否最小改动接入的前提。 |
| **P4** | **namespace 缺 org 维度**：当前 `[agents, agentId, users, userId]`，方案 §8.4 要求 `[org, orgId, user, userId]` | 中 | 见 [13-storage-upgrade](./13-storage-upgrade.md) S3。多租户隔离加固。 |
| **P5** | **JuiceFS/FUSE 文档自相矛盾**：SAAS_ARCHITECTURE §3.2/§8 说"无 FUSE"，§3.1 图/§15.2 部署/§18 风险表又列 JuiceFS | 低 | 见 [13-storage-upgrade](./13-storage-upgrade.md) S4。已立项。 |
| **P6** | **前端双轨未收敛**：SaaS-app 自带"最小登录+聊天"前端，paw 有完整 16 页前端；12-plan B0 说"B 起切 paw 前端 + JWT auth 改造"，尚未执行 | 中 | 下阶段决策点（见第三部分 T4 与"决策需用户确认"）。 |
| **P7** | **LTM 未接线**：B3′ 计划标"可选/需外置服务"，未实现 | 低 | 维持可选；配置门控接 Mem0/ReMe，未配则降级 MEMORY.md（已通过快照存活）。 |

### 1.3 范围确认（与目标对齐）

目标=「面向企业全部用户的个人助手，功能对标 QwenPaw，用云资源运行」。据此与 12-plan 约束：

- ✅ **多用户 + 多租户隔离**：QwenPaw 原项目是**单用户单机**（`auth.py` 只允许注册一个账号、无 org/tenant）。本方案补的正是 QwenPaw 缺的这层——这是"企业全部用户"的核心增量，方向正确。
- ✅ **云资源运行**：沙箱走 cube/docker/**e2b**。本会话已修复 e2b envd 0.6.x 协议问题，**e2b 云沙箱现可用**（`saas.sandbox.type=e2b` 零代码切换），直接支撑"云资源运行"。
- ✅ **剔除 IM 渠道**：用户已确认不做钉钉/飞书/微信等 IM 集成。保持 Web 渠道为主。
- ⚠️ **浏览器 + Coding** 是 QwenPaw 标志能力，对标需补（Phase C），但非"可用闭环"前置。

---

## 第二部分：当前完成度真实快照（截至本评审）

| Phase | 状态 | 完成度 | 关键缺口 |
|-------|------|--------|----------|
| **A** 多用户助手底座 | ✅ 基本完成 | ~95% | DELETE 会话接口缺；API 形态未对齐 paw；`content` vs `content_json` |
| **B** Agent 能力装配 | 🟡 主体完成 | ~70% | LTM 未接；`AgentToolsController`/`MarketplacesController` 未移植；前端仍是最小版（未切 paw） |
| **C** 工具补全 | ❌ 未开始 | 0% | BrowserTool、MCP 接线、Coding IDE 全缺 |
| **D** 控制面+生产化 | ❌ 大部分未开始 | ~10% | 仅 `UsageMeteringMiddleware`；无 Admin/RLS/MinIO/可观测/降级 |

**已落地亮点**：JWT 登录 + 多租户 TenantContext 全链路、会话/消息持久化（V5）、技能自演进装配（B1′）、无-Docker 工作区 fs（B2′ Redis BaseStore）、PermissionEngine tool_guard + HITL 确认（B4′）、e2b 云沙箱（本会话修复）。

---

## 第三部分：下一阶段具体实施方案（Claude Code 实现依据）

> **决策原则：「做正确的事情，不做容易的事情」。** 据此**否决**了"先做能 demo 的 Phase B 产品面收口、把存储/隔离地基拖到 Phase D"的省事路线。理由见 3.1。

### 3.1 阶段选择：地基正确性优先（Foundation Correctness），而非产品面收口

**下一阶段 = 地基正确性加固，而非 Phase B 产品收口。**

#### 为什么不是"先收口 Phase B"（容易但不正确）
产品面（paw）已 85–90% 现成，收口能快速 demo——**这正是它的诱惑，也是它的陷阱**。在脆弱地基上堆产品面，只会让脆弱性随每个功能放大。三处"容易 vs 正确"的分叉，按原则都选正确：

| 分叉 | 容易的做法 | 正确的做法（采纳） | 为什么 |
|------|-----------|-------------------|--------|
| **存储持久化** | 保留 `PgRemoteSnapshotClient`（BYTEA + stop 时整包 tar），MinIO 拖到 D | **F3：MinIO 后端 + 沙箱开时热路径实时持久化** | 当前模型在两次 stop 间崩溃/OOM/驱逐即丢工作。"记住文件"是助手的**核心承诺**，丢工作是正确性失败，非打磨问题。13-storage + MountPoint 参考都指认这是最大地基缺口。 |
| **多租户 namespace** | 维持 `[agents,…,users,…]`，缺 org 维度，先做别的 | **F1：先统一 `{org}:{user}:{agent}`** | namespace 一旦写入更多数据再改＝数据迁移。趁早正确比事后正确便宜。跨租户数据是灾难级风险。 |
| **历史消息存储** | `content` 纯文本 | **content_json 结构化** | 纯文本回放丢工具调用/推理块，企业审计与回放失真。 |

#### 正确的次序逻辑
1. **地基决策有"先写先污染"特性**：namespace、存储 schema 错了就得迁移。必须在写入更多数据前定正确。
2. **企业级不可妥协项 = 数据持久性 + 租户隔离**：这两条不是功能，是底线。retrofit（事后补）成本远高于 design-in（设计入）。
3. **云资源运行（用户强调）= 云沙箱 + 持久存储的闭环**：e2b 云沙箱本会话已修复可用，但其上的工作区若无 MinIO 持久化，"云端记住文件"就是空话。地基与"云运行"目标直接绑定。
4. paw 产品面在正确地基上"收口"是低风险接线，**晚做不吃亏**；地基晚做则全盘返工。

### 3.2 任务分解（F1–F6，先地基 → 后产品收口；每个可独立交付+验证）

> namespace 规范统一为 `{org}:{user}:{agent}`，所有新增存储/路由遵循。
> 阶段一（F1–F4）= 地基正确性；阶段二（F5–F6）= 产品面收口（在正确地基上）。

#### 【地基】F1 · namespace org 维度统一（P4，0.5 周，低风险）⭐ 最先做
- `RemoteFilesystemSpec.namespaceFactory` 由 `[agents, agentId, users, userId]` 改为 `[org, orgId, user, userId, agent, agentId]`（对齐 SAAS_ARCHITECTURE §8.4）。
- 沙箱状态 key、快照路径、BaseStore namespace 全部纳入 org 维度。
- **必须最先做**：此后所有 F2–F6 写入的数据都带正确 namespace，避免迁移。
- **DoD**：alice@orgA 与 alice@orgB（同名跨租户）数据完全隔离；存储 key 含 org 段。
- 文件：`AgentConfig.java`、`SandboxConfig.java`、`SaasProperties`。

#### 【地基】F2 · API 形态对齐 agent 作用域（P3，1 周，中风险）⭐ 正确性前置
- `SaasChatController`/`SessionController`/`AgentController` 改造为 paw 形态：
  - `POST /api/agents/{agentId}/chat/stream`
  - `GET/DELETE /api/agents/{agentId}/sessions[/{id}][/messages]`
- agentId 在 service 层对 `TenantContext.orgId` 校验归属；多租户靠 TenantContext，不靠 URL。
- 旧扁平路由 `@Deprecated` 转发保留一个版本周期。
- **DoD**：curl 带 JWT 调 agent 作用域路由返回流式文本；为 F5 前端接入铺好正确契约。
- 文件：`SaasChatController.java`、`SessionController.java`、`AgentController.java`。

#### 【地基】F3 · 存储持久化根治（13-storage S1+S2，2.5–3.5 周，中-高风险）⭐ 本阶段核心
- **S1 MinIO 快照后端**：新建 `MinioRemoteSnapshotClient implements RemoteSnapshotClient`，布局 `snapshots/{org}/{user}/{agent}/{ts}.tar.gz`；`SandboxConfig.buildSnapshotSpec()` 按 `saas.storage.snapshot.backend=pg|minio|oss` 选择，Pg 降为 dev 兜底。复用框架 `OssRemoteSnapshotClient` 作参考。
- **S2 沙箱开时热路径持久化**：高频小文件（MEMORY.md/skills/memory/tools.json）经 `RemoteFilesystem` 写穿 BaseStore（沙箱开→MinIO BaseStore，关→Redis），与沙箱关路径统一；大文件/整包仍走 stop-time 快照兜底。热 KV 实时持久化 + 冷 tar 快照双层。
- **在 e2b 云沙箱上验证**（e2b 有 NATIVE_SNAPSHOT 模式，与 MinIO 互补）。
- **DoD**：e2b 沙箱内写 MEMORY.md/技能 → 进程被杀（模拟崩溃/驱逐）→ 重启沙箱后文件仍在；`backend=pg` 回归不变。
- 文件：新建 `MinioRemoteSnapshotClient.java`、`MinioConfig`、`SaasProperties.Storage`；改 `SandboxConfig.java`、`AgentConfig.java`。

#### 【地基】F4 · RLS 纵深隔离（D3，1 周，中风险）
- Flyway `V6__rls.sql`：所有 tenant 表 `ENABLE ROW LEVEL SECURITY` + `org_id` 策略；连接级设 `app.current_org`。
- 作 service 层过滤 + F1 namespace 之上的第三道防线。
- **DoD**：即便 service 层漏过滤，DB 层也拦截跨 org 读写（用 SQL 直连验证）。
- 文件：`V6__rls.sql`（pg + h2 变体；h2 不支持 RLS 则仅 pg 生效 + 文档说明）、`TenantContextMiddleware`（设 session 变量）。

#### 【产品收口】F5 · 移植 paw 控制器 + content_json + DELETE 会话（B5′/A2，1.5 周，中风险）
- 移植 `AgentToolsController`、`MarketplacesController` + `ClawMarketplaceRegistry`/`Git`/`Nacos`，加 org+user 过滤。
- `chat_messages.content` → `content_json`（结构化 AG-UI 消息块，正确回放）；`V7__chat_messages_content_json.sql`。
- 补 `DELETE /api/agents/{agentId}/sessions/{id}`（F2 路由下）。
- **DoD**：Web 内浏览工具/安装市场技能按租户隔离；历史回放含工具调用/推理；删会话正确隔离。
- 文件：`tools/AgentToolsController.java`、`marketplace/*`、`ChatPersistenceService.java`、`V7__*.sql`。

#### 【产品收口】F6 · 前端切换到 paw 前端 + JWT 多租户改造（B0，1.5–2 周，中风险）
- fork `agentscope-paw/frontend`（16 页完整 UI）替换最小前端；加 `auth-store`(JWT) + `LoginPage` + API 注入 `Authorization`/`X-Org-Id`/`X-User-Id`，指向 F2 路由。
- **正确而不镀金**：fork paw 现有栈、把多租户/鉴权层做扎实即可；**不**为美观按 11-doc 重写为 shadcn 栈（那是镀金，非正确）。11-doc 现代栈留作 Phase D 选项。
- **DoD**：登录 → agent 列表 → 聊天 → 历史 → 技能/工作区/市场/子代理页全可用，按用户隔离。
- 文件：`agentscope-saas-app/frontend/`。

#### （延后）F7 · PermissionEngine tool_guard 精配 + LTM 可选接线
- 原 T5/T6，低风险、非地基、非收口阻塞，随产品收口顺带或延至 Phase C。

### 3.3 阶段排期与依赖

```
阶段一（地基正确性，~5–6 周）
  F1 (namespace) ⭐最先 ──> F3 (存储根治) ⭐核心 ──┐
  F2 (API 形态) ⭐前置 ───────────────────────────┼──> 地基正确
  F4 (RLS) ── 依赖 F1 ────────────────────────────┘
阶段二（产品收口，在正确地基上，~3–4 周）
  F5 (控制器+content_json+DELETE) ── 依赖 F2
  F6 (paw 前端) ── 依赖 F2+F5
  F7 (权限/LTM) ── 顺带/延后
```

- **顺序**：F1 → (F2 ∥ F3 ∥ F4) → F5 → F6。F1 必须最先（防数据污染）；F3 是核心、最重，可与 F2/F4 并行推进。
- **总工期估**：地基 ~5–6 周 + 收口 ~3–4 周 ≈ **8–10 周**到"正确地基 + 完整 QwenPaw 产品面"。
- **诚实对比**：比"先 Phase B 收口（~4–5 周可 demo）"慢，但避免在脆弱地基上返工——这正是"不做容易的事"的代价与回报。

### 3.4 本阶段 DoD（阶段验收）
企业多用户各自 JWT 登录 → 在 **e2b 云沙箱**内完成 shell/文件/技能/子代理全流程；**工作区文件经 MinIO + 热路径持久化跨沙箱崩溃/驱逐存活**；跨 org/user 三层隔离（service + namespace + RLS）经 SQL 直连验证不可越权；历史回放结构化保真。

### 3.5 再下一阶段预告（Phase C/D，本阶段后细化）
- **C**：BrowserTool（Playwright，`agentscope-saas-tools` 新模块，云沙箱内 headless chromium）+ MCP/子代理接线 + Coding IDE（可写编辑器，LSP 后置）。
- **D**：Admin 控制面 + 可观测（OTel/Micrometer）+ DegradationManager + Docker Compose/Helm 离线交付 + 前端现代栈美化（11-doc，可选）。

---

## 第四部分：已落定的决策（按「做正确的事」原则，不再回抛）

1. **下一阶段范围 → 地基正确性优先（F1–F4），而非 Phase B 产品收口。** 拒绝在脆弱存储/隔离地基上先堆可 demo 的产品面。
2. **存储 → 立即根治（F3：MinIO + 热路径持久化）**，不拖到 Phase D。"记住文件"是核心承诺，当前 BYTEA+stop-tar 模型崩溃即丢工作，是正确性缺陷。
3. **namespace → 立即补 org 维度（F1，最先做）**，避免事后数据迁移。
4. **历史消息 → content_json 结构化（F5）**，保回放/审计保真，不用纯文本。
5. **前端（F6）→ fork paw 现有栈 + 扎实做多租户鉴权层**；不为美观重写现代栈（镀金 ≠ 正确）。11-doc 现代栈留作 Phase D 可选。
6. **验证基线 → e2b 云沙箱**（本会话已修复可用），契合"云资源运行"目标。

---
*本文档为评审与规划。修正动作 P1（文档加注）、F1–F6（下阶段实现）按上述落定的次序执行。存储 F3 细节见 [13-storage-upgrade.md](./13-storage-upgrade.md) S1/S2。*
