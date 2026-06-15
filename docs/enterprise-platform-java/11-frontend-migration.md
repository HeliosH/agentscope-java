# 11 · Frontend Migration Plan (Enterprise SaaS Console)

> **定位**：本文档是 `docs/enterprise-platform-java/` 系列的第 11 篇，补齐 Java Path 方案中缺失的前端实现方案。
>
> **前置阅读**：`01-overview.md`（产品目标）、`02-architecture.md`（系统架构）、`10-roadmap.md`（后端实施路线图）

---

## 1. Overview & Architecture Decision

### 1.1 为什么基于 agentscope Web UI 扩展

企业版前端有三个可选基础：

| 选项 | 技术栈 | 优势 | 劣势 |
|------|--------|------|------|
| **A. QwenPaw Console** | React 18 + Ant Design 5 + `@agentscope-ai/chat` | 功能最完整（25+ API、22 ToolCard、插件系统） | 绑定 Python 后端；Ant Design 与企业定制不兼容；`@agentscope-ai/chat` 封装无法对接 AG-UI 协议 |
| **B. agentscope Web UI** | React 19 + Vite 8 + shadcn/radix + AG-UI SSE | 原生对接 AG-UI 协议；现代技术栈；代码量小（~60 组件）可快速扩展 | 功能较少（无 Admin、无通道、无市场） |
| **C. 全新开发** | 自选 | 无历史包袱 | 工作量最大；无法复用任何已有组件 |

**选择 B**，理由：

1. **AG-UI 协议原生支持**：`sessionApi.streamEvents()` 已实现 fetch-based SSE async generator，与 Java 后端 `POST /agui/run` 的对接是增量式改造，而非全量重写
2. **现代技术栈**：React 19 + Tailwind CSS 4 + shadcn/radix 满足企业定制需求（Ant Design 的 "Bailian" 主题难以深度定制）
3. **代码量可控**：~60 个组件、~8 个 hook、~5 个 API 模块，理解成本和改造风险低
4. **参考 QwenPaw 设计模式**：QwenPaw 的插件注册表、ToolCard 注册、Admin 设置页结构等设计模式可在 shadcn 技术栈下重新实现，不需要移植代码

### 1.2 总体策略

**保留 UI 交互模型和 AG-UI 通信基础，替换 API 层和认证体系，新增企业模块**：

```
保留 ✅                          替换 🔄                         新增 ➕
─────────────────────────────────────────────────────────────────────────────
Chat 流式渲染                   API 层 → Java REST              SSO 登录
SSE 连接管理                    认证 → OIDC/SAML + JWT          Admin Dashboard
Tool Renderer 注册机制          租户头注入 → X-Org-Id           通道管理
Agent/Session 导航              错误处理 → 统一格式             技能市场
Credential 动态表单             响应类型适配                    安全设置
Schedule 日历视图                                              Token 用量分析
i18n 框架 (EN/ZH)                                              Coding Mode
File Attachment                                                通知与审批
音频流播放                                                      插件系统
Onboarding Tour                                                6 语言 i18n
```

### 1.3 架构原则

1. **AG-UI 优先**：所有 Chat 交互通过 AG-UI 协议（`POST /agui/run`），不走自定义 REST
2. **租户隔离前端感知**：每个 API 请求自动携带租户上下文，UI 按组织维度隔离数据
3. **插件可扩展**：菜单、路由、ToolCard、Slot 均可通过插件注册表扩展
4. **渐进增强**：每个 Phase 可独立交付，不依赖后续 Phase 的功能
5. **无障碍合规**：基于 Radix 原语构建，WCAG 2.1 AA 从 Day 1 纳入

---

## 2. Technology Stack Changes

### 2.1 基础技术栈（从 agentscope Web UI 继承）

| 组件 | 版本 | 说明 |
|------|------|------|
| React | 19.x | 并发特性，Server Components 就绪 |
| react-dom | 19.x | |
| react-router-dom | 7.x | Loader/action 模式，类型安全路由 |
| Vite | 8.x | Rolldown 打包器，更快 HMR |
| TypeScript | 5.7+ | 严格模式 |
| Tailwind CSS | 4.x | oklch 色彩空间，Utility-first |
| shadcn/ui + Radix | latest | 无障碍原语，可组合 |
| i18next + react-i18next | latest | EN/ZH 已有 |
| `@agentscope-ai/agentscope` | ^0.0.9 | 事件类型 SDK |
| sonner | latest | Toast 通知 |
| framer-motion | latest | 动画 |

### 2.2 新增依赖

| 组件 | 版本 | 用途 |
|------|------|------|
| Zustand | 5.x | 全局状态管理（auth、tenant、AG-UI stream、notification） |
| @tanstack/react-query | 5.x | 服务端状态管理、缓存、乐观更新 |
| Recharts | 2.x | Token 用量图表、Admin 统计图表 |
| @monaco-editor/react | 4.x | Coding Mode Web IDE |
| react-arborist | 3.x | 虚拟化文件树 |
| cmdk | latest | 命令面板（Ctrl+K） |
| lucide-react | latest | 图标库（替代 emoji 图标） |
| date-fns | latest | 日期处理（已有） |
| cron-parser | latest | Cron 表达式解析（已有） |

### 2.3 移除 / 替换

| 原有 | 替换为 | 原因 |
|------|--------|------|
| `localStorage` 手动管理 server_url | Zustand `auth-store` + `tenant-store` | 企业版不需要手动输入服务器地址 |
| `localStorage` 手动管理 username | JWT 解码 | 用户身份来自 SSO |
| emoji 图标 | lucide-react SVG 图标 | 专业性和一致性 |
| onborda 引导系统 | 自定义引导（基于 framer-motion） | 减少依赖，onborda 需要 Next.js shim |

---

## 3. Project Structure

