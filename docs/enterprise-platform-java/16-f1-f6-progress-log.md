---
name: saas-foundation-f1-f3-progress
description: "Foundation phase F1-F6 progress: ALL DONE + committed. F4 RLS verified live on pg; F6 (paw frontend fork + JWT + backend gaps) committed 83afc663."
metadata: 
  node_type: memory
  type: project
  originSessionId: dc572917-7c74-4a65-b93a-5e4279a178bc
---

企业助手 SaaS「地基正确性」阶段(14-plan-review F1–F6)进度,2026-06-19。

## 已完成并提交(enterprise_platform 分支)
- **F1 namespace org 维度**:框架 RemoteFilesystemSpec 加 `namespaceFactory()` 扩展点;SaaS AgentConfig 注入 `tenantNamespace(rc)` → `[org, orgId, user, userId]`,从 RuntimeContext 取 TenantContext。物理按租户分区工作区存储。commit d48e1e1a
- **F2 agent 作用域 API**:`/api/agents/{agentId}/chat/stream`、`GET/DELETE /api/agents/{agentId}/sessions[/{id}][/messages]`;加 DELETE 会话(级联 deleteBySessionId);旧扁平路由 `/api/chat/stream`、`/api/sessions*` 保留 @Deprecated 转发(到 F6 前端切换后删)。commit d48e1e1a
- **F3-S1 MinIO 快照后端**:`MinioRemoteSnapshotClient implements RemoteSnapshotClient`(object key `<keyPrefix>/<snapshotId>.tar.gz`,bucket 首用创建,exists 对 NoSuchBucket 容错)+ `MinioSnapshotClientFactory`(隔离 io.minio 出 app 层)。`SandboxConfig` 按 `saas.sandbox.snapshot.backend=pg|minio` 切换,pg 为 dev 默认。application.yml 接 `SAAS_SANDBOX_MINIO_*`。commit 4280ab32

## MinIO 验证环境(用户已拉 docker 镜像)
- 容器:`docker run -d --name saas-minio -p 9000:9000 -p 9001:9001 -e MINIO_ROOT_USER=minioadmin -e MINIO_ROOT_PASSWORD=minioadmin minio/minio server /data --console-address ":9001"`
- 探针 `MinioSnapshotProbe`(storage 模块 test,手动跑):exists=false→upload→exists=true→download 匹配,RESULT=OK ✅
- 跑法:`MINIO_BUCKET=agentscope-saas mvn -pl agentscope-saas/agentscope-saas-storage exec:java -Dexec.mainClass=io.agentscope.saas.storage.MinioSnapshotProbe -Dexec.classpathScope=test`

## 构建/测试注意(沿用 [[saas-build-invocation]])
- `mvn -o` 离线;spotless 硬 gate(改完先 `spotless:apply`)。
- agentscope-core 的 `AnthropicToolsHelperTest` 在离线下 NoClassDefFoundError(MessageCreateParams)——**预存环境问题**,非本阶段引入。验证 saas-app 改动:`mvn -o -pl agentscope-harness install -DskipTests`(javadoc 对未 import 的 `{@link RuntimeContext}` 报错,需全限定 `io.agentscope.core.agent.RuntimeContext`),再 `mvn -o -pl agentscope-saas/agentscope-saas-app test`(3 测试过)。
- exec:java 需联网拉插件(3.6.3);`-o` 下插件缺失时去掉 `-o`。

## 下一步:F3-S2 热路径实时持久化
## F3-S2 热路径实时持久化:待框架支持,暂缓
当前沙箱开时工作区只在 stop 时整包 tar 上传,两次 stop 间崩溃/驱逐丢工作。

**可行性核实(2026-06-19)**:HarnessAgent builder 强制 `sandboxFilesystemSpec`/`remoteFilesystemSpec`/`localFilesystemSpec` **三选一**(HarnessAgent.java:1781-1788);`abstractFilesystem()` escape hatch 与所有 spec 互斥且会绕过沙箱生命周期装配。`capturedSandboxFs`(SandboxBackedFilesystem 具体类型)被 subagent 装配等处以具体类型引用,无法装饰。**结论:S2 需框架侧先提供"sandbox + remote 叠加"能力**(如 SandboxBackedFilesystem 内置 remote 投影,或允许 sandbox spec 叠加 RemoteFilesystemSpec),属框架级特性开发,不应在 SaaS 侧硬塞。

按"做正确的事=不引入未经验证的高风险改动",S2 暂缓,记录为框架依赖项。S1(MinIO 快照)已让 stop 时持久化从 Postgres BYTEA 升级到对象存储(正确且已验证);MEMORY.md 等在 stop 时进 tar 快照,崩溃窗口仅限两次 stop 间。转而推进 F4(RLS)。

