# 06 · 运行时与数据模型（Java）

## 1. Agent 运行时

### 1.1 HarnessAgent + 中间件链
`QwenPawAgent` 的行为用 `HarnessAgent` + 自定义 Middleware 重建（人设、技能加载、记忆挂载）。运行时**无状态**，状态外置到 Session/Store/Sandbox：
- **会话状态** → `RedisSession`（框架）；
- **工作区文件** → 沙箱 + `RemoteFilesystem`/MinIO（无 FUSE）；
- **沙箱** → `SandboxStateStore`（Redis）+ `SandboxBroker` DB 映射。

### 1.2 会话生命周期
```
chat(org,user,agent,msg) → Controller → RuntimeContext.builder()
  → agent.call(msg, rc)
     → TenantContext → RateLimit → UsageMetering
     → SandboxLifecycle(acquire,IsolationScope.USER) → WorkspaceContext
     → PermissionEngine → AgentTrace
     → ReActAgent.streamEvents(): reasoning + acting（工具在沙箱）
         · 模型调用 → 模型网关（计量+配额）→ 内网模型
     → Flux<AgentEvent> → AG-UI SSE 推前端
  → doFinally: persistState(RedisSession) + recordUsage
```

### 1.3 记忆（演进式，Java 重建）
框架提供 `Memory` + `LongTermMemory` 底座；QwenPaw 的"自演进/反思/沉淀"逻辑用 Java 重写，挂在中间件或 LongTermMemory 之上：
- 短期：会话上下文（RedisSession）；
- 长期：`LongTermMemory` → PostgreSQL + pgvector，按 `org_id/user_id` 分区；
- 检索注入：reasoning 前从向量库召回相关记忆。

## 2. 数据模型

### 2.1 核心表（PostgreSQL，均含 `org_id` + RLS）

```sql
CREATE TABLE orgs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name VARCHAR(255) NOT NULL, slug VARCHAR(64) UNIQUE NOT NULL,
  settings JSONB DEFAULT '{}',          -- 安全策略、可用模型、配额上限
  status VARCHAR(20) DEFAULT 'active', created_at TIMESTAMPTZ DEFAULT NOW());

CREATE TABLE users (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id UUID NOT NULL REFERENCES orgs(id),
  email VARCHAR(255) UNIQUE NOT NULL, idp_subject VARCHAR(255),
  display_name VARCHAR(255), role VARCHAR(20) DEFAULT 'member',
  tier VARCHAR(20) DEFAULT 'standard',  -- 配额等级（非订阅）
  created_at TIMESTAMPTZ DEFAULT NOW());

CREATE TABLE api_keys (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id UUID NOT NULL, user_id UUID NOT NULL REFERENCES users(id),
  key_hash VARCHAR(64) UNIQUE NOT NULL, name VARCHAR(255),
  scopes TEXT[] DEFAULT '{}', expires_at TIMESTAMPTZ, created_at TIMESTAMPTZ DEFAULT NOW());

CREATE TABLE agents (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id UUID NOT NULL, user_id UUID NOT NULL REFERENCES users(id),
  name VARCHAR(255) NOT NULL, visibility VARCHAR(16) DEFAULT 'private',  -- private/dept/org
  config JSONB NOT NULL DEFAULT '{}', model_config JSONB NOT NULL DEFAULT '{}',
  status VARCHAR(20) DEFAULT 'active', created_at TIMESTAMPTZ DEFAULT NOW(),
  UNIQUE(org_id, user_id, name));

CREATE TABLE sandboxes (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id UUID NOT NULL, user_id UUID NOT NULL, agent_id UUID NOT NULL REFERENCES agents(id),
  sandbox_type VARCHAR(32) NOT NULL,    -- cube/docker/k8s
  sandbox_state JSONB, status VARCHAR(20) DEFAULT 'creating',
  last_active_at TIMESTAMPTZ DEFAULT NOW(),
  UNIQUE(org_id, user_id, agent_id));

CREATE TABLE chat_sessions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id UUID NOT NULL, user_id UUID NOT NULL, agent_id UUID NOT NULL REFERENCES agents(id),
  title VARCHAR(500), summary TEXT, message_count INTEGER DEFAULT 0,
  source VARCHAR(16) DEFAULT 'user', created_at TIMESTAMPTZ DEFAULT NOW());

CREATE TABLE memories (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id UUID NOT NULL, user_id UUID NOT NULL, agent_id UUID NOT NULL REFERENCES agents(id),
  content TEXT NOT NULL, memory_type VARCHAR(32) DEFAULT 'fact',
  embedding vector(1536), created_at TIMESTAMPTZ DEFAULT NOW());

CREATE TABLE credentials (              -- 模型密钥，加密
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id UUID NOT NULL, type VARCHAR(64), enc_payload BYTEA, kms_key_ref VARCHAR(128));

CREATE TABLE schedules (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id UUID NOT NULL, user_id UUID NOT NULL, agent_id UUID NOT NULL,
  cron VARCHAR(64), payload JSONB, enabled BOOLEAN DEFAULT TRUE);

CREATE TABLE channel_bindings (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id UUID NOT NULL, agent_id UUID NOT NULL,
  channel_type VARCHAR(32), account_ref VARCHAR(255), secret_ref VARCHAR(128),
  access_policy JSONB);

CREATE TABLE usage_records (            -- 计量（配额/审计，非计费）
  id BIGSERIAL PRIMARY KEY,
  org_id UUID NOT NULL, user_id UUID NOT NULL,
  metric VARCHAR(64) NOT NULL,          -- llm_tokens/sandbox_seconds/storage_bytes
  value BIGINT NOT NULL, model VARCHAR(64), recorded_at TIMESTAMPTZ DEFAULT NOW())
  PARTITION BY RANGE (recorded_at);

CREATE TABLE audit_logs (
  id BIGSERIAL PRIMARY KEY,
  org_id UUID NOT NULL, actor UUID, action VARCHAR(64),
  resource VARCHAR(128), detail JSONB, ts TIMESTAMPTZ DEFAULT NOW());

CREATE TABLE skills (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id UUID,                          -- NULL = 平台内置
  name VARCHAR(128), version VARCHAR(32), source VARCHAR(16),  -- builtin/market/custom
  manifest JSONB, scan_result JSONB, status VARCHAR(16));      -- pending/approved/rejected

CREATE TABLE tier_policies (            -- 替代 subscriptions（无计费）
  tier VARCHAR(20) PRIMARY KEY,         -- standard/advanced/privileged
  max_agents INTEGER, max_sandboxes INTEGER, monthly_token_quota BIGINT,
  storage_gb INTEGER, idle_ttl_seconds INTEGER);
```