```
agentscope-saas-console/
├── index.html
├── package.json
├── vite.config.ts
├── tsconfig.json
├── components.json                      # shadcn 配置
├── public/
│   └── locales/                         # i18n JSON 文件
│       ├── en/translation.json
│       ├── zh/translation.json
│       ├── ja/translation.json
│       ├── ru/translation.json
│       ├── pt-BR/translation.json
│       └── id/translation.json
└── src/
    ├── main.tsx                         # 应用入口
    ├── App.tsx                          # 根组件 + Provider 链
    ├── index.css                        # Tailwind + 主题变量
    │
    ├── lib/                             # 工具库
    │   ├── api-client.ts                # 统一 HTTP Client（auth + tenant + error）
    │   ├── agui-stream.ts               # AG-UI SSE 消费者
    │   ├── agui-types.ts                # AG-UI 事件 TypeScript 类型
    │   ├── json-patch.ts                # RFC 6902 JSON Patch 实现
    │   ├── utils.ts                     # cn(), formatDate() 等
    │   └── constants.ts                 # 特性开关、限制常量
    │
    ├── stores/                          # Zustand 状态管理
    │   ├── auth-store.ts                # JWT、用户信息、登录状态
    │   ├── tenant-store.ts              # orgId、userId、tier、quotas
    │   ├── agui-stream-store.ts         # 活跃 Run、事件状态机
    │   ├── notification-store.ts        # 收件箱、待审批
    │   └── plugin-registry.ts           # 菜单/路由/Slot 注册表
    │
    ├── hooks/                           # 自定义 Hooks
    │   ├── use-agui-run.ts              # AG-UI Run 生命周期
    │   ├── use-auth.ts                  # 认证状态 + 刷新
    │   ├── use-tenant.ts                # 租户上下文
    │   ├── use-rbac.ts                  # 角色/权限检查
    │   ├── use-notification.ts          # 实时通知
    │   └── use-agents.ts                # Agent CRUD（react-query）
    │
    ├── components/                      # 共享 UI 组件
    │   ├── ui/                          # shadcn 基础组件
    │   ├── layout/
    │   │   ├── AppShell.tsx             # 主布局壳
    │   │   ├── Sidebar.tsx              # 侧边栏（Agent 导航 + Admin 入口）
    │   │   ├── Header.tsx               # 顶栏（组织选择器、通知、用户菜单）
    │   │   └── CommandPalette.tsx        # 命令面板 (Ctrl+K)
    │   ├── chat/
    │   │   ├── ChatPanel.tsx            # 主聊天视图（AG-UI 驱动）
    │   │   ├── MessageBubble.tsx        # 单条消息
    │   │   ├── ReasoningBlock.tsx       # 可折叠推理展示
    │   │   ├── ToolCallCard.tsx         # 通用 ToolCall 包装
    │   │   ├── ToolRendererRegistry.tsx # ToolCard 分发
    │   │   ├── tool-renderers/          # 22+ 专用渲染器
    │   │   │   ├── BashRenderer.tsx
    │   │   │   ├── ReadRenderer.tsx
    │   │   │   ├── WriteRenderer.tsx
    │   │   │   ├── EditRenderer.tsx
    │   │   │   ├── GlobRenderer.tsx
    │   │   │   ├── GrepRenderer.tsx
    │   │   │   ├── BrowserRenderer.tsx
    │   │   │   ├── TaskCreateRenderer.tsx
    │   │   │   ├── ConfirmCard.tsx
    │   │   │   └── ... (13 more)
    │   │   ├── Composer.tsx             # 输入区 + 文件附件
    │   │   └── StreamingIndicator.tsx
    │   ├── coding/
    │   │   ├── CodingModePanel.tsx      # 分屏：编辑器 + Chat
    │   │   ├── FileTree.tsx             # 工作区文件浏览
    │   │   ├── CodeEditor.tsx           # Monaco 编辑器封装
    │   │   └── DiffViewer.tsx
    │   ├── admin/
    │   │   ├── AdminLayout.tsx
    │   │   ├── OrgManager.tsx
    │   │   ├── UserManager.tsx
    │   │   ├── QuotaDashboard.tsx
    │   │   ├── SandboxMonitor.tsx
    │   │   ├── AuditLogTable.tsx
    │   │   ├── ChannelManager.tsx
    │   │   └── SecuritySettings.tsx
    │   ├── marketplace/
    │   │   ├── SkillMarketplace.tsx     # 浏览、搜索、筛选
    │   │   ├── SkillDetailCard.tsx
    │   │   └── SecurityScanBadge.tsx
    │   ├── channels/
    │   │   ├── ChannelConfigForm.tsx    # 每种通道的动态表单
    │   │   ├── ChannelBindingTable.tsx
    │   │   └── QrCodeModal.tsx          # 钉钉/飞书/微信扫码
    │   └── notifications/
    │       ├── NotificationInbox.tsx
    │       └── ApprovalDialog.tsx
    │
    ├── pages/                           # 路由页面
    │   ├── LoginPage.tsx                # SSO 跳转 + 本地回退
    │   ├── SsoCallbackPage.tsx          # OIDC/SAML 回调处理
    │   ├── AgentsHubPage.tsx
    │   ├── AgentCreatePage.tsx
    │   ├── AgentLayout.tsx              # Agent 作用域布局（带 Tabs）
    │   ├── AgentChatPage.tsx
    │   ├── AgentWorkspacePage.tsx
    │   ├── AgentSkillsPage.tsx
    │   ├── AgentToolsPage.tsx
    │   ├── AgentSubagentsPage.tsx
    │   ├── AgentSessionsPage.tsx
    │   ├── AgentChannelsPage.tsx
    │   ├── AgentSettingsPage.tsx
    │   ├── CodingPage.tsx
    │   ├── SchedulePage.tsx
    │   ├── CredentialPage.tsx
    │   ├── MarketplacesPage.tsx
    │   ├── ProfilePage.tsx
    │   ├── NotificationPage.tsx
    │   └── admin/
    │       ├── AdminDashboardPage.tsx
    │       ├── AdminOrgsPage.tsx
    │       ├── AdminUsersPage.tsx
    │       ├── AdminSandboxPage.tsx
    │       ├── AdminUsagePage.tsx
    │       ├── AdminChannelsPage.tsx
    │       ├── AdminAuditPage.tsx
    │       ├── AdminSecurityPage.tsx
    │       └── AdminQuotaPage.tsx
    │
    └── api/                             # 类型化 API 模块
        ├── auth.ts                      # 登录、SSO 回调、刷新、me
        ├── agents.ts                    # Agent CRUD（组织作用域）
        ├── sessions.ts                  # Session CRUD、消息、收件箱
        ├── agui.ts                      # AG-UI Run 端点
        ├── credentials.ts               # Provider/Model 配置
        ├── schedules.ts                 # Cron CRUD、执行历史
        ├── channels.ts                  # Channel SPI 类型、配置、绑定
        ├── skills.ts                    # 工作区技能、市场安装
        ├── mcp.ts                       # MCP 客户端 CRUD、工具
        ├── security.ts                  # Tool Guard、File Guard、Scanner
        ├── usage.ts                     # Token 用量汇总、明细
        ├── workspace.ts                 # 文件、代码文件、上传/下载
        ├── sandbox.ts                   # 状态、生命周期
        ├── admin.ts                     # Dashboard 统计、配额、审计
        ├── coding.ts                    # 切换、项目 CRUD
        ├── access.ts                    # 白名单、黑名单、审批
        └── backup.ts                    # CRUD + SSE 流式备份
```

---

## 4. Core Infrastructure

### 4.1 API Client

替换现有 `api/client.ts`，增加认证头、租户头、错误统一处理：

```typescript
// src/lib/api-client.ts
interface ApiClientConfig {
  baseUrl: string;
  getAccessToken: () => string | null;
  onUnauthorized: () => void;
  onQuotaExceeded: (orgId: string) => void;
}

class ApiClient {
  private config: ApiClientConfig;

  async request<T>(method: string, path: string, opts?: RequestOptions): Promise<T> {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      ...this.buildAuthHeaders(),
      ...this.buildTenantHeaders(),
      ...opts?.headers,
    };
    // 移除 GET 请求的 Content-Type
    if (method === 'GET') delete headers['Content-Type'];

    const res = await fetch(`${this.config.baseUrl}${path}`, {
      method,
      headers,
      body: opts?.body ? JSON.stringify(opts.body) : undefined,
      signal: opts?.signal,
    });

    if (res.status === 401) {
      // 尝试静默刷新
      const refreshed = await this.tryRefresh();
      if (refreshed) return this.request(method, path, opts);
      this.config.onUnauthorized();
      throw new ApiError(401, 'Session expired');
    }
    if (res.status === 429) {
      const orgId = this.getCurrentOrgId();
      this.config.onQuotaExceeded(orgId);
      throw new ApiError(429, 'Quota exceeded');
    }
    if (!res.ok) {
      const body = await this.parseErrorBody(res);
      throw new ApiError(res.status, body.detail ?? body.message ?? body.error ?? res.statusText);
    }
    if (res.status === 204) return undefined as T;
    return res.json();
  }

  async stream(path: string, body: unknown): Promise<Response> {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      ...this.buildAuthHeaders(),
      ...this.buildTenantHeaders(),
    };
    return fetch(`${this.config.baseUrl}${path}`, {
      method: 'POST',
      headers,
      body: JSON.stringify(body),
    });
  }

  private buildAuthHeaders(): Record<string, string> {
    const token = this.config.getAccessToken();
    return token ? { Authorization: `Bearer ${token}` } : {};
  }

  private buildTenantHeaders(): Record<string, string> {
    // 从 JWT 解码或 tenant-store 读取
    const tenant = getTenantContext();
    return {
      ...(tenant.orgId ? { 'X-Org-Id': tenant.orgId } : {}),
      ...(tenant.userId ? { 'X-User-Id': tenant.userId } : {}),
    };
  }
}
```

### 4.2 路由结构