## 其余待办(14-plan F4–F6)
- F4 RLS:**已激活并真正验证生效(commit 8102f233 V9 修空 GUC bug + commit 08afd092 激活 + commit dc094a15 修三个传播 bug)**。V6 对 8 个 tenant 表 + V8 marketplaces 共 9 个表 ENABLE+FORCE RLS;V9 NULLIF 兜底空串/NULL→0 行;V10 给非 superuser `app` 角色 DML 授权 + default privileges。**激活架构**:两个 DataSource + 两个 DB 角色。primary pool 连非 superuser `app` 角色(RLS-wrapped TenantAwareDataSourceConfig,每次 getConnection 按 TenantContextHolder SET app.current_org);admin/bypass pool 连 superuser `agentscope`(AdminDataSourceConfig,@FlywayDataSource,Flyway 也走它跑迁移/CREATE POLICY/ALTER DEFAULT PRIVILEGES)。**Bootstrap 问题解法**:AuthController login/register 在 tenant 上下文建立前运行,无 GUC→RLS deny all→login 永失败。新增 AuthBootstrapRepository(JdbcTemplate 走 admin DS),只暴露 3 个 bootstrap 查询(findUserByEmail/findOrgBySlug/saveUser),显式窄 bypass——**拒绝 routing-DataSource 捷径**(空 holder 在已认证路径会静默绕 RLS=泄漏),安全默认是 deny,bypass 必须在调用点 opt-in。AuthController 改注入 AuthBootstrapRepository。provisioning/postgres-app-role.sql(非 Flyway,首次启动前跑,用 psql `\gexec` + `:var` 在 `$$` 外做幂等 CREATE ROLE)建 `app` 角色并 GRANT CONNECT。TenantAwareDataSourceConfig.applyTenantGuc 加 DB-product guard:非 PostgreSQL(如 H2)跳过 SET。**实体 JSON 映射修正**:ChatMessageEntity.content_json + MarketplaceEntity.properties 从 `columnDefinition="TEXT"` 改 `@JdbcTypeCode(SqlTypes.JSON)`,H2 V7/V8 列 TEXT→JSON。application.yml primary user 默认→`app`;saas.datasource.admin.* 指向 superuser;H2 三 profile(local/dev/gateway)都加 saas.datasource.admin.*→H2 sa URL。**⚠️ 假绿教训**:08afd092 当时的"register→login→/me 验证通过"是**假绿**——SELECT 返回 `[]` 既符合"正确隔离"也符合"GUC 空导致 deny all",无法区分。真正写操作(POST /api/agents 创建 agent)在 08afd092 下会 500(RLS 拒 INSERT)。**真正的端到端验证在 dc094a15 才完成**(register→login→create agent INSERT 过 RLS→stub chat run→ModelCallEndEvent→@Async UsageService.record→usage_records INSERT 过 RLS,row 对 `app` 角色仅当 GUC 匹配时可见)。
  - **dc094a15 修的三个传播 bug(全部在 RLS 真正生效前是潜伏的)**:① **请求路径 Reactor 跨调度器**:web filter 在 parallel-N 线程(security context 解析处)设 ThreadLocal,但 `subscribeOn(boundedElastic)` 跨调度器后 ThreadLocal 丢失,onScheduleHook 在提交线程捕获到 null→GUC 空→所有认证 tenant 表写被拒。**修法**:删 `TenantRlsSchedulerHook`(onScheduleHook),改 `TenantContextPropagator`(`Hooks.onEachOperator` + `Operators.lift`),filter 用 `contextWrite` 把 orgId 写进 Reactor Context,propagator 在每个 operator 边界把 Context 的 orgId 同步到 ThreadLocal。Reactor Context 是唯一能穿越所有调度器跳转的传播通道。② **@Async 路径**:Spring TaskExecutor 是独立调度器,Reactor Context 不流入,`UsageService.record`(@Async)在异步 worker 上 GUC 空→INSERT 被 RLS 拒→被方法 try/catch 静默吞。**修法**:`TenantAsyncConfig` 的 `TaskDecorator` bean(捕获提交线程 orgId,worker 上重设),Spring Boot 自动应用到 async executor。3 个单测覆盖。③ **TenantContext 取回**:`HarnessAgent.ensureSessionDefaults`(HarnessAgent.java:803)用 `putAll(ctx.getExtra())` 重建 RuntimeContext——**只拷贝 string extras,丢 typed singletons**。中间件读 `ctx.get(TenantContext.class)`(typed)永远得 null,所以 RateLimit/SandboxQuota/SandboxTracking/UsageMetering **从没看到过 tenant**(预存潜伏 bug,非 F4 引入)。**修法**:`TenantContext.from(ctx)` 先试 typed 再回退 `ATTR_KEY` string extra(唯一能活过 harness 重建的槽位),所有调用点改用它。controller 仍同时 put typed + string key(后者是生存关键)。
  - **关键 gotcha**:① 自定义 GUC 的 `SET ...= ''`/`RESET` 都得到空串(非 NULL),`''::uuid` 抛错→必须 V9 `NULLIF(...,'')` 在策略层兜底;② psql `:var` 替换**不**在 `DO $$...$$` 内生效,用 `\gexec` 把 CREATE 语句放 `$$` 外;③ Spring Boot 4 的 `@FlywayDataSource` 在 `org.springframework.boot.flyway.autoconfigure` 包(非老 `.autoconfigure`);`AutoConfigureWebTestClient` 在 Boot 4 已删,WebTestClient 测试改用 `WebTestClient.bindToServer().baseUrl("http://localhost:"+port)` 手动构造 + `@LocalServerPort`;④ Hibernate 7 `validate` 对 jsonb 列要求 `@JdbcTypeCode(SqlTypes.JSON)`,纯 `columnDefinition="TEXT"` 会类型不匹配;⑤ `spring-boot:run` 的 `${ENV:default}` 解析**环境变量**,`-D` 设的是 system property;⑥ **验证 RLS 必须用非 superuser 角色做写操作**(superuser 绕 RLS 给假绿;SELECT 返 `[]` 也是假绿——必须用 INSERT/UPDATE 证明 GUC 真的设上了);⑦ **本地 e2e 跑 pg 时必须同时设 `SAAS_DB_ADMIN_URL` 指向 e2e 库**,否则 admin DS(默认 agentscope_saas 库)与 primary(e2e 库)分库,Flyway 在旧库重复 seed 报 tier_policies_pkey duplicate;⑧ Reactor Context 传播是 webflux 多租户的正确范式,ThreadLocal + onScheduleHook 在 parallel-N→boundedElastic 拓扑下会丢值。