> **与 Java PDF 的差异**：PDF 有 `subscriptions`（订阅计费 + Stripe）；本方案改为 `tier_policies`（部门/角色等级，**无计费**）。

### 2.2 持久化技术栈
- ORM：Spring Data JPA 或 MyBatis（R2DBC 响应式可选）；
- 迁移：Flyway；
- RLS：PostgreSQL Row-Level Security 按会话变量 `app.org_id` 过滤 + ORM 拦截器强制注入。

### 2.3 配置分层（取代 config.json）
```
平台默认 → org.settings → 用户/部门(tier/偏好) → Agent 配置(model/技能/渠道/人设)
```
用 `@ConfigurationProperties`（平台默认）+ DB（org/user/agent 层），会话装配时合并缓存。

## 3. 存储后端
- **PostgreSQL(+pgvector)**：主存储（结构化 + 向量），RLS 隔离；
- **Valkey**：`RedisSession`（会话）、`RedisStore`（文件元数据/小文件 BaseStore）、`SandboxStateStore`、限流计数、`RedisSandboxExecutionGuard`；
- **MinIO**：工作区快照、上传、导出归档。

## 4. 多 Agent 协作
框架 `SubAgentTool` + `SubAgentConfig` + A2A 协议（client/server + Nacos 发现）。SubAgent 任务在正确的 tenant 沙箱内执行（`IsolationScope.USER`）。全部带 `org_id`，不跨组织。

## 5. 定时任务
框架 scheduler 扩展（Quartz/XXL-Job）触发，执行经 `SandboxManager` 在用户沙箱内运行。XXL-Job 集群天然多副本（无需自做 leader 选举）：
```java
@XxlJob("agentScheduledTask")
public void execute() {
    ScheduleRecord job = ...;
    RuntimeContext rc = RuntimeContext.builder()
        .userId(job.orgId() + ":" + job.userId())
        .sessionId("schedule:" + job.id()).build();
    rc.put(TenantContext.class, tenantResolver.byUser(job.orgId(), job.userId()));
    agent.call(Msg.user(job.prompt()), rc).subscribe();  // 工具自动沙箱内执行
}
```

> 模型调用与计量见 [07](./07-model-gateway.md)；安全与审计见 [08](./08-channels-skills-security.md)。