```
/login                          → LoginPage
/sso/callback                   → SsoCallbackPage
/                               → redirect to /agents

/agents                         → AgentsHubPage
/agents/new                     → AgentCreatePage
/agents/:id                     → AgentLayout (Tabs)
  /agents/:id/chat              → AgentChatPage
  /agents/:id/workspace         → AgentWorkspacePage
  /agents/:id/skills            → AgentSkillsPage
  /agents/:id/tools             → AgentToolsPage
  /agents/:id/subagents         → AgentSubagentsPage
  /agents/:id/sessions          → AgentSessionsPage
  /agents/:id/channels          → AgentChannelsPage
  /agents/:id/settings          → AgentSettingsPage
  /agents/:id/coding            → CodingPage

/channels                       → ChannelsHubPage
/channels/:channelId            → ChannelDetailPage
/marketplaces                   → MarketplacesPage
/schedule                       → SchedulePage
/credentials                    → CredentialPage
/profile                        → ProfilePage
/notifications                  → NotificationPage

/admin                          → AdminDashboardPage      [需 ADMIN/OWNER]
/admin/orgs                     → AdminOrgsPage
/admin/users                    → AdminUsersPage
/admin/sandboxes                → AdminSandboxPage
/admin/usage                    → AdminUsagePage
/admin/channels                 → AdminChannelsPage
/admin/audit                    → AdminAuditPage
/admin/security                 → AdminSecurityPage
/admin/quota                    → AdminQuotaPage
```

### 4.3 布局层次

```
AppShell
├── Sidebar（两区：Agent 导航区 + Admin 入口区）
├── Header（组织选择器 + 通知铃 + 用户菜单 + 命令面板触发器）
└── Content
    ├── AgentLayout（Agent 名称/描述 + 水平 Tabs）
    │   ├── Chat / Workspace / Skills / Tools / ...
    │   └── CodingPage（分屏：FileTree+Editor | Chat）
    └── AdminLayout（Admin 侧边栏 + 内容区）
```

---

## 5. Authentication & Multi-tenancy

### 5.1 SSO 登录流程

```
┌──────────┐     ┌──────────┐     ┌───────────┐     ┌──────────────┐
│ LoginPage │───→│  企业 IdP  │───→│ /sso/callback│───→│ POST /api/auth/sso/callback │
│           │     │ (Keycloak)│     │  ?code=... │     │ → JWT { org_id, user_id,    │
│ "SSO 登录" │     │           │     │            │     │   roles[], tier }           │
└──────────┘     └──────────┘     └───────────┘     └──────────────┘
```

**两条路径**：
1. **企业 SSO（主路径）**：点击 "Sign in with SSO" → 重定向到 IdP → 回调 `/sso/callback` → 后端验证 → 返回 JWT
2. **本地登录（回退）**：用户名 + 密码表单 → `POST /api/auth/login` → 返回 JWT（适用于小规模部署无 IdP 场景）

### 5.2 JWT 管理

- **Access Token**：存储在 Zustand `auth-store`（内存），不写入 localStorage（防 XSS）
- **Refresh Token**：存储在 HttpOnly Cookie（后端 `Set-Cookie`），前端不可读
- **静默刷新**：401 响应时调用 `POST /api/auth/refresh`（自动携带 Cookie），成功后重试原请求
- **JWT 解码**：客户端解码 payload 提取 `org_id`、`user_id`、`roles[]`、`tier` 供 `tenant-store` 使用

### 5.3 租户上下文传播

每个 API 请求自动携带：

| Header | 来源 | 说明 |
|--------|------|------|
| `Authorization: Bearer {jwt}` | auth-store | 认证令牌 |
| `X-Org-Id: {org_id}` | tenant-store / JWT | 组织隔离 |
| `X-User-Id: {user_id}` | tenant-store / JWT | 用户标识 |
| `X-Agent-Id: {agent_id}` | URL 路径或 Zustand | Agent 作用域（AG-UI 协议已原生支持） |

后端最终校验 JWT claims 与 headers 一致性，前端发送仅用于调试和明确性。

### 5.4 RBAC 前端实施

```typescript
// src/hooks/use-rbac.ts
export function useRbac() {
  const { roles } = useTenantStore();

  return {
    hasRole: (role: string) => roles.includes(role),
    hasPermission: (permission: string) => {
      // 从角色映射权限
      const rolePermissions: Record<string, string[]> = {
        OWNER: ['*'],
        ADMIN: ['agents:*', 'users:read', 'quota:*', 'audit:read', 'channels:*', 'security:*'],
        MEMBER: ['agents:read', 'chat:*', 'workspace:*', 'skills:read'],
        VIEWER: ['agents:read', 'chat:read', 'workspace:read'],
      };
      return roles.some(r => {
        const perms = rolePermissions[r] ?? [];
        return perms.includes('*') || perms.includes(permission);
      });
    },
  };
}
```

- `/admin/**` 路由守卫：需 `ADMIN` 或 `OWNER` 角色
- 组件条件渲染：如 "Create Agent" 按钮仅 `agents:create` 权限可见
- **后端是最终权威**：前端仅做 UX 优化，不替代后端权限校验

### 5.5 多组织切换

- Header 展示组织选择器下拉
- 切换组织时更新 `tenant-store.orgId`，触发所有数据重新拉取
- 侧边栏 Agent 列表、Sessions、所有 API 调用均按当前组织作用域

---

## 6. Chat & AG-UI Integration

> **本节是前端改造的技术核心**。现有前端与 Java 后端使用不同的流式通信协议，必须精确对接。

### 6.1 现有前端流式通信

```
1. GET /sessions/{sid}/stream   → SSE (text/event-stream)
   前端: sessionApi.streamEvents() — async generator yielding AgentEvent
2. POST /chat/                  → 触发 Chat Run（fire-and-forget）
   事件流通过 SSE 连接接收

事件类型 (AgentEvent):
  REPLY_START, REPLY_END, TOOL_CALL_START, TOOL_CALL_ARGS,
  TOOL_CALL_END, TOOL_CALL_RESULT, DATA_BLOCK_START/END,
  CUSTOM, ...
```

### 6.2 Java 后端 AG-UI 协议

```
1. POST /agui/run/{agentId}     → SSE (text/event-stream)
   Body: RunAgentInput {
     threadId, runId, messages[], tools[], context[], state{}, forwardedProps{}
   }

事件类型 (AguiEvent — 18 种):
  RUN_STARTED, RUN_FINISHED,
  TEXT_MESSAGE_START, TEXT_MESSAGE_CONTENT, TEXT_MESSAGE_END,
  TOOL_CALL_START, TOOL_CALL_ARGS, TOOL_CALL_END, TOOL_CALL_RESULT,
  STATE_SNAPSHOT, STATE_DELTA,
  RAW, CUSTOM,
  REASONING_START, REASONING_MESSAGE_START, REASONING_MESSAGE_CONTENT,
  REASONING_MESSAGE_END, REASONING_MESSAGE_CHUNK, REASONING_END
```

**关键差异**：
- 现有前端用 GET 请求 + 消息通过 SSE 流入 → AG-UI 用 POST 请求 + 消息作为请求体
- 现有前端是 fire-and-forget 触发 → AG-UI 是请求-流式响应
- 现有前端用 `REPLY_START/END` → AG-UI 用 `TEXT_MESSAGE_START/CONTENT/END`
- AG-UI 新增 `REASONING_*`、`STATE_*` 事件（现有前端无对应）

### 6.3 事件类型映射