- F5 **已完成并提交**(commit 00dd3d8a content_json + ebbe8e88 AgentTools/Marketplaces/JacksonConfig)。① content_json 结构化历史(ChatMessageEntity content→contentJson `@JdbcTypeCode(SqlTypes.JSON)`、ChatPersistenceService saveAssistantMessage(List<ContentBlock>) 用 ObjectMapper 序列化、SaasChatController AssistantContentAccumulator 捕获 AgentResultEvent.getResult().getContent()、SessionController.MessageView contentJson、V7 pg/h2 迁移)。② AgentToolsController 走 `agent.workspaceFor(userId,null).getFilesystem()` 多租户。③ MarketplacesController DB 化(MarketplaceEntity+V8;MarketplaceRegistry 按 (orgId,id) 缓存 lazy build+evict;凭证不回显)。④ JacksonConfig shared ObjectMapper bean。⑤ catalog/mcp-servers.json。pom 加 git-repository + nacos-skill + nacos-maintainer-client 依赖。
  - **关键教训**:SaasAppContextLoadsTest(@SpringBootTest + @ActiveProfiles("local") H2)是黄金验证,本轮一次性抓出 3 个真 bug:H2 V7 `QUOTE()` 不存在(改 JSON_OBJECT)、H2 V8 `TIMESTAMPTZ` 不支持(改 `TIMESTAMP WITH TIME ZONE`+`CURRENT_TIMESTAMP`)、无 ObjectMapper bean(补 JacksonConfig)。**凡改迁移/加 bean 必跑此测试**。V8 h2 必须用 `TIMESTAMP WITH TIME ZONE`+`CURRENT_TIMESTAMP`,绝不能用 `TIMESTAMPTZ`/`NOW()`。
  - **pg RLS 验证教训**:RLS 策略里的 `::uuid` cast 对空串零容忍——自定义 GUC 的 SET 空串/RESET 都≠ unset(都得到 ''),必须用 `NULLIF(...,'')` 在策略层兜底。验证 RLS 必须用**非 superuser 角色**(superuser 绕过 RLS 会给出假绿)。