| AG-UI 事件 | 前端动作 | 现有等价 |
|-------------|---------|---------|
| `RUN_STARTED` | 设置 `busy=true`，显示流式指示器 | 隐式（POST 响应开始） |
| `TEXT_MESSAGE_START` | 创建新的 Assistant 消息（带 `messageId`），标记 streaming | 隐式（首个 `token` 事件） |
| `TEXT_MESSAGE_CONTENT` | 追加 `delta` 到当前消息文本 | `token` 事件 |
| `TEXT_MESSAGE_END` | 标记消息完成 | `REPLY_END` |
| `TOOL_CALL_START` | 创建工具条目（`toolCallId`、`toolCallName`），标记 pending | `TOOL_CALL_START` |
| `TOOL_CALL_ARGS` | 追加 `delta` 到工具参数缓冲区（JSON 片段流式拼装） | `TOOL_CALL_ARGS` |
| `TOOL_CALL_END` | 标记参数完整，分发给 ToolRenderer | 无（现有前端收到完整 args） |
| `TOOL_CALL_RESULT` | 设置工具结果，标记完成 | `TOOL_CALL_RESULT` |
| `STATE_SNAPSHOT` | 替换整个客户端状态 | 无 |
| `STATE_DELTA` | 应用 JSON Patch（RFC 6902）到客户端状态 | 无 |
| `RAW` | 若含错误则展示，否则忽略 | `error` 事件 |
| `CUSTOM` | 按 `name` 路由到插件处理器 | `CUSTOM` 事件 |
| `REASONING_START` | 显示推理阶段指示器 | 无 |
| `REASONING_MESSAGE_START` | 创建可折叠推理块 | 无 |
| `REASONING_MESSAGE_CONTENT` | 追加 `delta` 到推理文本 | 无 |
| `REASONING_MESSAGE_END` | 关闭推理块 | 无 |
| `REASONING_MESSAGE_CHUNK` | 便捷事件：自动 start + content + end | 无 |
| `REASONING_END` | 关闭推理阶段 | 无 |
| `RUN_FINISHED` | 设置 `busy=false`，持久化 Session | `REPLY_END` |

### 6.4 AG-UI 流客户端实现

```typescript
// src/lib/agui-stream.ts

export type AguiEventType =
  | 'RUN_STARTED' | 'RUN_FINISHED'
  | 'TEXT_MESSAGE_START' | 'TEXT_MESSAGE_CONTENT' | 'TEXT_MESSAGE_END'
  | 'TOOL_CALL_START' | 'TOOL_CALL_ARGS' | 'TOOL_CALL_END' | 'TOOL_CALL_RESULT'
  | 'STATE_SNAPSHOT' | 'STATE_DELTA'
  | 'RAW' | 'CUSTOM'
  | 'REASONING_START' | 'REASONING_MESSAGE_START' | 'REASONING_MESSAGE_CONTENT'
  | 'REASONING_MESSAGE_END' | 'REASONING_MESSAGE_CHUNK' | 'REASONING_END';

export interface AguiEvent {
  type: AguiEventType;
  threadId: string;
  runId: string;
  [key: string]: unknown;
}

// 具体事件类型定义在 agui-types.ts 中
// 对应 Java 端 AguiEvent.java sealed interface 的 18 个 record

export interface RunAgentInput {
  threadId: string;
  runId: string;
  messages: AguiMessage[];
  tools?: AguiTool[];
  context?: AguiContext[];
  state?: Record<string, unknown>;
  forwardedProps?: Record<string, unknown>;
}

export async function* runAgent(
  agentId: string,
  input: RunAgentInput,
  signal?: AbortSignal
): AsyncGenerator<AguiEvent> {
  const res = await apiClient.stream(`/agui/run/${encodeURIComponent(agentId)}`, input);
  if (!res.ok) {
    throw new ApiError(res.status, await res.text());
  }
  const reader = res.body!.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    let boundary: number;
    while ((boundary = buffer.indexOf('\n\n')) >= 0) {
      const chunk = buffer.slice(0, boundary);
      buffer = buffer.slice(boundary + 2);

      // 跳过 SSE 注释（keep-alive 心跳）
      if (chunk.startsWith(':')) continue;

      const dataLine = chunk.split('\n')
        .find(line => line.startsWith('data:'));
      if (!dataLine) continue;

      const jsonStr = dataLine.slice(5).trim();
      if (!jsonStr) continue;

      try {
        yield JSON.parse(jsonStr) as AguiEvent;
      } catch {
        // 格式错误的事件 — 跳过
      }
    }
  }
}
```

### 6.5 React Hook: `useAguiRun`

```typescript
// src/hooks/use-agui-run.ts
export function useAguiRun(agentId: string) {
  const [messages, setMessages] = useState<Message[]>([]);
  const [busy, setBusy] = useState(false);
  const [threadId, setThreadId] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  async function send(text: string, attachments?: File[]) {
    const runId = crypto.randomUUID();
    const tid = threadId ?? crypto.randomUUID();

    // 从本地消息数组构建 AG-UI 输入
    const inputMessages = buildAguiMessages(messages, text, attachments);
    const input: RunAgentInput = {
      threadId: tid,
      runId,
      messages: inputMessages,
      forwardedProps: {
        orgId: useTenantStore.getState().orgId,
        userId: useTenantStore.getState().userId,
      },
    };

    // 乐观更新：立即显示用户消息
    setMessages(prev => [...prev, { id: runId, role: 'user', text, streaming: false }]);

    setBusy(true);
    abortRef.current = new AbortController();

    try {
      for await (const event of runAgent(agentId, input, abortRef.current.signal)) {
        applyEvent(event, setMessages, setThreadId);
      }
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') {
        // 用户主动中断，显示部分结果
      } else {
        toast.error('Chat error: ' + (err as Error).message);
      }
    } finally {
      setBusy(false);
    }
  }

  function abort() {
    abortRef.current?.abort();
  }

  return { messages, busy, threadId, send, abort };
}
```

### 6.6 事件状态机

```typescript
// src/lib/agui-stream-store.ts

// 每个 Run 追踪的状态
interface RunState {
  currentTextMessageId: string | null;
  currentToolCallId: string | null;
  currentReasoningMessageId: string | null;
  toolArgsBuffer: Map<string, string>;       // toolCallId → 累积的 JSON args 片段
  reasoningBuffer: Map<string, string>;      // messageId → 累积的推理文本
}

function applyEvent(
  event: AguiEvent,
  setMessages: React.Dispatch<React.SetStateAction<Message[]>>,
  setThreadId: (id: string) => void
) {
  switch (event.type) {
    case 'RUN_STARTED':
      // 初始化 Run 状态
      break;

    case 'TEXT_MESSAGE_START':
      setMessages(prev => [...prev, {
        id: (event as any).messageId,
        role: (event as any).role ?? 'assistant',
        text: '',
        toolCalls: [],
        reasoning: null,
        streaming: true,
      }]);
      runState.currentTextMessageId = (event as any).messageId;
      break;

    case 'TEXT_MESSAGE_CONTENT':
      setMessages(prev => prev.map(m =>
        m.id === (event as any).messageId
          ? { ...m, text: m.text + (event as any).delta }
          : m
      ));
      break;

    case 'TEXT_MESSAGE_END':
      setMessages(prev => prev.map(m =>
        m.id === (event as any).messageId
          ? { ...m, streaming: false }
          : m
      ));
      runState.currentTextMessageId = null;
      break;

    case 'TOOL_CALL_START': {
      const { toolCallId, toolCallName } = event as any;
      setMessages(prev => {
        const last = prev[prev.length - 1];
        if (last?.role === 'assistant' && last.streaming) {
          return prev.map(m => m.id === last.id ? {
            ...m,
            toolCalls: [...m.toolCalls, {
              id: toolCallId,
              name: toolCallName,
              args: '',
              argsComplete: false,
              result: null,
            }],
          } : m);
        }
        // 工具调用独立回合：创建新 assistant 消息
        return [...prev, {
          id: crypto.randomUUID(),
          role: 'assistant',
          text: '',
          toolCalls: [{
            id: toolCallId,
            name: toolCallName,
            args: '',
            argsComplete: false,
            result: null,
          }],
          reasoning: null,
          streaming: true,
        }];
      });
      runState.currentToolCallId = toolCallId;
      runState.toolArgsBuffer.set(toolCallId, '');
      break;
    }

    case 'TOOL_CALL_ARGS': {
      const { toolCallId, delta } = event as any;
      runState.toolArgsBuffer.set(
        toolCallId,
        (runState.toolArgsBuffer.get(toolCallId) ?? '') + delta
      );
      setMessages(prev => prev.map(m => ({
        ...m,
        toolCalls: m.toolCalls.map(tc =>
          tc.id === toolCallId
            ? { ...tc, args: runState.toolArgsBuffer.get(toolCallId) ?? '' }
            : tc
        ),
      })));
      break;
    }

    case 'TOOL_CALL_END':
      setMessages(prev => prev.map(m => ({
        ...m,
        toolCalls: m.toolCalls.map(tc =>
          tc.id === (event as any).toolCallId
            ? { ...tc, argsComplete: true }
            : tc
        ),
      })));
      runState.currentToolCallId = null;
      break;

    case 'TOOL_CALL_RESULT':
      setMessages(prev => prev.map(m => ({
        ...m,
        toolCalls: m.toolCalls.map(tc =>
          tc.id === (event as any).toolCallId
            ? { ...tc, result: (event as any).content }
            : tc
        ),
      })));
      break;

    case 'REASONING_MESSAGE_START':
      // 创建可折叠推理块
      break;

    case 'REASONING_MESSAGE_CONTENT':
      // 追加推理文本
      break;

    case 'REASONING_MESSAGE_END':
      // 关闭推理块
      break;

    case 'STATE_SNAPSHOT':
      // 替换整个客户端状态
      break;

    case 'STATE_DELTA':
      // 应用 JSON Patch 操作 (RFC 6902)
      break;

    case 'CUSTOM':
      // 按 name 分发给插件处理器
      // 特殊处理: REQUIRE_USER_CONFIRM → 触发 ConfirmCard
      if ((event as any).name === 'REQUIRE_USER_CONFIRM') {
        // 将待审批工具调用推入 notification-store
      }
      break;

    case 'RUN_FINISHED':
      // 标记所有流式消息为完成
      setThreadId((event as any).threadId);
      setMessages(prev => prev.map(m => ({ ...m, streaming: false })));
      break;
  }
}
```

### 6.7 ThreadId / SessionId 映射

| 场景 | 行为 |
|------|------|
| 新对话 | 前端生成 `threadId = crypto.randomUUID()`，随 `RunAgentInput` 发送，后端创建 Session 记录 |
| 恢复对话 | 前端调用 `GET /api/sessions` 列出会话，每条记录包含 `id`（后端 UUID）和 `threadId`（AG-UI 标识），用 `threadId` 发起后续 `RunAgentInput` |
| 服务端记忆 | 当 `agentscope.agui.server-side-memory=true`，后端按 `threadId` 维护对话历史，前端仅发送最新用户消息 |

### 6.8 中断处理

AG-UI WebFlux handler 支持流取消：客户端关闭 SSE 连接时，服务端 `doOnCancel` 调用 `result.agent().interrupt()`。

前端实现：
1. 每个 Run 存储 `AbortController` 引用
2. "Stop" 按钮点击时调用 `abortController.abort()`
3. 部分响应原样展示，附加 "已停止" 指示器

### 6.9 人机确认 (Human-in-the-Loop)

`PermissionEngine` 返回 `ASK` 模式时，AG-UI 流发出 `CUSTOM` 事件：

```
CUSTOM { name: "REQUIRE_USER_CONFIRM", value: { toolCallId, toolName, args } }
```

前端处理：
1. 检测 `CUSTOM` 事件 `name === "REQUIRE_USER_CONFIRM"`
2. 渲染 `ConfirmCard` 模态框：工具名称、参数、Approve/Deny 按钮
3. **推荐方案（Option B）**：调用 `POST /api/approvals/{id}/approve` 或 `/deny`，后端重新触发 Agent，前端重新打开 AG-UI 流
4. 审批记录自动进入审计日志

---

## 7. Tool Renderer Expansion

### 7.1 注册表机制

参考 QwenPaw Console 的 `BUILTIN_CARD_REGISTRY` 模式，使用 shadcn 技术栈重新实现：

```typescript
// src/components/chat/ToolRendererRegistry.tsx

interface ToolRendererProps {
  toolCallId: string;
  toolName: string;
  args: string;          // 原始 JSON 字符串
  argsComplete: boolean;
  result: string | null;
  isStreaming?: boolean;
}

interface ToolRendererEntry {
  name: string | RegExp;
  component: React.ComponentType<ToolRendererProps>;
  priority: number;      // 更高 = 更具体匹配
}

const TOOL_RENDERER_REGISTRY: ToolRendererEntry[] = [];

export function registerToolRenderer(entry: ToolRendererEntry) {
  TOOL_RENDERER_REGISTRY.push(entry);
  TOOL_RENDERER_REGISTRY.sort((a, b) => b.priority - a.priority);
}

export function resolveToolRenderer(toolName: string): React.ComponentType<ToolRendererProps> {
  const match = TOOL_RENDERER_REGISTRY.find(
    e => (typeof e.name === 'string' ? e.name === toolName : e.name.test(toolName))
  );
  return match?.component ?? DefaultRenderer;
}
```

### 7.2 渲染器清单（22+）

| # | 渲染器 | 匹配工具名 | 关键特性 |
|---|--------|-----------|---------|
| 1 | BashRenderer | `execute_shell_command`, `shell_execute`, `bash` | 语法高亮命令 + 输出，退出码徽章 |
| 2 | ReadRenderer | `read_file`, `filesystem_read` | 文件路径头，语法高亮内容 |
| 3 | WriteRenderer | `write_file`, `filesystem_write` | Diff 视图（前后对比），文件路径 |
| 4 | EditRenderer | `edit_file`, `filesystem_edit` | 统一 Diff 视图（行号） |
| 5 | GlobRenderer | `glob`, `filesystem_glob` | 文件列表 + 图标，匹配计数 |
| 6 | GrepRenderer | `grep_search`, `filesystem_grep` | 搜索结果 + 行号，高亮匹配 |
| 7 | BrowserRenderer | `browser_use`, `navigate`, `click`, `type`, `snapshot`, `scroll` | 截图预览，URL 栏，操作日志 |
| 8 | TaskCreateRenderer | `task_create` | 任务标题，状态徽章，截止日期 |
| 9 | SubAgentRenderer | `sub_agent_call`, `delegate_to_agent` | Agent 名称，嵌套对话预览 |
| 10 | MCPToolRenderer | `mcp__*` | MCP 服务器名称徽章，工具特定展示 |
| 11 | ScheduleRenderer | `schedule_task`, `cron_create` | Cron 表达式，下次执行时间 |
| 12 | SkillRenderer | `skill_*` | 技能名称徽章，执行状态 |
| 13 | MemoryRenderer | `memory_search`, `memory_store` | 记忆操作类型，内容预览 |
| 14 | CodeSearchRenderer | `code_search`, `lsp_search` | File:Line 结果，代码片段预览 |
| 15 | DownloadRenderer | `download_file` | 文件名，大小，下载按钮 |
| 16 | UploadRenderer | `upload_file` | 文件名，进度条 |
| 17 | APICallRenderer | `api_call`, `http_request` | 方法徽章，URL，状态码，响应预览 |
| 18 | DatabaseRenderer | `db_query` | SQL 语法高亮，结果表格 |
| 19 | ImageRenderer | `image_gen`, `screenshot` | 内联图片预览 |
| 20 | SendFileRenderer | `send_file` | 文件卡片，下载链接 |
| 21 | GetCurrentTimeRenderer | `get_current_time` | 格式化时间展示 |
| 22 | DefaultRenderer | `*`（兜底） | 通用可折叠 JSON 展示 |

每个渲染器是 `src/components/chat/tool-renderers/` 下的独立 `.tsx` 文件，模块加载时通过 `registerToolRenderer()` 自注册。

---

## 8. Admin Dashboard

### 8.1 页面结构

Admin 区域通过 `/admin/*` 路由访问，需 `ADMIN` 或 `OWNER` 角色。

**AdminDashboardPage** (`/admin`)：概览统计卡片 + 图表

| 卡片 | 数据源 | 图表类型 |
|------|--------|---------|
| 活跃用户（24h） | `GET /api/admin/stats/active-users` | 数字 + 趋势 |
| 活跃沙箱 | `GET /api/admin/sandboxes?status=running` | 数字 + 火花线 |
| Token 用量（7d） | `GET /api/admin/usage/summary?period=7d` | 面积图（按天） |
| 配额利用率 | `GET /api/admin/quota/status` | 仪表盘（按组织） |
| 待审批 | `GET /api/admin/access/pending` | 计数徽章 |

**AdminOrgsPage** (`/admin/orgs`)：组织 CRUD 表格 + 创建/编辑对话框