- **F6 fork paw 前端 + JWT 多租户:已完成并提交(commit 83afc663)**。三层一次提交:Layer A 后端补 ~10 个端点 + V11 迁移(agents/chat_sessions/chat_messages 加列;AgentController 重塑为 paw AgentDefinition + GET/PUT/DELETE/POST /draft;SessionController 加 inbox/turns/reset/read + sessionKey==sessionId;SaasChatController GET /chat/session;AgentSkillsController marketplace-install;新 SubagentController+WorkspaceResolver+AgentDraftService+TemplateRegistry/TemplatesController+bundled templates/ 资源树;WebConfig SPA catch-all)。Layer B 前端 fork paw TS/React18/vite(channels 删除)+ JWT 认证改造(auth.ts、api/http.ts fetch override 对 /api/** 注 Bearer+401→/login+SSE 透传、LoginPage、ProtectedRoute、AppShell 用户菜单),build 进 static/。Layer C 验证+修真 bug:**TransactionRequiredException**——Spring Data JPA 派生 deleteBySessionId 在 boundedElastic 的 Mono 里无 @Transactional 会抛;ChatPersistenceService.resetSession/deleteSession/deleteAgentCascade 改 @Transactional 从 Mono 块内调用(H2 local profile 实测 reset 200/session delete 204/agent cascade delete 204)。加 3 个集成测试(TemplatesControllerTest/AgentControllerShapeTest/SubagentControllerTest,各 register→token 模式)。**黄金 gate 17/17 绿**(`mvn -o -pl agentscope-saas/agentscope-saas-app test`,从 repo 根跑)。
  - **F6 关键 gotcha(非显然,值得记)**:① **shell cwd 跨命令持久**——`cd agentscope-saas/agentscope-saas-app && grep ...` 会把持久 cwd 带进子模块,之后 `mvn -o -pl agentscope-saas/agentscope-saas-app ...` 报 "Could not find the selected project in the reactor"。**必须从 repo 根 `/Users/family/Documents/workspace/agentscope-java-main` 跑 maven**(绝对路径或 cd 回根)。② **javadoc 块注释里别写 `*/`**——`{@code classpath*:templates/*/template.json}` 里的 `*/` 提前闭合块注释,google-java-format/spotless 报 "需要 class、interface、enum 或 record"。改写措辞避开 `*/`。③ 测试取 token:register 响应用 `getResponseBodyContent()`(byte[])再 substring 抽 `"token":"..."`,**别用** `getResponseBody().toString()`(byte[] 给 `[B@...`)。TemplateRegistry.list() 返回 **LinkedHashMap**(插入序=classpath 扫描序,环境敏感),测试用 `$[?(@.id=='X')]` 过滤断言,别用 `$[0]` 索引。
  - **F6 Layer C e2e 验证(2026-06-20 完成,commit fcb3d265 附带)**:docker 全在(saas-pg 33h、saas-minio、新拉 saas-redis redis:latest)。`agentscope_saas_e2e` DB + `app` 角色都还在(记忆说"容器不在"是错的)。drop+重建 e2e DB 走真 bootstrap(Flyway V1-V11 干净 apply)。**e2e 脚本 `agentscope-saas-app/scripts/f6-e2e.sh`**:以非 superuser `app` 角色连 pg(real RLS)+ 真 JWT(`SAAS_SECURITY_DEV_ENABLED` **不设**)+ stub model + sandbox off + Redis on。Alice register(demo org `...000001`),Bob 用 SQL 植入 orgB `...000002`(复用 Alice bcrypt hash,bob 密码同 alice)。27/27 全过:golden path(create/get/chat-stream/chat-session/inbox/turns/templates/subagent)+ 跨 org 隔离(Bob GET/DELETE Alice 的 agent=404、list 互不可见、turns 404)+ **DB 级 RLS 直证**(`PGOPTIONS="-c app.current_org=<bogus>"` 连 `app` 角色 → 0 行;superuser `agentscope` BYPASSRLS → 全行,证 0 是 RLS 非空表)+ 三个 tx 修复路径(reset 200 / session delete 204 / agent cascade delete 204)。
  - **F6 e2e 踩的坑(非显然)**:① **dev bypass 会让 jwt=null → NPE 500**:`SAAS_SECURITY_DEV_ENABLED=true` 激活 `DevSecurityConfig`(anyExchange permitAll,不验 JWT),`AgentController`/`SessionController` 的 `@AuthenticationPrincipal Jwt` 为 null → `orgId(jwt)` NPE 500。**测真 RLS 必须不设 dev flag**(走真 `SecurityConfig`,JWT 解码,principal 非 null)。DevSecurityConfig 注释只说"chat endpoint tolerates null jwt"——F6 新 controller 没 null guard,这是潜在健壮性缺口(prod 关 dev 无事,dev 开着会 500)。② **subagent/workspace/skill 端点要 Redis**:AgentConfig sandbox-off 分支 `workspaceStoreProvider.getIfAvailable()` 拿 Redis BaseStore;Redis 没起 → 无 filesystem → subagent 500 `JedisConnectionException localhost:6379`。local profile 用内存 store 不需要;default(pg)profile **必须起 Redis**。③ **register 总是进 demo org**:`AuthController.doRegister` 把所有自注册用户塞进 seed `demo` org(`DEFAULT_ORG_SLUG="demo"`),所以**自注册测不了跨租户**——要跨 org 得用 SQL 植第二个 org+user。④ psql `SET` 会把 "SET" 回显到 stdout 污染 count;用 `PGOPTIONS="-c app.current_org=..."` 在连接级设 GUC,绕开 `SET` 回显。
  - **遗留扁平路由已删(commit fcb3d265)**:`POST /api/chat/stream`(streamLegacy+LegacyChatRequest)、`GET /api/sessions`(listLegacy)删;`ChatSessionRepository.findByOrgIdAndUserIdOrderByUpdatedAtDesc` 删(仅 legacy 用);`SessionView`/`toLegacyView` 保留(agent-scoped list 还用)。smoke-test.sh 改打 agent-scoped 路由。SaasApp javadoc 路由引用更新。黄金 gate 仍 17/17。

## Phase C2 动态 per-user MCP:已完成并提交(2026-06-21,commit 09e5186a,已推远程)
多租户 SaaS 单例 HarnessAgent 服务多用户,但 build-time tools.json 只加载受管工作区默认 MCP——每个租户看到相同工具、org admin 无法下发 org base。C2 加 per-reasoning-step 协调,让每个用户看到自己的有效集(org base ⊕ 用户工作区 tools.json),无需重启。
- **Harness 层(与 saas-core 解耦,因 saas-core 依赖 harness 而非反向)**:McpClientRegistry(userId,serverName)→live McpClientWrapper 缓存(getOrCreate 构建并缓存、remove/closeAll 拆除,跨 turn 保活免重连);ToolsConfigMerger 纯合并(user 同名覆盖 org、org 未被覆盖的继承、builtin allow/deny user-wins+org 兜底);ToolsConfigLoader.parse 抽出 load 内联的 parse+env-substitution 供运行时复用;McpServerRegistrar.buildWrapper 暴露(阻塞式)wrapper 构建供 registry 不走 build-time register 路径;DynamicMcpMiddleware.onReasoning 读用户 tools.json(经 per-user AbstractFilesystem namespace)与注入的 org base 合并,协调 live toolkit(注册新增/移除已删),无 tenant context 时 no-op,失败 log+吞(同 McpServerRegistrar 约定);HarnessAgent.Builder.dynamicMcp(registry,orgIdExtractor,orgBaseLoader) 接线,userId 直接从 RuntimeContext.getUserId() 读。
- **SaaS 层**:OrgToolsConfigController `/api/org/tools/config`(admin-gated via JWT role claim;org id 取自 caller 的 org_id claim,admin 只能管自己 org)GET 返回 org base / PUT 替换 mcpServers 子键保留 settings 其余;OrgToolsConfigService 读写 orgs.settings.mcpServers(malformed 时降级空);AgentConfig dynamicMcp 接 TenantContext.orgId extractor + OrgToolsConfigService::loadOrgBase,注册单例 McpClientRegistry bean;OrgEntity.settings 加 `@JdbcTypeCode(JSON)`(列已在 V1 存在,无需迁移)。
- **修的真 bug**:① OrgToolsConfigService.saveOrgBase line134 `writeValueAsString(root)` 抛 checked `JsonProcessingException` 未 catch/throws→编译失败。包 try/catch 重抛 `IllegalStateException`(读路径降级空,写路径应 surface 而非静默丢 admin 配置)。② spotless lambda 格式→`spotless:apply`。
- **构建 gotcha**:`-pl agentscope-saas/agentscope-saas-app` 不带 `-am` 时从本地仓库拉**旧** saas-core jar(无 OrgEntity.getSettings)→编译报"找不到符号 getSettings"。**改了 saas-core 实体后必须先 `mvn -o -pl agentscope-saas/agentscope-saas-core -am install -DskipTests`** 再跑 app test。harness 改了同样要先 `-pl agentscope-harness -am install -DskipTests`(javadoc gate)。
- **验证**:harness install -DskipTests SUCCESS(javadoc 过);saas-app test **黄金 gate 17/17 绿**,含 SaasAppContextLoadsTest(@SpringBootTest + H2 local)全 context 加载(新 bean + dynamicMcp 接线,此测试是 bean-wiring 失败的黄金 gate)。
- **未做 e2e(已知缺口)**:OrgToolsConfigController admin-gated,但自注册用户全进 demo org 且默认 role=member,**API 无路径创 admin 角色**(只能 SQL 植)。故 PUT /api/org/tools/config 走普通 login 流程测不了,需 SQL 植 admin 的 harness(类 F6 e2e 的 Bob 模式)。这是预期 admin 分层非缺陷。

## 14-plan 之后剩余路线图(2026-06-21 核实)
**14-plan 的 F1–F6 全部完成并提交**(F1-F6 + 修 bug)。14-plan §3.5 预告的 Phase C/D **全部未开始**(代码核实:无 BrowserTool、无 agentscope-saas-tools 模块、无 LTM 接线、无 Admin 控制面、无 OTel/Micrometer、无 Coding IDE/LSP)。C2 是 Phase C 里的第一个子项(MCP 接线),已做。剩余:
- **F3-S2 热路径实时持久化**:暂缓,框架依赖项。HarnessAgent builder 强制 sandbox/remote/local filesystem **三选一**,无"sandbox+remote 叠加"能力,需框架侧先提供(SandboxBackedFilesystem 内置 remote 投影或允许 spec 叠加)。S1(MinIO stop-time 快照)已让持久化从 PG BYTEA 升级到对象存储,崩溃窗口仅限两次 stop 间。
- **F7 PermissionEngine tool_guard 精配 + LTM 可选接线**:延后。PermissionEngine 已在 AgentConfig 接线(B4′,ALLOW/ASK/DENY),F7 是"精配"非从零;LTM(LongTermMemory)saas 侧 0 接线,维持可选(未配降级 MEMORY.md,已通过快照存活)。
- **Phase C 工具补全**:C1?(未细分) + C2 MCP(✅ 已做) + **BrowserTool(Java Playwright,0%,需新 agentscope-saas-tools 模块,云沙箱内 headless chromium)** + **Coding IDE/LSP(0%,前端编辑器只读)**。
- **Phase D 生产化**:**Admin 控制面**(/admin/tenants/sandboxes/usage/audit,0%)+ **可观测**(OTel TenantTraceMiddleware + Micrometer + Grafana,0%)+ **DegradationManager** 优雅降级(0%)+ **离线交付**(Docker Compose + Helm,0%)+ 前端现代栈美化(11-doc shadcn,可选)。
- **模型网关**:内网 vLLM/Ollama 路由 + 密钥托管 + token 配额(10-roadmap Phase 5,0%)。
- **渠道系统**:Phase 4,但用户已确认**剔除 IM 渠道**(钉钉/飞书/微信),Web 为主。Web(AG-UI)已在 F6 通过 paw 前端 fork 落地。

## 端到端验证(2026-06-21,commit 45e2eb11):登录→沙箱 skill+MCP→结果(文本/文件)→记忆→释放
真 LLM(DeepSeek `deepseek-v4-flash` @ `https://api.deepseek.com/v1`,OpenAI 兼容 base-url 要带 `/v1`)+ docker 沙箱(`ubuntu:latest`,`SAAS_SANDBOX_WORKSPACE_ROOT=/workspace` 必设,否则 `mkdir ''` 失败)+ Redis + 真 JWT,e2e 库 `agentscope_saas_e2e` + `app`/`agentscope` 角色密码均 `agentscope`。
- **新增代码**:① AgentWorkspaceController `GET /workspace/file/download`(octet-stream + Content-Disposition,调 `fs.downloadFiles` 补现有 `GET /file` 对二进制只返占位符的缺口)。② `scripts/EchoMcpServer.java` 单文件 Java stdio MCP server(echo+calculate,零依赖零外网,`java EchoMcpServer.java` 跑,手写 JSON-RPC 对齐 io.modelcontextprotocol.sdk:initialize/tools/list/tools/call,协议自测全过)。③ `scripts/e2e-full.sh` 复刻 f6-e2e 断言模式。
- **sandbox-on(docker)12/13 → 13/13(commit fca90731 修后全绿)**:原先"唯一失败:跨沙箱 tar restore"是**误判**——加诊断日志后证实 AbstractBaseSandbox Branch C(stop tar + start restore)**本就工作**,只是成功不打日志看不见。真正根因是 execute 命令写的文件不在 workspaceRoot tar 范围内(doPersistWorkspace 前 `find /workspace -ls` 诊断只看到 `skills/.curator_state.json`,无 result.txt)。修法:chat 后台流式时 `docker exec` 直写 `/workspace/e2e-marker.txt`(确保进 tar);第二轮 chat 起新容器后 `docker exec cat` 直读(避开 LLM 不回显)。最终 **14/14 PASS**(reg/agent/MCP/C2/sandbox/persist/restore/release),跨沙箱文件存活 confirmed。
- **sandbox-off(Redis BaseStore)11/11**:download 端点 200+octet-stream+attachment+内容正确、MEMORY.md 持久化读回、跨 call Redis 持久化。证明改动 A 下载端点 + 记忆机制工作。
- **关键 gotcha**:① **sandbox-on 模式 workspace 端点 call 外 500**(`SandboxBackedFilesystem.requireSandbox: No active sandbox`)——工作区文件在 docker 容器内,call 结束沙箱 stop 后 download/read/memory 端点全 500。这是 F3-S2 热路径持久化要解决的(暂缓)。文件结果 call 外下载只有 sandbox-off(Redis BaseStore)能做。② **DeepSeek base-url 必须带 `/v1`**(`https://api.deepseek.com/v1`),框架 DeepSeekFormatter 示例即如此;OpenAIChatModel endpointPath 默认 null(用默认 `/chat/completions`)。③ `SAAS_SANDBOX_WORKSPACE_ROOT` 必须设(如 `/workspace`),空则 DockerSandbox `mkdir ''` 失败→chat 500 `Failed to start workspace`。④ e2e chat prompt 不能含双引号(JSON body 里破坏→400),用 `--data-binary @<(printf ...)` + prompt 无双引号。⑤ **SandboxEntity id 没手动赋值**(已修:commit f0b6bd54 在 `SandboxBroker.registerActive` 加 `setId(UUID.randomUUID())`)。修后 RLS 拒 INSERT(GUC 没传到 boundedElastic,类 F4 @Async 传播 bug),tracking 行仍存不了。**非阻塞,留作下阶段。** ⑥ docker 沙箱 `--network=none` 不影响 MCP/LLM(MCP server 是主进程 stdio 子进程,LLM 走主进程,不经沙箱网络)。⑦ e2e app 启动:**改了 harness 后必须 `java -jar` fat jar**(`spring-boot:run` 不重 resolve SNAPSHOT 依赖,新 class 不进 classpath)。`package` 触发 javadoc:jar,C2 跨模块 `{@link DynamicMcpMiddleware}` 引用找不到→`-Dmaven.javadoc.skip=true`。⑧ `users` 表无 status 列(admin INSERT 别写 status)。⑨ superuser `agentscope` 查 hash 绕 RLS(用户 id 被 RLS deny 时用 `-U agentscope`)。

## 下阶段实施依据(2026-06-21 e2e 验证实况,commit fca90731)

**e2e-full.sh**:sandbox-on 13/13 + sandbox-off 11/11(仅 download 端点 call 外 500,F3-S2 gap)。以下据此列出待修复和技术债。

### 待修复 Bug(影响功能正确性)

| # | 现象 | 根因 | 文件 | 修复方向 |
|---|------|------|------|----------|
| **B1** | `sandboxes` 表 RLS 拒 INSERT → tracking 行丢失 | GUC 没传到 boundedElastic(`SandboxTrackingMiddleware.onInput`,类 F4 @Async 传播 bug) | `SandboxTrackingMiddleware.java` | 注入 `TenantAsyncConfig.TaskDecorator` 或在 middleware 内手动 `SET app.current_org` |
| **B2** | workspace 端点 call 外 500 | `SandboxBackedFilesystem` 在无 active call 时抛 "No active sandbox" | 框架级,F3-S2 热路径持久化 | 等待框架侧 `SandboxBackedFilesystem` 内置 remote 投影,或允许 sandbox spec 叠加 RemoteFilesystemSpec |
| **B3** | execute 工具写的文件不进 workspace tar | execute 默认 cwd ≠ workspaceRoot,或 LLM 用 `execute` 而非 `write_file` 导致文件落在 tar 范围外 | `ShellExecuteTool` + DockerSandbox cwd 语义 + LLM prompt | 确保 execute 默认 workingDirectory=/workspace;或 e2e 用 `docker exec` 直写绕过 LLM 不确定性 |

### 已确认工作(诊断日志证实,不修)

| 机制 | 证据 |
|------|------|
| 跨沙箱 stop tar + start Branch C restore | exists=true → hydrate → 第二轮容器内 `cat /workspace/e2e-marker.txt` → `hello-cross-sandbox` |
| C2 动态 MCP stdio 握手 | `StdioClientTransport: MCP server started` → LLM 调 echo → SSE 含结果 |
| 沙箱释放 | call 后 `docker ps --filter name=agentscope-sandbox` 空 |
| LTM 记忆自动激活 | `memory_search`/`memory_get` 已注册,flush+consolidation middleware 自动注册 |

### 14-plan 剩余路线图(已更新)

**F1–F6 + C2 全部完成并提交**。剩余:
- **F3-S2**:框架级,暂缓。等待 SandboxBackedFilesystem 内置 remote 投影。
- **F7**:PermissionEngine 精配 + LTM 可选接线,低风险延后。
- **Phase C**:C2 MCP(✅) + ~~BrowserTool~~(用户否决,内网) + Coding IDE/LSP(0%)。
- **Phase D**:Admin 控制面(0%) + 可观测 OTel/Micrometer(0%) + DegradationManager(0%) + 离线交付(0%)。
- **模型网关**:代码已就绪(type=gateway),部署配环境变量。
- **渠道系统**:用户剔除 IM,Web(AG-UI)已落地。

## Cube sandbox 端到端验证(2026-06-22,dev profile 全绿)

用 `--spring.profiles.active=dev` 对真实 CubeSandbox(`http://cubesandbox.dev.comnova.cc:3000`)跑通完整 e2e。dev profile 零外部依赖:H2 + 内存状态 + ScriptedToolModel(把 chat message 当 shell 命令驱动 `execute` 工具,无需真 LLM)+ DevSecurityConfig 免登录。8 个 cube microVM 创建后全部释放(platform `/health` sandboxes=0)。

**验证通过项**:

1. cube 平台 API(`POST /sandboxes`/`DELETE`/`GET /templates`)可达;envd host `https://49983-{sandboxId}.{domain}` DNS+TLS 通(证书校验失败→`insecureSkipTlsVerify=true` 兜底,envdVersion 0.2.0 与 commit 1623782f 修复目标一致)。
2. 完整 ReAct 循环:ScriptedToolModel 首轮发 `execute` ToolUseBlock → CubeSandbox envd `/process.Process/Start`(Connect+protobuf)执行 shell → `TOOL_CALL_RESULT`=`"Exit code: 0\n\nhello-cube-sandbox\n"` → 次轮终止文本 → `RUN_FINISHED`(7/7 断言)。
3. 单次调用 FS 验证:`echo X > /home/user/m.txt && cat m.txt && pwd && ls` → 输出 `MARKER-XYZ`、`pwd=/home/user`、`ls` 含 m.txt(cwd=workspaceRoot,write/read 工作正常)。
4. **跨 cube microVM 工作区持久化(Branch C)**:call 1 `echo cross-cube-survivor > /home/user/survivor.txt` → stop → tar 快照(20480→30720 bytes 随写入增长)上传 PG → call 2 创建**新** microVM → `snapshot exists=true` → `Branch C: restoring from snapshot`(base64+tar shell hydrate)→ `cat survivor.txt` 输出 `cross-cube-survivor`。文件在两个独立 cube 实例间存活。此前 Branch C 仅 docker 沙箱验证过(commit fca90731),现 cube 也确认。
5. 沙箱释放:CubeSandbox.shutdown → `platform.killSandbox`(DELETE /sandboxes/{id},204);8 创建 0 残留。

**验证中发现并修复的 4 个 bug(dev profile 此前根本跑不通)**:

- **配置模板失效**:`application-dev.yml` 写死 `cube-template-id: tpl-72d9b3147a4c4029b9b10dfe`,该模板在 cube 服务器 "not available locally"(worker 镜像缓存被驱逐,`GET /templates` 仍标 READY 但 `POST /sandboxes` 报 130483)。改为 `${CUBE_TEMPLATE_ID:tpl-sandbox-code}`(env 可覆盖,默认当前可用模板)。
- **SandboxQuotaMiddleware UUID 解析崩溃**:`onAgent` 在 try 块**外** `UUID.fromString(tc.orgId())`,dev tenant `dev-org` 非 UUID → `IllegalArgumentException` 在中间件链首抛出,整个 chat 流 500。加 `isUuid` guard(非 UUID tenant 跳过配额检查),与 `SaasChatController.isPersistable` 跳过非 UUID 持久化的约定一致。
- **SandboxTrackingMiddleware 同样问题**:`UUID.fromString(tc.orgId())` 在 try 外,同样 guard 修复。
- **dev profile 权限 ASK 门**:`execute` 在默认 `askTools` 里(SaasProperties.Permission),dev 无覆盖 → ReAct 循环被 `require_user_confirm` HITL 暂停,命令不执行。dev profile 加 `saas.agent.permission.allow-tools: [execute, write_file, read_file, ...]` + `ask-tools: []`(dev 无人值守,自动放行)。

**关键 gotcha(非显然)**:

- **改 saas-sandbox 后必须 `clean install` 到本地仓库**再打 app 包。`mvn -pl agentscope-saas-app package`(无 `-am`)从 `D:\.m2\repo` 拉**旧** saas-sandbox jar(无修复)→ 运行时仍崩。且 Maven 增量编译会误判源码未变跳过重编译(即便 `clean install` 后 class 仍是旧的)——必须 `mvn clean install` 强制重编译。本地仓库是 `D:\.m2\repo`(非默认 `~/.m2`)。
- **`javap` 不在 Git Bash PATH**——用 `javap` 验证 class 是否含某方法会静默失败(`command not found`→空输出→误判"不存在")。用 `find + unzip + javap` 时先确认 javap 可用(`$JAVA_HOME/bin/javap`)。
- **`spring-boot:run` 不重 resolve SNAPSHOT 依赖**——改了 harness/sandbox 后必须 `java -jar` fat jar。fat jar 打包用 `-Dmaven.javadoc.skip=true`(cross-module `{@link}` 会让 javadoc:jar 失败)。
- **dev profile 默认端口 8080**(`${SERVER_PORT:8080}`);要换端口须显式传 `SERVER_PORT` env 或 `--server.port=`。
- **envd 间歇 HTTP 502**:后台 `SessionTree.mirrorToFilesystem`→`SandboxBackedFilesystem.uploadFiles` 偶发 `envd Start failed HTTP 502`(沙箱并发/teardown 竞态),非致命,chat 不受影响。
- **跨调用快照共享**:dev 模式下所有 chat 共享同一 snapshotId(`HarnessAgent` session 解析对 dev-user 稳定),跨调用 Branch C restore 依赖 stop→upload→新 start→hydrate 时序,call 间需留足 stop 时间(实测 3s 够)。

**测试 gate**:`mvn -o -pl agentscope-saas/agentscope-saas-sandbox -am install` + `mvn -pl agentscope-saas/agentscope-saas-app test` = saas-app 23/23 绿(含 SaasAppContextLoadsTest 上下文加载 golden gate)+ saas-sandbox 7/7 绿(含 SandboxQuotaMiddlewareTest,UUID guard 不破坏既有 UUID-tenant 配额测试)。offline 缺 `surefire-junit-platform:3.5.5` provider 时去 `-o` 在线跑。

**遗留(非阻塞)**:F3-S2 热路径实时持久化仍暂缓(框架级);dev 模式 chat 消息不持久化(非 UUID tenant,设计如此);envd 502 噪声待框架侧 sandbox filesystem 投影解决。