**AdminUsersPage** (`/admin/users`)：用户表 + 组织筛选、角色分配、密码重置、Tier 徽章

**AdminSandboxPage** (`/admin/sandboxes`)：沙箱池实时视图。每行：组织、用户、Agent、沙箱类型、状态、运行时间。操作：暂停/恢复/强制终止

**AdminUsagePage** (`/admin/usage`)：Token 用量分析（详见第 12 节）

**AdminChannelsPage** (`/admin/channels`)：通道管理（详见第 9 节）

**AdminAuditPage** (`/admin/audit`)：可搜索、可筛选的审计日志表格。列：时间、操作者、动作、资源、详情、组织。筛选器：组织、动作类型、日期范围

**AdminSecurityPage** (`/admin/security`)：安全设置（详见第 11 节）

**AdminQuotaPage** (`/admin/quota`)：Tier 配额配置。三种 Tier（standard/advanced/privileged）表格 + 可编辑限额。按组织覆盖

---

## 9. Channel Management

### 9.1 UI 流程

1. **ChannelsHubPage**：通道卡片网格，展示类型、状态、绑定 Agent、消息计数。"添加通道" 按钮打开类型选择对话框
2. **ChannelDetailPage**：通道配置表单。表单根据 `channel_type` 动态生成：
   - `GET /api/channels/types` 返回可用 SPI 类型
   - `GET /api/channels/{id}/schema` 返回该通道配置属性的 JSON Schema
   - 前端使用 JSON Schema 渲染动态表单（复用 Credential 管理的 `SchemaForm` 模式）
3. **QrCodeModal**：钉钉/飞书/微信通道显示二维码供扫描。二维码来自 `GET /api/channels/{id}/qr-code`
4. **BindingTable**：展示 Agent 与通道的绑定关系。可内联编辑

### 9.2 支持的通道类型（16 种）

钉钉、飞书、企业微信、Telegram、Discord、QQ、Matrix、Mattermost、MQTT、iMessage、Voice、SIP、小艺、元宝、微信、OneBot

---

## 10. Skill Marketplace

### 10.1 浏览

- **MarketplacesPage**：市场源（Git、Nacos、PostgreSQL 等）网格 + "添加市场" 对话。每张卡片：连接状态、技能数量、最后同步时间
- 市场内可搜索技能列表：名称、描述、版本、"安装" 按钮
- 全局搜索：`GET /api/skills/hub/search?q=...`

### 10.2 安装流程

1. 用户点击 "安装"
2. `POST /api/agents/{id}/skills/workspace/marketplace-install` → `{ marketplaceId, skillName }`
3. 409 Conflict → 显示 "技能已存在" 对话，可选覆盖
4. 成功 → 技能出现在 Agent 的 Workspace 技能列表

### 10.3 安全扫描

安装前展示技能扫描结果：
- `GET /api/skills/{id}/scan` → `{ status: "pending"|"approved"|"rejected", findings: [...] }`
- 已批准：绿色徽章；已拒绝：红色徽章 + 发现详情
- 管理员可覆盖："强制启用" 按钮（组织级覆盖）

---

## 11. Security Settings

**AdminSecurityPage** (`/admin/security`) 标签页界面：

| Tab | 内容 | API |
|-----|------|-----|
| Tool Guard | 每个工具的权限规则：ALLOW/DENY/ASK。工具名 vs 动作矩阵 | `GET/PUT /api/security/tool-guard` |
| File Guard | 文件操作的允许/禁止路径模式 | `GET/PUT /api/security/file-guard` |
| Skill Scanner | 自动扫描开关、严重等级阈值（自动拒绝） | `GET/PUT /api/security/skill-scanner` |
| Network | 允许的出站主机（allow-no-auth-hosts）。表格 + 添加/移除 | `GET/PUT /api/security/network` |
| Permission Modes | 每个组织/Tier 的默认权限模式 | `GET/PUT /api/security/permission-modes` |

所有设置按组织作用域。后端合并策略：平台默认 → 组织覆盖 → 用户/Session 覆盖。

---

## 12. Token Usage Analytics

**AdminUsagePage** (`/admin/usage`)：

| 视图 | 图表 | API |
|------|------|-----|
| 用量趋势 | 堆叠面积图（按模型） | `GET /api/usage/summary?groupBy=model&period=30d` |
| 按组织 | 柱状图（Top 10 组织） | `GET /api/usage/summary?groupBy=org` |
| 按用户 | 表格（排序/筛选） | `GET /api/usage/details?orgId=...` |
| 按模型 | 饼图 + 表格 | `GET /api/usage/summary?groupBy=model` |
| 按日期 | 热力图日历 | `GET /api/usage/summary?groupBy=date` |

用户版本（ProfilePage）："我的用量" 卡片展示个人 Token 消耗、配额剩余、趋势。

图表使用 Recharts，日期范围选择器 + 组织/用户/模型钻取。数据通过 `@tanstack/react-query` 拉取，5 分钟 stale time。

---

## 13. Coding Mode

### 13.1 布局

```
+---------------------------+---------------------------+
|    File Tree + Editor     |        Chat Panel         |
|                           |                           |
|  [FileTree]  [Monaco]    |  [Messages]               |
|                           |  [Composer]               |
+---------------------------+---------------------------+
```

### 13.2 组件

- **FileTree**：`react-arborist` 虚拟化树。数据来自 `GET /api/workspace/files?recursive=true`
- **CodeEditor**：Monaco Editor 封装（`@monaco-editor/react`）。读取：`GET /api/workspace/file?path=...`，保存：`PUT /api/workspace/file`
- **Chat 集成**：同一 `useAguiRun` hook。当 Agent 使用 `EditRenderer` 或 `WriteRenderer` 时，编辑器自动导航到受影响文件并高亮变更

### 13.3 后端集成

- `POST /api/coding/toggle` 启用/禁用 Coding Mode
- `GET /api/coding/projects` 列出项目
- 工作区文件监听 SSE（`GET /api/workspace/watch`）推送文件变更事件，实时更新树

### 13.4 实施说明

Coding Mode 延迟到 Phase F5 实现。先交付文件树和只读编辑器（复用现有 Workspace 组件），后续追加完整写入/编辑集成。

---

## 14. Notification & Approval System

### 14.1 通知收件箱

- **NotificationBell**（Header）：未读计数徽章
- **NotificationPage**：通知列表，类型包括：Session 消息（来自通道）、审批请求、配额警告、系统告警
- 通知拉取：轮询 `GET /api/notifications?unread=true`（30s 间隔）。未来：SSE 推送

### 14.2 审批工作流

`PermissionEngine` 返回 `ASK` 模式时：

1. AG-UI 流发出 `CUSTOM` 事件 `name: "REQUIRE_USER_CONFIRM"`
2. 前端渲染 `ConfirmCard` 模态框
3. 用户操作后调用 `POST /api/approvals/{id}/approve` 或 `/deny`
4. 后端重新触发 Agent，前端重新打开 AG-UI 流
5. 审批记录自动写入审计日志

---

## 15. Plugin System

### 15.1 注册表模式

参考 QwenPaw Console 的 `MenuRegistry`、`RouteRegistry`、`SlotRegistry` 模式：

```typescript
// src/stores/plugin-registry.ts

interface MenuEntry {
  id: string;
  parentId?: string;
  label: string | ((locale: string) => string);
  icon?: string;
  path?: string;
  order: number;
  requiredRole?: string;
  before?: string;
  after?: string;
}

interface RouteEntry {
  path: string;
  component: React.LazyExoticComponent<any>;
  parentId?: string;      // 渲染在哪个 Layout 内
  order: number;
}

interface SlotEntry {
  slotId: string;         // "agent-tab" | "chat-toolbar" | "tool-renderer" | ...
  component: React.ComponentType<any>;
  order: number;
  visible?: boolean;
}

class PluginRegistry {
  menus: MenuEntry[] = [];
  routes: RouteEntry[] = [];
  slots: Map<string, SlotEntry[]> = new Map();

  registerMenu(entry: MenuEntry): Disposable;
  registerRoute(entry: RouteEntry): Disposable;
  registerSlot(slotId: string, entry: SlotEntry): Disposable;

  getMenus(parentId?: string): MenuEntry[];  // topo-sort 排序
  getRoutes(parentId?: string): RouteEntry[];
  getSlots(slotId: string): SlotEntry[];
}
```

### 15.2 扩展点

| Slot ID | 位置 | 用途 |
|---------|------|------|
| `agent-tab` | AgentLayout 水平 Tab | 添加自定义 Tab 页 |
| `chat-toolbar` | ChatPanel 工具栏 | 添加按钮（导出、分享） |
| `tool-renderer` | ToolRendererRegistry | 添加自定义工具渲染器 |
| `admin-sidebar` | AdminLayout 侧边栏 | 添加 Admin 功能入口 |
| `header.left` / `header.right` | AppShell Header | 添加 Header 内容 |
| `sidebar.top` / `sidebar.bottom` | AppShell Sidebar | 添加侧边栏内容 |

### 15.3 拓扑排序

插件声明依赖（`before`/`after`）时，注册表在渲染前执行 Kahn 算法拓扑排序，防止渲染顺序问题。检测循环依赖并报警告。

---

## 16. i18n Expansion

### 16.1 目标：6 种语言

| 代码 | 语言 | 状态 |
|------|------|------|
| en | English | 完整覆盖 |
| zh | 简体中文 | 完整覆盖 |
| ja | 日本語 | 完整覆盖（新增） |
| ru | Русский | 完整覆盖（新增） |
| pt-BR | Português Brasileiro | 完整覆盖（新增） |
| id | Bahasa Indonesia | 完整覆盖（新增） |

### 16.2 实现

- 翻译文件：`public/locales/{lang}/translation.json`
- 语言检测：浏览器语言 → 存储偏好 → 组织默认
- `react-i18next` + `Suspense` 边界懒加载
- 所有用户可见字符串使用 `t('key')`，JSX 中无硬编码字符串
- 日期/数字格式使用 `Intl` API（按 i18next locale）

### 16.3 翻译键结构

```json
{
  "common": { "save": "Save", "cancel": "Cancel", "delete": "Delete", "confirm": "Confirm" },
  "auth": { "login": "Sign In", "sso": "Sign in with SSO", "logout": "Sign Out" },
  "chat": { "placeholder": "Type a message...", "stop": "Stop", "reasoning": "Thinking..." },
  "admin": { "dashboard": "Dashboard", "quota": "Quota Management", "audit": "Audit Logs" },
  "tools": { "bash": "Shell Command", "read": "Read File", "edit": "Edit File" },
  "marketplace": { "install": "Install", "scan": "Security Scan", "approved": "Approved" },
  "channel": { "dingtalk": "DingTalk", "feishu": "Feishu", "wecom": "WeCom" },
  "security": { "toolGuard": "Tool Guard", "fileGuard": "File Guard", "scanner": "Skill Scanner" }
}
```

---

## 17. Build & Deployment

### 17.1 Vite 配置

```typescript
// vite.config.ts
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: 'dist',
    sourcemap: false,
    rollupOptions: {
      output: {
        manualChunks: {
          vendor: ['react', 'react-dom', 'react-router-dom'],
          ui: ['@radix-ui/react-dialog', '@radix-ui/react-dropdown-menu',
               '@radix-ui/react-popover', '@radix-ui/react-tabs'],
          charts: ['recharts'],
          editor: ['@monaco-editor/react'],
        },
      },
    },
  },
  server: {
    proxy: {
      '/api':  { target: 'http://localhost:8080', changeOrigin: true },
      '/agui': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
});
```

### 17.2 Docker 镜像

```dockerfile
FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci --ignore-scripts
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
```

### 17.3 Nginx 配置要点

- SPA 回退：所有非文件路由返回 `index.html`
- API 代理：`/api/*` 和 `/agui/*` 代理到 Java 后端
- SSE 无缓冲：`/agui/*` 路径设置 `proxy_buffering off; X-Accel-Buffering: no`
- 安全头：`X-Frame-Options: DENY`、CSP、HSTS
- Gzip/Brotli 压缩静态资源

### 17.4 离线交付

- `console` Docker 镜像作为离线交付包的一部分（参考 `09-deployment-observability.md`）
- 所有静态资源烘焙到镜像内，运行时无 CDN 依赖
- i18n JSON 打包在应用内
- 不使用 Google Fonts（使用系统字体），不使用外部图标 CDN（打包 lucide SVG）

### 17.5 与后端集成

生产环境推荐 Nginx 独立容器（提供更好的缓存、压缩、SSE 处理）。也可将构建产物放入 Spring Boot 的 `src/main/resources/static/` 由后端直接服务。

---

## 18. Phased Implementation Plan

### Phase F1（3-4 周）— 脚手架 & 认证

**与后端 Phase 1 同步（多租户基础设施）**

- [ ] Fork `agentscope-main/examples/web_ui/frontend/` → `agentscope-saas-console/`
- [ ] 升级依赖：添加 Zustand、react-query、Recharts、cmdk、lucide-react
- [ ] 实现 `api-client.ts`（JWT auth + 租户头 + 401 刷新 + 429 配额处理）
- [ ] 实现 `auth-store.ts`（JWT 内存存储 + HttpOnly Cookie 刷新令牌）
- [ ] 实现 `tenant-store.ts`（orgId、userId、roles、tier、orgs[]）
- [ ] 构建 `LoginPage`（SSO 跳转 + 本地回退）
- [ ] 构建 `SsoCallbackPage`（OIDC/SAML code 交换）
- [ ] 实现 `PrivateRoute` 守卫（`GET /api/auth/me` 验证）
- [ ] 构建 `AppShell`、`Sidebar`、`Header`（组织选择器 + 通知铃 + 用户菜单）
- [ ] 迁移现有 Agent/Session/Credential/Schedule 页面（适配新 API Client）
- [ ] 设置 i18n（EN + ZH 翻译）
- **DoD**：用户可 SSO 登录，看到组织级 Agent 列表，切换组织

### Phase F2（3-4 周）— AG-UI Chat 集成

**与后端 Phase 1-2 同步（AG-UI Starter 就绪、沙箱集成）**

- [ ] 实现 `agui-types.ts`（18 种 AG-UI 事件 TypeScript 接口）
- [ ] 实现 `agui-stream.ts` SSE 消费者（`runAgent()` async generator）
- [ ] 实现 `applyEvent()` 状态机（所有事件类型）
- [ ] 构建 `useAguiRun` hook
- [ ] 重写 `ChatPanel` 使用 `useAguiRun`（替代现有 `useMessages` + `streamEvents`）
- [ ] 构建 `MessageBubble`、`ReasoningBlock`、`StreamingIndicator`
- [ ] 实现 `ToolRendererRegistry` 注册系统
- [ ] 构建前 8 个工具渲染器（Bash、Read、Write、Edit、Glob、Grep、TaskCreate、Default）
- [ ] 实现 `ConfirmCard`（人机确认，CUSTOM 事件 `REQUIRE_USER_CONFIRM`）
- [ ] 实现中断处理（AbortController）
- [ ] 接入 `Composer` 文件附件
- **DoD**：完整 AG-UI Chat — 流式文本、Tool Call 渲染、推理展示、确认对话框

### Phase F3（4-5 周）— Agent 管理 & 工作区

**与后端 Phase 3 同步（工具与 Agent 行为重建）**

- [ ] 迁移所有 Agent CRUD 页面（AgentsHub、Create、Settings）
- [ ] 迁移 Workspace 页面（FileTree、只读编辑器）
- [ ] 迁移 Skills 页面（工作区技能、从市场安装）
- [ ] 迁移 Sessions 页面（收件箱、Turns、对话记录）
- [ ] 迁移 Subagents 页面
- [ ] 迁移 Tools 页面
- [ ] 新增 6 个工具渲染器（Browser、SubAgent、MCPTool、Schedule、Skill、Memory）
- [ ] 实现动态 JSON Schema 表单（用于 Agent 配置、通道配置、凭据配置）
- [ ] 添加剩余 i18n 翻译（JA、RU、PT-BR、ID）
- **DoD**：所有 Agent 作用域页面可用（Chat、Workspace、Skills、Sessions）

### Phase F4（4-5 周）— Admin、通道、市场、安全

**与后端 Phase 4-5 同步（通道系统、Admin Dashboard、技能市场）**

- [ ] 构建 `AdminLayout`（标签侧边栏）
- [ ] 构建 `AdminDashboardPage`（统计卡片 + Recharts 图表）
- [ ] 构建 `AdminOrgsPage`（CRUD）
- [ ] 构建 `AdminUsersPage`（角色管理）
- [ ] 构建 `AdminSandboxPage`（实时沙箱监控）
- [ ] 构建 `AdminUsagePage`（Token 分析图表）
- [ ] 构建 `AdminAuditPage`（可搜索审计日志表格）
- [ ] 构建 `AdminQuotaPage`（Tier 配额配置）
- [ ] 构建 `AdminSecurityPage`（Tool Guard、File Guard、Skill Scanner、Network Tab）
- [ ] 构建 `ChannelsHubPage` 和 `ChannelDetailPage`（动态配置表单）
- [ ] 构建 `QrCodeModal`（钉钉/飞书/微信）
- [ ] 构建 `SkillMarketplace`（浏览 + 安装流程）
- [ ] 构建 `SecurityScanBadge` 组件
- [ ] 新增 8 个工具渲染器（CodeSearch、LSP、Download、Upload、APICall、Database、Image、SendFile）
- **DoD**：Admin Dashboard、通道管理、技能市场、安全设置全部可用

### Phase F5（3-4 周）— Coding Mode、通知、插件、打磨

- [ ] 构建 `CodingPage`（分屏布局：FileTree + Monaco + Chat）
- [ ] 实现文件写入/编辑（Workspace API）
- [ ] 实现工作区文件监听 SSE（实时更新文件树）
- [ ] 构建 `NotificationInbox` 和 `ApprovalDialog`
- [ ] 实现 `PluginRegistry`（MenuRegistry、RouteRegistry、SlotRegistry）
- [ ] 构建 `CommandPalette`（Ctrl+K，使用 `cmdk`）
- [ ] 企业品牌定制（通过 CSS 变量的主题定制）
- [ ] 无障碍审计（WCAG 2.1 AA）：焦点管理、ARIA 标签、键盘导航、屏幕阅读器测试
- [ ] 移动端响应式适配（所有页面）
- [ ] 性能优化：代码分割、懒加载、虚拟化列表
- [ ] 构建部署 Docker 镜像
- [ ] 集成测试套件（Playwright）
- **DoD**：完整企业功能集，可访问，高性能，可部署

### 工作量汇总

| Phase | 周数 | 同步后端 | 关键交付 |
|-------|------|---------|---------|
| F1 | 3-4 | Phase 1 | 认证 + 脚手架 |
| F2 | 3-4 | Phase 1-2 | AG-UI Chat |
| F3 | 4-5 | Phase 3 | Agent 页面 + 工作区 |
| F4 | 4-5 | Phase 4-5 | Admin + 通道 + 市场 |
| F5 | 3-4 | Phase 5 | Coding Mode + 打磨 |
| **合计** | **17-22** | **~30-40 周后端** | 完整企业版 Console |

前端可与后端 Phase 1 并行启动（使用 MSW Mock API）。每个前端 Phase 可在对应后端 Phase 完成前 1-2 周开始，以 API 契约文档为基准。

---

## 19. Risks & Mitigations

| 风险 | 影响 | 可能性 | 对策 |
|------|------|--------|------|
| **AG-UI 协议漂移**（前端与后端事件定义不一致） | Chat 流式中断 | 中 | 从 Java `AguiEvent.java` sealed interface 生成 TypeScript 类型；集成测试套件；锁定后端 commit |
| **Tool Renderer 数量（22+）**工期超估 | Phase F3/F4 延迟 | 中 | 优先实现 8 个核心渲染器（Phase F2），其余后补；DefaultRenderer 兜底所有未注册工具 |
| **SSO 集成**因企业 IdP 差异而复杂化 | 每次部署需定制 SSO 流程 | 高 | 标准 OIDC/SAML 流程；可配置 IdP 端点；本地登录回退；SSO 配置通过 Admin UI |
| **Coding Mode (Monaco)** 大文件性能差 | 编辑体验不佳 | 中 | 懒加载 Monaco；虚拟化文件树；浏览器编辑设文件大小限制 |
| **6 语言 i18n** 翻译质量 | 不专业或不准确 | 中 | 4 种新语言使用专业翻译服务；技术术语社区评审；i18n lint 检查缺失键 |
| **后端 API 未就绪**时前端已开始开发 | 开发阻塞 | 中 | 使用 MSW (Mock Service Worker) 按契约 Mock API；契约优先开发 |
| **无障碍合规**低估工作量 | 无法部署到政府/受监管组织 | 中 | 从 Phase F5 开始审计；使用 Radix 原语（内置 a11y）；每个组件 WCAG 检查清单 |
| **插件系统复杂性**导致渲染异常 | 不可预测的 UI 行为 | 低 | 拓扑排序插件加载顺序；沙箱化插件渲染（Error Boundary）；严格的 Slot 类型契约 |
| **离线交付**需无 CDN 依赖 | 构建复杂度 | 低 | 打包所有依赖；不用 Google Fonts（系统字体）；不引用外部 CDN（lucide SVG 打包） |
| **Inline-style → Tailwind 迁移**工作量低估 | CSS 回归，延迟交付 | 中 | 逐组件渐进迁移；`cn()` 工具逐步采用；迁移期间保留内联样式作为回退 |

---

## Appendix: Key Reference Files

### 改造来源（agentscope-main Web UI）

| 文件 | 说明 |
|------|------|
| `examples/web_ui/frontend/src/api/session.ts` | 现有 SSE 流式通信（`streamEvents()` async generator），AG-UI 对接的起点 |
| `examples/web_ui/frontend/src/hooks/useMessages.ts` | 现有消息处理 hook（284 行），需重写为 `useAguiRun` |
| `examples/web_ui/frontend/src/components/chat/tool-renderers/` | 现有 8 个工具渲染器，扩展基础 |
| `examples/web_ui/frontend/src/components/chat/MessageBubble.tsx` | 消息渲染组件（611 行），需增加 REASONING 事件支持 |
| `examples/web_ui/frontend/src/api/client.ts` | 现有 HTTP Client，需增加 auth + tenant 头 |

### 对接目标（agentscope-java AG-UI）

| 文件 | 说明 |
|------|------|
| `agentscope-extensions-protocol-agui/src/.../event/AguiEvent.java` | 18 种 AG-UI 事件类型定义（sealed interface + record），TypeScript 类型的 Source of Truth |
| `agentscope-extensions-protocol-agui/src/.../adapter/AguiAgentAdapter.java` | 后端事件转换逻辑，理解此映射是编写前端 `applyEvent()` 的关键 |
| `agentscope-extensions-protocol-agui/src/.../encoder/AguiEventEncoder.java` | SSE 序列化格式（`data: {json}\n\n`） |
| `agentscope-agui-spring-boot-starter/src/.../AguiProperties.java` | 配置属性（path-prefix、cors、timeout、server-side-memory 等） |
| `agentscope-agui-spring-boot-starter/src/.../webflux/AguiWebFluxHandler.java` | WebFlux 端点实现（`POST /agui/run`），中断处理逻辑 |

### 参考模式（QwenPaw Console）

| 文件 | 参考内容 |
|------|---------|
| `console/src/plugins/registry/store.ts` | 插件注册表模式（Menu/Route/Slot 三表 + topo-sort） |
| `console/src/components/Chat/ToolCards/registerBuiltinCards.ts` | ToolCard 注册模式（BUILTIN_CARD_REGISTRY） |
| `console/src/layouts/registry/builtinMenu.ts` | 内置菜单项注册模式 |
| `console/src/pages/Settings/Security/` | 安全设置页面结构（Tabbed interface） |
| `console/src/api/modules/` (25+ files) | API 模块组织方式（每领域一文件） |
