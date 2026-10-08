# 38. 运行时可靠性实施记录

> 方案来源：[37. 运行时可靠性修复与能力收尾落地方案](./37-runtime-reliability-and-feature-closure-plan.md)
>
> 更新日期：2026-10-08。状态：运行时治理、Mem0 投影队列、PG 增量运行消息归档、关键消息提交屏障、大正文分离存储、轻量检查点及总量自适应转存、会话执行代际撤销、文件发布预留和持久补登已实施；最新验收与剩余范围见第 15 节。不代表 B0-B8 全部完成。

## 1. 首批范围

保留 DDD/MyBatis 边界，不修改 JWT，不新增业务 SQL 或数据库迁移，不改用户侧沙箱选择方式。真实沙箱只验证本地 Docker 部署的 OpenSandbox。

| 项目 | 本批实现 | 边界 |
|---|---|---|
| R01.1 辅助模型路由 | `StepBindableModel.bindToContext` 显式绑定可信运行上下文；记忆提炼、整合复用组织模型目录、绑定路由和窗口准入 | 主请求仍以最终消息可信 metadata 路由，不使用另一套冲突的租户来源 |
| R01 有界收尾 | 辅助调用采用总 deadline；提炼输出最多 1024 token，整合输出受配置及所选模型限制；截断、过滤、超时或调用失败不写记忆 | 提炼默认 30 秒，整合默认 60 秒；保留旧构造器，新增带 deadline 的构造器。用途计量、分页输入和后台提炼尚未实现 |
| R02.1 完整扫描门禁 | 新增 `WorkspaceProjectionReport`，记录扫描、上传、未变化、字节、扫描完整性和拒绝原因；文件数、总量、单文件或安全路径违规都不再静默成功 | 复用生命周期失败回执及应用层 checkpoint 门禁；清理、状态持久化和释放继续执行 |
| R03.1 一致容量 | 新增 `WorkspaceTransferPolicy`，初始化、投影、恢复共享限制；子 Agent 文件系统 fork 继承策略 | 默认 5000 文件、32 MiB/文件、256 MiB/工作区；本批不改变 byte[]/内存 tar 传输方式 |
| R04.1 可执行恢复配置 | 新增 `REQUEST_ONLY` / `DURABLE` 模式；未启用调度器不得配置启用的 DURABLE；未成功取得恢复租约，不返回“后台恢复已安排” | 默认 REQUEST_ONLY，保留请求内恢复；不把配置检测等同于跨进程 Worker 心跳证明 |
| R05.1 增量归档止血 | 归档保留 `Msg.id`，已归档相同消息不再追加；相同 ID 不同内容拒绝覆盖 | 不删除旧随机 ID 历史，不宣称解决跨副本竞态；PG 权威账本仍在 B2 |

## 2. 配置变更

```yaml
saas:
  model:
    stream-recovery:
      enabled: true
      mode: REQUEST_ONLY
  file-store:
    max-file-bytes: 33554432
  sandbox:
    transfer-max-files: 5000
    transfer-max-bytes: 268435456
```

环境变量：`SAAS_MODEL_STREAM_RECOVERY_MODE`、`SAAS_FILE_STORE_MAX_FILE_BYTES`、`SAAS_SANDBOX_TRANSFER_MAX_FILES`、`SAAS_SANDBOX_TRANSFER_MAX_BYTES`。

启用后台恢复时，显式设置 `mode: DURABLE`，同时启用 `saas.orchestration.enabled` 与 `saas.orchestration.scheduler-enabled`。禁用恢复策略时不要求调度器。总传输容量不得小于单文件容量，文件数和容量必须为正，非法组合在装配时失败。

本次行为变更：以前超限或不安全归档可能返回成功，现在任务会返回失败；不会删除前一次完整扫描的文件清单。容量拒绝不是暂时模型故障，不进入模型恢复循环。远程初始化遇到下载失败也不再假装文件已准备好。

## 3. 回归证据

新增/扩展测试覆盖：选择非默认模型的提炼及整合、保持主请求路由边界、重复归档、新消息追加、ID 内容冲突、超限与非法路径扫描、旧清单保留、文件数/总容量边界、5 MiB 恢复、配置限制恢复、后台恢复配置矩阵、checkpoint 失败后清理仍可记录、辅助调用 deadline、截断记忆不覆盖及不推进水位。

- 全量 SaaS reactor：最终业务代码回归通过，`mvn -o -pl agentscope-saas/agentscope-saas-app -am -Djacoco.skip=true test -q` 返回 0；不代表所有可选外部集成都已实测。
- 双租户模型路由、恢复模式、生命周期回执和 DDD/MyBatis 架构约束：4 个应用层测试类、29 项测试通过。后补的双租户夹具单独重跑通过。
- 本批 Harness 重点回归：辅助调用 3 项、增量归档 2 项、沙箱文件系统 21 项、沙箱生命周期 9 项通过，共 35 项；SessionTree 镜像及其它 Harness 测试随全量回归执行。
- 前端：`npm run build` 通过，静态文件无额外变更。
- OpenSandbox Provider 生命周期：4 项通过，创建、等待 Running、execd 执行、删除。
- OpenSandbox 企业任务 smoke：12/12 通过，包含真实登录、上传、下载、配额、SSE、HITL、执行输出、长会话分页、归档下载、后端资源回收；使用 scripted 模型和真实 PG/Redis/MinIO/OpenSandbox。
- OpenSandbox 发布门禁：8/8 通过，包含静态入口、健康、上述任务 smoke、管理诊断和 Prometheus 指标。
- 本批未修改前端交互；API smoke 不等同于全部页面浏览器验收。

本地命令证据：

```bash
OPENSANDBOX_API_BASE_URL=http://127.0.0.1:18081 \
  bash agentscope-saas/agentscope-saas-app/scripts/opensandbox-runtime-lifecycle.sh
APP_PORT=18082 START_DOCKER_DEPS=false \
  bash agentscope-saas/agentscope-saas-app/scripts/start-opensandbox-local.sh --smoke
mvn -o -pl agentscope-saas/agentscope-saas-app \
  -Dtest=ModelCatalogTest,RunRecoveryCoordinatorTest,MeteredSandboxLifecycleObserverTest,DataAccessArchitectureTest \
  -Djacoco.skip=true test -q
```

本批容量拒绝、截断输出、恢复配置不合法等负向场景以定向回归验证，尚未全部做真实浏览器/真实网关故障注入；跨进程强杀、对象提交中断和大工作区流式恢复在后续批次验收。本批未使用外部付费模型、CubeSandbox 或 E2B，不验证 JWT 密钥治理。

首次全量测试受受限执行环境影响，Mockito 无法附加 JVM，媒体测试进程退出；已解除限制重跑。测试使用 `-Djacoco.skip=true`，不删除或改写旧覆盖率数据来规避问题。

## 4. 后续顺序

1. 继续 R01.2：统一调用治理、输入分块、调用补偿和策略页面已实施；Mem0 投影队列可靠性见第 7 节。文件式记忆的独立后台预算、页级进度和可恢复水位仍未完成。
2. B2：不可变产物发布账本、流式对象接口、PG 增量运行消息账本、按任务文件清单恢复，消除全用户工作区初始化与跨副本 JSONL 竞态。
3. B3：安全人工重试、工具副作用对账、状态驱动的 API/UI。
4. B4/B5：企业只读岗位主数据、个人记忆卡片及可选 Mem0 投影、确定性产物验证。
5. B6-B8：生产部署与故障/灾备门禁、用户定时任务、MCP 授权及高级运行框架能力。

以上未完成项继续按文档 37 验收矩阵执行，不把单测通过或旧报告中的“等价能力”当作全部完成证据。

## 5. 第二批：统一调用治理

### 5.1 实施范围

本批新增数据库迁移 V34；仍保留 DDD/MyBatis 边界，不修改 JWT，不验证 CubeSandbox/E2B，不改用户侧沙箱选择方式。

- Core 新增框架中立的 `PurposeBindableModel`：绑定用途、上下文窗口、输入估算和调用限额；Core/Harness 不引入 SaaS 身份类型。
- SaaS 将 `ModelInvocationService` 作为主模型适配：主推理、Core/Harness 压缩、记忆提炼和整合复用可信组织/用户身份及固定路由。无可信身份的调用明确失败，不默默切换全局模型。
- 组织可为辅助用途指定可用模型；默认继承当前选择。有效输入/输出限额取用途配置和绑定模型窗口的较小值，超限在发送前拒绝。
- 新增 Domain Port、MyBatis Mapper/Repository，以及 PG/H2 `model_invocations` 和 `model_invocation_policies`。账本关联组织、用户、Run、Task、AgentRun 和 Attempt；PG 表开启强制 RLS，管理通道 SQL 仍显式约束租户和所属用户。
- 准入短事务锁定组织及用户、检查有效租约/期限、登记在途 token/cost 预留并占用调用次数；模型 IO 在事务外执行。结算使用调用 ID 和 `STARTED` 条件转换，用量投影和 Run/Task 统计在同一事务内更新。
- 主调用、同步辅助调用共同使用 Run/Task 预算，同时检查组织/用户日配额及用户月度等级 token 配额。历史 `usage_records` 纳入额度统计，迁移不会重置旧用量。日/月边界使用 UTC。
- 输出上限可按剩余预算进一步缩小；保留 `maxTokens` 或 `maxCompletionTokens` 的原接口选择，不同时注入两种参数。请求生成参数不能改写模型地址、凭证或传输认证头。
- 总 deadline 不会被连续流式输出延长。失败、取消、任务/组织失效仍记录已消耗资源；终态 Run 只补记资源事实，不重放调度状态。缺失完整 provider 用量时标记为估算，不作为准确计费数据。
- 移除受治理模型的旧事件式重复扣减；非 SaaS/未托管模型保留旧中间件兼容行为。历史没有 AgentRun 的持久任务必须携带 Run/Task/Attempt 和有效租约，并走任务预算治理，不能绕过预算。
- AI 助手配置生成入口补充从认证身份构建调用上下文、输出上限和总超时；截断结果不能成为可用配置。管理员连通性探针仍属于单独的有界管理操作及审计，不等同于运行任务调用账本。
- 链式压缩保留已有摘要；失败、空输出、长度截断或内容过滤不再生成假摘要。压缩中间件仅在原输入仍满足窗口限制时允许保留原历史继续运行。

### 5.2 配置与管理接口

```yaml
saas:
  model:
    invocations:
      reasoning-timeout-seconds: 300
      max-daily-org-tokens: 10000000
      max-daily-user-tokens: 200000
      max-daily-org-calls: 10000
      max-daily-user-calls: 200
```

上面的配额为示例，不是部署默认值。默认四项日配额均为空，表示不增加日配额限制；`0` 阻止新调用，负数和非正超时配置拒绝装配。对应环境变量为 `SAAS_MODEL_INVOCATION_TIMEOUT_SECONDS`、`SAAS_MODEL_MAX_DAILY_ORG_TOKENS`、`SAAS_MODEL_MAX_DAILY_USER_TOKENS`、`SAAS_MODEL_MAX_DAILY_ORG_CALLS`、`SAAS_MODEL_MAX_DAILY_USER_CALLS`。

辅助用途默认限额如下；最终仍受模型窗口约束：

| 用途 | 输入上限 | 输出上限 | deadline |
|---|---:|---:|---:|
| COMPACTION | 16000 | 2048 | 60 秒 |
| MEMORY_EXTRACT | 8000 | 1024 | 30 秒 |
| MEMORY_CONSOLIDATE | 16000 | 4000 | 60 秒 |
| VERIFY | 8000 | 1024 | 60 秒 |

管理员接口：

- `GET /api/admin/model-invocations/policies`：当前组织四类辅助策略，包含默认策略版本。
- `PUT /api/admin/model-invocations/policies/{purpose}`：设置 `modelId`、`maxInputTokens`、`maxOutputTokens`、`timeoutSeconds` 和当前 `version`；不接受请求体中的组织或用户身份。
- `modelId: null` 表示继承所选模型；策略更新使用版本冲突检测，旧版本更新返回 409。模型必须属于当前组织可用目录；普通用户不能读写策略。
- 本批提供 API，没有新增管理端策略编辑界面。VERIFY 为后续验证器预留，不能视为确定性产物验证已经交付。

### 5.3 验证证据

- 全量 SaaS reactor 回归通过，包含 Core、Harness、Domain、DAL、应用层及 DDD/MyBatis 架构约束。首次发现历史持久任务缺失 AgentRun 的兼容性问题，修复后全量重跑通过。
- 调用账本集成 11 项、调用适配 7 项、管理策略权限 4 项、压缩可靠性 4 项，以及真实持久任务执行回归通过；用量只增加一次，过期租约/组织不能提交成功。
- 本地真实 PostgreSQL 已成功执行 V34；OpenSandbox 任务 smoke 12/12，发布门禁 8/8，使用 scripted 模型和真实 PG/Redis/MinIO/OpenSandbox，不使用付费模型。
- 沙箱任务门禁结束后脚本停止应用进程；Spring Boot Maven 插件记录退出 143 是脚本主动停止的结果，门禁脚本整体返回 0。四个本次启动的基础容器已恢复为停止状态，未删除数据卷。
- AI 配置生成补充 3 项测试、最终应用层全部回归通过。前端没有本批代码变更，未声称完成新增页面浏览器验收。
- 门禁后额外的 PG 角色隔离只读检查未完成：Docker `start/exec` 接口长时间无响应，包括兼容 API 的重试。已终止本轮阻塞命令；不将这项补充检查记为通过。此前四容器停止命令成功，补充 PG 重启尝试失败；最终补充清理状态以本地 Docker 恢复后的检查为准。

### 5.4 尚未完成的治理工作

以下为第二批结束时的历史记录；已继续实施的项目以第 6 节为准。

R01.2 仍是部分交付，不应标记整体完成：

1. 输入已按用途与模型窗口准入，但记忆和摘要尚未实现完整分页/分块折叠；超限目前拒绝或由上层保留历史，不自动把全部内容拆成持久后台任务。
2. 辅助失败尚未形成 PG/Outbox 待处理队列、独立后台预算和可恢复水位；同步维护仍保留。源 Run 已终态后不能再用其执行范围开始调用，需要先落实后台任务生命周期。
3. 服务实例崩溃留下的 STARTED 记录尚无持久补偿结算任务；过期预留不继续占用完整输出预算，但保守保留估算输入和调用次数。尚不能承诺准确计费或外部供应商物理重试次数的一一核算。
4. 当前发送前复查组织目录可用性，不代表完成跨副本即时策略撤销；同 ID 管理模型删除后暴露部署模型、绑定后撤销以及策略热更新的完整传播矩阵需补齐。
5. 组织级策略管理页面、更多双租户真实数据库模型路由测试和强杀/结算中断故障注入仍待验收。

下一批先完成以上分页和后台生命周期，再进入 B2 的产物发布及 PG 增量消息账本。其余 B3-B8 和文档 37 的未完成范围保持不变。

## 6. 第三批：输入分块、遗留调用补偿及管理界面

本节记录第三批结束时的验收；后续 Mem0 投影队列变更见第 7 节。

### 6.1 实际运行路径

- 新增框架中立的 `PurposeTextFold`，摘要、记忆提炼和记忆合并均接入。按绑定模型的输入估算器、模型窗口、用途策略及显式配置取最小预算；预算包含提示、前页结果和消息包装，不按裸文本长度直接调用模型。
- 压缩不再截掉最早的待摘要历史。按原顺序逐页处理，每页携带上一页摘要；已有摘要参与合并，保留的最新原消息不变。超大单条文本也可分块，UTF-16 代理对不在页边界拆开。
- 大记忆引用先逐页形成只读去重参考，再提炼对话；大 `MEMORY.md` 和日记进入合并源流，避免每次把整个旧文件作为提示。每个请求受输出限制与总调用 deadline 约束；取消不会继续发送后续页。
- 所有页成功后才返回完整替换或写入记忆；后续页失败、空回复、过滤或截断不提交部分结果，也不推进合并水位。模型摘要仍是有损表示，不替代原始消息或企业岗位主数据。
- 模型调用补偿任务按 deadline 加宽限期扫描 `STARTED`，逐个短事务锁定组织/用户并复查状态；多个实例竞争只结算一次，不重发模型请求、不重执行工具、不自动转换 Run 状态。每条失败独立处理，下轮可重试。
- 崩溃前真实供应商用量未知时，以该调用准入的完整 token 预留上界做保守估算，标记 `ESTIMATED` 和 `MODEL_INVOCATION_ABANDONED`；不宣传为准确计费。已有实际结算回执优先；补偿已落库后迟到回执不重复累加或改成成功。
- 新增 V35：PG 部分索引按 `deadline_at,id` 覆盖跨租户过期扫描；H2 对应索引与契约同步。仍由 Domain 定义端口、DAL 的 MyBatis 实现 SQL、应用服务管理事务，没有新增业务 JDBC。
- 模型目录在读取时复查 PG 定义版本，未变化时复用已构造的模型对象。调用在准入前和实际发送前复查目录及用途策略；另一副本的删除、禁用、配置变更会阻止旧绑定继续发送，尤其不能因删除同名托管模型而让旧任务偷偷调用部署模型。
- 策略变更要求下一次调用重新绑定，不在一次已经开始的请求中替换供应商；不是已发送调用的即时强制中止。组织目录任一版本变化采用保守拒绝旧绑定，后续可优化成单模型版本判断。
- 持久目录读取显式使用已认证的绑定组织，并恢复 ThreadLocal；异步发送通过可信 Reactor Context 传播组织，避免后台/换线程读不到 RLS 行后错误回退目录。
- 管理端 Models 页面增加辅助用途策略表和编辑弹窗，支持继承任务选择模型或指定组织模型、输入/输出 token 上限、timeout 和版本冲突提示。VERIFY 显示 Reserved，不能理解为独立产物验收器已经交付。

### 6.2 运维配置

```yaml
saas:
  model:
    invocations:
      reconciliation-enabled: true
      reconciliation-grace-seconds: 60
      reconciliation-batch-size: 100
      reconcile-fixed-delay-seconds: 60
```

对应 `SAAS_MODEL_INVOCATION_RECONCILIATION_ENABLED`、`SAAS_MODEL_INVOCATION_RECONCILIATION_GRACE_SECONDS`、`SAAS_MODEL_INVOCATION_RECONCILIATION_BATCH_SIZE`、`SAAS_MODEL_INVOCATION_RECONCILE_FIXED_DELAY_SECONDS`。宽限期必须为正；批量范围 1..1000。可关闭补偿任务，不改变请求内调用账本。

### 6.3 验证记录

- 新增逐页完整覆盖、前页传递、实际窗口/用途限额、代理对边界、取消停止分块、后页失败不提交、超大旧记忆及日记折叠测试。
- 新增调用补偿并发竞争、幂等资源入账、不重放 Run、迟到回执、有效调用不补偿和跨组织不可读契约；新增两副本目录删除/同名部署回退、策略绑定后变更及租户换线程读取测试。
- 管理页面脚本 `scripts/model-invocation-ui-smoke.cjs`：1440x1000 和 390x844 两视口通过编辑、保存、刷新回显、版本冲突、对话框尺寸、页面无横向溢出及无页面异常检查；截图已人工查看。API 使用明确测试夹具，不冒充真实后端 E2E。
- `npm run build` 已通过，静态资源随新页面重建。
- 完整 reactor 初跑在 Harness 发现旧测试假定摘要只调用一次；已调整为验证两页输入完整且不超限，不放宽窗口约束。
- 随后完整 reactor 被已有 `AgentPerformanceTest` 阻断：一次长历史测试异常墙钟耗时，单独重跑长历史恢复正常，但冷启动单次响应记录 1351ms，超过原 1000ms 断言。没有修改性能阈值；性能门禁仍未通过。
- 功能回归还发现关闭管理器测试的自定义配置跨测试泄漏；`resetForTesting()` 现在同时恢复默认配置，并补充复位测试，不改变生产关闭策略。
- 最终 SaaS reactor 功能回归返回 0，明确排除上述 `AgentPerformanceTest`，不是包含性能门禁的全量验收。逐页折叠 5 项、分页记忆维护 3 项、压缩可靠性 5 项、调用账本 13 项、调用适配 9 项、模型目录 9 项、管理策略接口 4 项，以及 DDD/MyBatis 架构检查 11 项和仓储集成 2 项均通过。外部集成的条件跳过仍不能算作实测。
- 真实 PG/OpenSandbox 门禁尚未完成：Docker 查询可用，但 `start saas-pg` 在旧兼容 API 和协商 API 均挂起。已终止本次门禁及挂起 CLI，最后状态查询确认四个依赖容器均仍停止，未删除数据卷。V35 尚未据此宣称在真实 PG 上验收。

最终功能回归命令：

```bash
mvn -o -pl agentscope-saas/agentscope-saas-app -am \
  -Djacoco.skip=true '-Dtest=*,!AgentPerformanceTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test -q
```

本地证据日志：`/private/tmp/chugou-reliability-functional-tests-final.log`、`/private/tmp/chugou-invocation-ui-build-final.log`、`/private/tmp/chugou-policy-ui-smoke-final.log`。浏览器脚本通过 `BROWSER_CHANNEL=chrome` 运行；页面使用 API 夹具，验证后的临时前端服务已停止。

### 6.4 明确剩余边界

1. **持久后台记忆生命周期未完成**：PG/Outbox 待处理任务、独立后台预算、页级持久进度和可恢复水位尚未实施。当前分块仍是一次调用链中的串行处理，进程重启后从原始输入重新处理；未声称持久分页任务已完成。
2. **文件读取仍未完全流式化**：分块限制每个模型请求和候选页分配，但上游会先读取/序列化完整文件或历史文本；PG 消息分页权威账本及对象流式读取仍属于 B2。
3. **用量精确对账未完成**：供应商物理重试次数、崩溃时真实输出量、已估算回执与迟到实际账单的调整流程仍需专门对账设计。
4. **真实依赖与故障验收未完成**：V35 的真实 PG/RLS、跨实例策略传播实测、强杀/结算中断、全量性能门禁和真实浏览器后端联动不能用夹具或 H2 通过代替。
5. B2 文件发布与增量历史、B3 安全重试、B4 岗位/个人记忆治理、B5 独立验收器及 B6-B8 仍按文档 37 的依赖推进，本批不标记这些范围完成。

## 7. 第四批：持久语义记忆投影队列

### 7.1 范围与真实边界

本批首先修复已有可选 Mem0 链路，不将它混同于 Harness 文件式记忆后台化。继续保留 DDD/MyBatis：领域层定义来源入账和领取/结算端口，DAL 实现 SQL，应用层处理调用、退避和调度。没有业务 JDBC，不修改 JWT，不调用外部付费模型，不验证 CubeSandbox/E2B。

- 请求内不再直接 `mem0.add(...).subscribe()`。成功完成且收到父 Agent 最终结果后，异步调度线程执行一次短 PG 来源入账；流完成前等待这次入账，不等待 Mem0 推理。失败、取消、暂停或仅收到子 Agent 结果都不建立新投影来源。
- 新来源只包含本轮最后一条原始用户输入和父 Agent 最终回答，不重复提交整个 session，不采集系统提示、工具结果、压缩历史或召回记忆。历史已经存在的来源不自动重写或删除。
- 来源 ID 由可信组织、用户、Agent、session 与源用户消息 ID 形成。DAL 的 `appendIfAbsent` 只插入、不更新；重复来源不会重置已领取或已同步状态。相同身份但内容不同明确拒绝覆盖。来源消息 ID 缺失的兼容调用仍用新 ID，不宣称对无标识旧数据去重。
- PG `memory_events` 本身就是这条投影的持久队列，没有额外建立双写消息队列。开启 Mem0 必须装配持久来源账本；关闭 Mem0 不影响平台运行。无账本的兼容构造器仅检索，不把未入账内容偷偷发送给 Mem0。
- V36 增加每次领取唯一的令牌、租约期限和下一次尝试时间。有限扫描后对每条记录执行带组织、状态、尝试次数及到期条件的原子领取；模型/HTTP IO 不持有事务或行锁。竞争失败跳过，不启动第二次投影。
- 尝试次数在领取时增加，进程崩溃也计入。租约过期后可被新 Worker 接管；旧 Worker 必须同时满足当前令牌和未过期租约才能结算，迟到成功/失败、跨组织更新和重复结算不再覆盖当前状态。
- 单次调用使用总超时；Mem0 请求明确 `async_mode=false`，避免将服务端异步排队回执当作同步完成。空 Mono 或缺少 `results` 列表的受理回执不记为同步成功；合法空列表表示本轮没有需要提炼的内容。失败按指数退避加有界抖动，耗尽进入 `dead_letter`；最后一次领取后崩溃的记录也会在租约过期后进入该终态，不无限重复领取。
- 源账本的组织、用户、Agent 和事件 ID 覆盖不可信 metadata 中的同名字段。持久错误和应用日志只记录错误类别，不记录供应商原始错误正文中的敏感内容。
- 投影使用独立单线程调度器，不替换默认运行时调度器；每批还有领取时间预算。慢服务不会占住运行时租约/截止时间的调度线程。时间预算限制继续领取，不中断已领取调用，其最长额外执行时间受单次超时约束。
- 管理端 Memory projection 页面增加 `dead_letter` 筛选、危险状态色及失败/耗尽统计。关闭 `replay-enabled` 时仍可入账，投影暂停；恢复开关后继续扫描。终态重放目前需专门的运维流程，尚未新增人工重放 API/UI。

### 7.2 配置与升级

```yaml
saas:
  ltm:
    enabled: false
    replay-enabled: true
    timeout-seconds: 60
    replay-stale-seconds: 300
    replay-batch-size: 50
    replay-max-attempts: 10
    replay-fixed-delay-seconds: 60
    replay-retry-base-seconds: 30
    replay-retry-max-seconds: 3600
    replay-scan-budget-seconds: 30
```

新增环境变量：`SAAS_LTM_REPLAY_RETRY_BASE_SECONDS`、`SAAS_LTM_REPLAY_RETRY_MAX_SECONDS`、`SAAS_LTM_REPLAY_SCAN_BUDGET_SECONDS`。现有 `SAAS_LTM_REPLAY_STALE_SECONDS` 现在也是新领取的租约长度，必须严格大于 `SAAS_LTM_TIMEOUT`；旧的无租约 `syncing` 记录按 `updated_at` 及同一宽限配置恢复。旧尝试次数不重置。每批 1..1000 条、总尝试 1..1000 次，超时、退避和时间预算必须是有效正数，错误配置在装配时拒绝。

部署应先应用 V36 再运行新代码；滚动升级前停止旧版本直接写入/重放 Mem0 的入口，旧二进制没有领取令牌保护，不能与新 Worker 混跑并声称已满足防迟到覆盖。

### 7.3 验证记录

- 定向回归通过：Mem0 Worker 13 项、来源入账 4 项、中间件 9 项，以及 DDD/MyBatis 架构和仓储集成检查。并发领取使用真实 MyBatis/H2，不用 mock 代替原子条件更新。
- H2 全应用启动已成功执行到 V36；新增插入去重 SQL 也经过实际 MyBatis 仓储验证。
- 最终 SaaS reactor 功能回归返回 0，仍明确排除上一批未通过的 `AgentPerformanceTest`，外部集成的条件跳过不算实测。当前 Worker 16 项、中间件 9 项、来源入账 4 项均通过；涵盖真实 Mem0 SDK 对本地 HTTP 测试服务的 503 后恢复、受理但未完成回执拒绝、扫描时间预算和并发令牌条件。该 HTTP 服务是可控协议夹具，不是真实 Mem0 推理服务。
- 应用启动及独立调度器检查 2 项通过，确保 Mem0 调度器与默认运行时 `taskScheduler` 不同；DDD/MyBatis 架构约束及仓储集成继续通过。H2 启动迁移到 V36 不等于 PG 的 RLS 验证。
- 前端构建通过并重建静态资源；API 夹具浏览器测试在 1440x1000、390x844 两视口通过模型策略编辑/保存/冲突，以及记忆失败终态筛选、移动端记录滚动与无页面横向溢出检查。截图已查看，临时前端服务已停止；不把夹具测试称为真实后端浏览器 E2E。
- 真实 PostgreSQL/OpenSandbox 验证仍受阻：Docker 列表查询返回，但本次 `docker start saas-pg` 在 20 秒硬超时内未返回，命令已被终止。最后状态检查确认 PG、Redis、MinIO、OpenSandbox 四容器均仍停止；未删除容器、数据卷或强制重启 Docker Desktop。V36 的真实 PG/RLS 验收不能以 H2 结果代替。

最终命令与本地日志：

```bash
mvn -o -pl agentscope-saas/agentscope-saas-app -am \
  -Djacoco.skip=true '-Dtest=*,!AgentPerformanceTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test -q
NODE_PATH=/Users/family/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules \
  BROWSER_CHANNEL=chrome SCREENSHOT_DIR=/private/tmp/chugou-memory-queue-ui \
  node agentscope-saas/agentscope-saas-app/scripts/model-invocation-ui-smoke.cjs
```

日志：`/private/tmp/chugou-memory-queue-functional-tests-final.log`、`/private/tmp/chugou-memory-ui-build.log`、`/private/tmp/chugou-memory-ui-smoke-final.log`。本批没有新增模型凭证、外部模型请求或沙箱供应商切换。

### 7.4 仍需完成

1. 本批是 **Mem0 来源到语义服务的后台投影**，不是所有记忆计算后台化。Harness `MemoryFlushMiddleware` / `MemoryMaintenanceMiddleware` 的同步文件式提炼、整合仍存在；独立用途预算、页级持久结果、可恢复水位、岗位/个人记忆卡片治理继续按 R01/R08 实施。
2. 来源入账尚未与 PG 权威运行消息提交处于同一事务。数据库故障导致来源入账失败时会降级保留用户回答，但不能承诺已生成待处理记录；从权威历史补建来源需依赖 B2 增量消息账本。
3. 领取令牌只保护本地状态；如果 Mem0 已写入而响应丢失，后续可能再次提交。稳定事件 ID 作为 metadata 便于对账，**不是供应商幂等键承诺**。当前是有界至少一次尝试，不宣称外部副作用恰好一次或自动消除向量重复。
4. Mem0 内部推理费用/窗口、来源级数据保留与删除、人工终态重放和语义卡片幂等 upsert 尚未接入统一模型预算账本。当前 `infer=true` 的兼容投影不能被当作企业岗位主数据或已确认个人记忆。
5. B2-B8、真实 PG/OpenSandbox 故障注入及上一批性能门禁仍未完成；本批未变更性能阈值，也未用局部通过替代全平台端到端验收。

## 8. 第五批：文件式记忆后台化的来源前置条件

### 8.1 为什么先提交消息账本

文档 37 第 4.3 节明确要求：R05 增量来源未完成前保留同步记忆维护。当前 JSONL 的远端镜像是异步最佳努力；如果直接把文件提炼移出请求，后台作业可能在沙箱释放后找不到完整输入。本批因此先落实 PG 顺序来源，不将框架 `.block()` 改成无控制 `.subscribe()`，也不声称独立后台记忆预算已经交付。

### 8.2 归档模型与接线

- V37 新增 `runtime_message_streams` 和 `runtime_messages`。问答仍使用现有 `chat_messages`，运行消息正文逐条保存，身份、seq、父消息 ID、写入时 Run/AgentRun 引用、内容摘要、字节数和时间分别存储；没有把整个会话塞进一个字段。
- 顺序域采用 `(org, root session, agent label, runtime session key)`，原始 Run/AgentRun 作为消息来源引用。主会话跨 Run 复用同一流，避免每轮创建新 AgentRun 时把保留窗口重新复制成全部历史；子 Agent 继承归档接口，使用自己的名称/会话键分流。这是会话提交顺序，不替代任务 DAG 的依赖顺序。
- 框架中立的 `SessionArchiveStore` 不引用 SaaS 身份或数据库。Domain 定义仓储端口，DAL 的 MyBatis 实现 SQL，应用服务显式使用可信租户身份及主数据源的短事务，没有业务 JDBC。
- 归档事务验证 session 属于组织、员工及绑定的 Agent，锁定 session/流游标后分配 seq。相同消息 ID/摘要重复提交不新增；同 ID 不同内容拒绝，新增行和游标一起回滚。正文哈希规范化字典顺序/数字表示，不把路由 metadata、timestamp 或工具调用的审批/执行状态当作内容冲突；工具参数、正文及参数中的业务 `state` 字段仍参与严格校验。归档保留首次提交的完整消息，不用后续运行状态覆盖原文；工具副作用及终态仍以已有 Journal 为准。
- `SessionArchiveMiddleware` 在输入进入运行链、推理上下文投影前和成功完成前提交可用原始消息；记忆 offload、正常及紧急压缩共用同一接口。归档失败是关键提交失败，不能吞掉错误后丢弃原消息。非 SaaS/未配置接口的独立 Harness 保留原 JSONL 行为。
- 系统提示、框架压缩摘要和召回记忆不作为原始对话来源；真实用户输入中的 XML/context 标签不按字符串模式误删。
- 工具结果驱逐、参数截断和 `@文件路径` 展开是工作投影，保留已提交来源摘要。投影不能冒充新的原始正文；父子流需要继承投影时，只能从同一组织、员工、Agent 及根 session 下的已提交完整来源恢复。原始工具正文不会被预览片段覆盖；附件展开不覆盖用户原始输入，文件内容仍由独立文件存储管理。
- 输入归档限制消息数、单条字节数及批量正文总量，超限拒绝，不静默截断。窗口读取先只取有界 seq/字节元数据，再取累计字节预算内的正文；同时控制条数及正文总字节数，不深 OFFSET、不加载全部历史到应用内存。
- `session_history`、`session_search`、`session_list` 在配置 PG 接口时读取持久账本，不依赖本机 JSONL 或活动沙箱。历史/搜索工具返回明确标注的有界预览；PG URI 不是文件路径，压缩摘要提示使用历史工具读取。
- 新增员工自己名下的只读 API：`GET /api/agents/{agentId}/sessions/{sessionId}/runtime-messages`。先校验 session 所属关系，再读取 `agentLabel/sessionKey` 指定的流；支持 `afterSeq` 或 `beforeSeq`、`limit`，返回下一游标和 `hasMore`。问答页面和文件 UI 没有被替换为完整运行轨迹。
- session reset 同事务清理运行账本；session/Agent/员工/组织删除通过外键清理其归档域。读工具遇到同一员工历史中不唯一的会话键会明确拒绝歧义；API 的根 session 条件在 SQL 查流时生效，不先拿另一根会话再做过滤。
- 新 Mapper 默认 INFO，即便上层包开 DEBUG，也不默认把私有运行消息正文作为 SQL 参数写入日志。

### 8.3 配置

```yaml
saas:
  runtime-archive:
    enabled: true
    max-batch-messages: 5000
    max-message-bytes: 33554432
    max-batch-bytes: 67108864
    max-window-bytes: 33554432
```

对应 `SAAS_RUNTIME_ARCHIVE_ENABLED`、`SAAS_RUNTIME_ARCHIVE_MAX_BATCH_MESSAGES`、`SAAS_RUNTIME_ARCHIVE_MAX_MESSAGE_BYTES`、`SAAS_RUNTIME_ARCHIVE_MAX_BATCH_BYTES`、`SAAS_RUNTIME_ARCHIVE_MAX_WINDOW_BYTES`。窗口总量必须至少覆盖单条上限；batch/字节组合非法时拒绝装配，单次读最多 500 条。默认启用 PG 新写入，部署先执行 V37；禁用时回到旧 Harness 文件归档，不将文件模式称为跨实例强一致账本。

新读 API默认返回最新窗口；`afterSeq=0` 从起点向前读取。`afterSeq` 与 `beforeSeq` 不可同时使用。大消息使页面未达到条数上限时，仍根据索引探测返回准确的 `hasMore`，不能把字节截断误当作历史结束。

### 8.4 验证记录

- 账本集成 15 项、框架归档 5 项、只读 API 3 项及附件展开 7 项全部通过，共 30 项相关测试，其中本批新增 24 项。包含真实 MyBatis/H2 的重复提交、并发 seq、冲突回滚、父子投影来源、员工隔离、双向字节窗口及搜索通配符转义；现有 ChatPersistence/DDD 架构检查一并通过。
- 真实 Harness 使用受控模型及内存状态完成输入到最终回复、人工确认后恢复、附件展开后的归档流程。后两项分别证明状态变化不再阻断续跑，以及模型看到附件正文但 PG 保留原始用户文本；不调用记忆模型、外部付费模型或沙箱。H2 已迁移至 V37。
- 10 万条容量来源通过真实 H2/MyBatis 生成并读取指定尾部/前向窗口；夹具使用实际 Msg 编码与摘要函数，没有加载全部历史到应用端作为分页结果。此项不是 PostgreSQL 生产延迟基准。
- 完整功能 reactor 回归通过：`mvn -o -pl agentscope-saas/agentscope-saas-app -am -Djacoco.skip=true '-Dtest=*,!AgentPerformanceTest' -Dsurefire.failIfNoSpecifiedTests=false test -q`。已有 `AgentPerformanceTest` 独立排除，未修复或计为性能验收通过；可选外部测试的条件跳过也不算真实依赖验证。
- 首次定向验证发现 Map 容器序列化会遗漏多态内容标识，已改用框架 Msg 编码并通过往返验证。首次真实门禁发现 HITL 修改工具调用状态导致同 ID 内容冲突，已限定归一化工具调用状态并复测成功；参数变更仍产生不同摘要。另补齐附件展开的投影标记，避免同类误判。
- 真实 PG 已从 V34 迁移至 V37；核对新增两表的 `ENABLE/FORCE RLS`、组织策略及应用角色 CRUD 授权均生效。使用本地 PG/Redis/MinIO/OpenSandbox 及受控 scripted 模型执行 `start-opensandbox-local.sh --smoke`：用户流程 **13/13**、企业发布诊断 **8/8** 通过，覆盖登录、HTTP multipart 上传/下载、HITL、沙箱命令执行、快照恢复、SSE 最终返回、释放后文件下载和后端资源回收。
- 新增真实归档门禁在后端沙箱释放后读取前向/最新/向后窗口，检查 seq 顺序、USER/ASSISTANT/TOOL 来源及另一员工返回 404；检查已并入共享 smoke 脚本，可用 `SANDBOX_SMOKE_RUNTIME_ARCHIVE=false` 对旧版本部署显式跳过。隔离测试账号先登录、首次才注册，避免重复运行的账号冲突。
- 修改后的共享脚本重复执行也通过 **13/13 + 8/8**，验证账号可复用，归档及资源释放未依赖首次注册。格式检查及 `bash -n` 通过。smoke 正常关闭应用，不把其退出清理时 Maven 子进程的终止日志当作门禁失败。
- 验证结束确认应用端口已关闭，将本轮从停止状态拉起的 PG/Redis/MinIO/OpenSandbox 四个容器恢复为停止状态；未删除镜像、容器或数据卷，也未关闭 Docker Desktop 或干预其他容器。
- 本批未执行浏览器点击型全量 UI 验收、企业私有模型质量/中断测试或 CubeSandbox/E2B 测试；前端入口 HTTP 可达与 multipart API 模拟不等于页面交互验收。受控模型的辅助记忆调用可能无文本返回，本门禁不作为记忆提炼质量证据。

### 8.5 明确仍未完成

1. 文件式提炼/整合仍是同步链路；独立后台预算、任务 PG/Outbox 提交、页级持久结果、可恢复水位及确定性记忆发布仍待实施。
2. 本批新消息正文有界地内联 PG；大工具正文的不可变 MinIO 引用、流式对象传输、断点下载和冷归档导出尚未完成。原有 JSONL 不自动导入或删除，不能宣称旧历史已经全部可从新接口读取。
3. Checkpoint 仍保留现有状态快照；尚未改为只保存 archive cursor/摘要/窗口/文件清单，也未完成工具 Journal、消息提交和 checkpoint 的完整故障注入矩阵。框架级提交顺序及正常/异常/取消的退出窗口已在第 9 节补齐；强杀、数据库中断及工具结果从 Journal 补建等仍不能称为全部可精确恢复。
4. 主流去重依赖稳定源消息 ID；SDK/旧路径重建新 ID 的消息不会按文本擅自合并。搜索当前为组织/员工/Agent 约束下的 SQL 子串匹配及有界预览，全文索引与跨海量历史的生产查询优化仍待实测。
5. session reset 与写入用 session 锁串行，但尚未增加运行代际撤销；用户在活动 Run 中 reset 的迟到写入/恢复行为仍需要与取消和代际 fencing 一并治理，不宣称已解决该竞态。
6. 后台记忆队列不能仅凭本批部分来源能力就启用；B2 产物发布、B3 重试、B4 记忆治理、B5 验收器、B6-B8，以及旧性能门禁仍依原依赖推进。

## 9. 第六批：关键消息提交与退出边界

### 9.1 修复的问题

上一批 `onReasoning` 提交下一次推理输入，但 Core 在工具结果加入上下文后会立即创建 ContextCheckpoint。这两点之间存在“恢复点已经引用结果，消息账本还未提交”的窗口；如果此时 Checkpoint 失败、工具后置处理异常或运行被取消，仅成功结束时的追加无法覆盖最后的已完成消息。

另外，Core 的运行流和工具事件流使用内部订阅转接事件，却没有把转接订阅绑定到外层取消。取消外层运行不保证模型/工具订阅停止，可能在沙箱释放后继续运行。本批修复实际运行流的订阅所有权，不改变 durable Worker 与网页 SSE 订阅解耦的产品规则：网页断开不等于取消后台任务。

### 9.2 实现边界

- 新增 Core 中立端口 `ConversationCommitter`。Harness 的 `SessionArchiveMiddleware` 将其绑定到既有 `SessionArchiveStore`；不引入 SaaS 租户、Spring、SQL 或新的模型客户端。SaaS 仍经应用服务短事务和 DAL MyBatis 提交，不新增数据库迁移，不修改 JWT。
- 工具结果完成已有 Journal 终态提交、经过后置处理并加入上下文后，Core 先提交当前消息窗口，再保存 ContextCheckpoint。没有配置 Checkpoint 时也提交完成的工具消息。归档失败抛错，不允许用未入账消息继续推理或创建恢复点。
- Core 保存最终 AgentState 前提交消息，失败不写状态快照，也不发出成功 `AgentResultEvent`。未配置端口的独立 Core/Harness 调用方保留原存储方式。
- 在本次调用成功、错误或取消的信号进入同会话序列化锁的释放逻辑前，冻结一个只读消息列表。正常/异常补交和资源清理使用该退出窗口，不在收尾阶段重新读取可能已被后续调用改变的工作列表。新调用激活时清除旧窗口；窗口只在 RuntimeContext 中，未把它写成第二套数据库历史。
- 运行转接和工具转接采用事先注册的 `BaseSubscriber`，由 `FluxSink.onCancel` 持有；取消会传递到模型、工具及已有 Journal 取消处理。收到开始事件就取消时，不再启动模型。写工具的取消结果沿用 `OUTCOME_UNKNOWN`，不能假定外部副作用已回滚或盲目重放。
- `SessionArchiveMiddleware` 在异步错误和同步 `next` 抛错时等待补交退出窗口，再传递原错误；补交失败改为关键归档错误，并将原错误保留为 suppressed 信息。初始输入提交失败时，不安装屏障、不启动下一段运行。
- Harness 的 call、streamEvents 及兼容流的资源清理先提交退出窗口，再执行沙箱投影/持久化/释放。释放位于 `finally`，因此数据库失败不能造成沙箱泄漏。已取消的流无法再向客户端发送收尾错误；此时记录不含正文的关键提交失败日志，继续清理，不能将其记成已成功持久化。
- 没有把记忆维护改成不受控后台 `.subscribe()`，也没有启用文件式记忆后台计算。本批处理实际运行和工具订阅，legacy chunk hook 的最佳努力机制未整体替换。

### 9.3 验证记录

- 本批新增 **18 项**测试通过：Core 8 项、归档中间件 4 项、Harness 资源清理 4 项、MyBatis/H2 归档集成 2 项。已有 ReAct 新循环、审批恢复、附件展开和分页归档回归保持通过。
- 故障顺序覆盖：Journal → 消息 → Checkpoint → 最终消息 → 状态；Journal 已成功但归档失败时不创建 Checkpoint、不继续模型、不写状态；Checkpoint 失败时已提交工具消息仍存在，测试中的工具副作用只执行一次；最终归档失败时不发成功结果。
- 取消覆盖：开始事件取消不调用模型；模型运行中取消传到模型并释放同会话执行锁；工具执行中取消传到工具，并记录未知写入结果；冻结窗口不会读到之后追加的消息；模拟沙箱生命周期验证正常、异常和取消时的提交/释放顺序，以及归档失败仍释放资源。
- 两项真实 MyBatis/H2 测试在 Checkpoint 回调中直接读取已提交工具来源，分别验证成功和故障路径。Journal/Checkpoint 的故障由受控端口注入，不冒充生产 PostgreSQL 服务中断演练。
- 完整功能 reactor 回归返回 0：`mvn -o -pl agentscope-saas/agentscope-saas-app -am -Djacoco.skip=true '-Dtest=*,!AgentPerformanceTest' -Dsurefire.failIfNoSpecifiedTests=false test -q`。旧性能测试仍明确排除，不计为通过；Spotless 与 `git diff --check` 通过。
- 真实 PG/Redis/MinIO/OpenSandbox 配合 scripted 模型执行本地门禁，用户流程 **13/13**、企业诊断 **8/8** 通过，覆盖上传下载、人工确认、快照恢复、沙箱执行、最终 SSE、释放后文件读取、运行账本双向分页和员工隔离。日志未出现关键归档错误。本批未使用企业私有模型或付费模型，未执行浏览器点击型全量验收、CubeSandbox 或 E2B 验证。
- 验证后确认应用端口关闭，并将本轮启动前均为停止状态的四个依赖容器恢复为停止状态；镜像、容器和数据卷保留，未关闭 Docker Desktop。

### 9.4 仍需推进

1. 文件式记忆后台提炼/整合仍需独立预算、持久任务/Outbox、页级结果、水位及确定性发布。Mem0 投影队列不等于文件记忆计算后台化。
2. 大消息正文 MinIO 不可变引用、流式传输、旧历史导入/冷导出以及只保存 cursor/摘要/窗口的轻量 Checkpoint 尚未完成。
3. 本批不处理进程强杀后的未提交尾部、数据库不可用时的可靠待补交记录、从工具 Journal 重建稳定消息 ID，以及完整的租约/代际故障矩阵。取消只能取消订阅，不保证阻塞的外部 SDK 或已经发出的副作用立即停止。
4. session reset/delete 与活动 Run 的代际撤销仍待治理；冻结退出窗口不替代跨实例 fencing。GracefulShutdown/手工状态保存等旁路也未全部改为统一提交屏障。
5. 当前端口继续提交有界工作窗口并按稳定 ID 去重；批量身份查询优化、生产 PG 延迟和旧性能门禁仍需单独验证。B2-B8 剩余事项保持文档 37 的依赖顺序。

## 10. 第七批：大正文分离、预览读取和孤立回收

### 10.1 存储和提交协议

- 新增 V38（PG/H2）。`runtime_messages` 继续保存稳定消息 ID、顺序、来源、语义摘要和原始字节数；超过阈值的新正文改为 `body_id` 引用加有界预览。`runtime_message_bodies` 保存不可变对象定位、所有权、精确字节摘要、容量和发布/回收状态。Domain 只定义端口，DAL 使用 MyBatis，应用层负责可信身份与事务；没有在业务服务中新增 JDBC。
- 复用部署配置的 `FileObjectStore`。生产 MinIO 对象位于 `runtime-transcripts/{org}/{user}/{agent}/{rootSession}/{UUID}.json`，与上传/生成文件目录和记忆投影隔离；不登记到用户文件目录，不通过 API 返回内部对象键。PG BYTEA 仅作为既有本地/测试后端，不把它称为生产大对象存储。
- 顺序为短事务预留 `STAGED` → 事务外上传并有界读回校验 → 条件发布 `READY` → 消息/游标事务关联引用。先锁组织容量记录，再验证所属根会话并预留容量；所有远程 PUT/GET/DELETE 均不跨消息数据库锁。不宣称 PG/MinIO 跨系统原子提交或通用 exactly-once。
- 引用关联必须在消息事务中更新正文的可回收时刻。GC 使用领取令牌和租约，提交领取后再次检查引用，防止等待中的旧 MVCC 查询误删刚发布对象；旧令牌不能确认新领取。暂存未发布、消息事务回滚及并发重复提交产生的无引用对象可回收；父子 Agent 在同一所属根会话内共享正文引用，不重复上传。
- 回收后保留 `DELETED` 墓碑并每日重检，处理“超时的 PUT 在 DELETE 后才完成”的孤立字节。正文元数据刻意不随所属 session/Agent/员工/组织级联删除，物理清理仍可由管理通道完成。墓碑元数据保留/最终清除策略尚未交付。
- 大正文使用独立员工/组织容量上限，`STAGED/READY/DELETING` 均计入；失败暂存也占额度，直到确认回收。组织容量锁串行化预留，避免不同员工同时突破组织额度。这不是用户文件额度，也不覆盖旧内联消息等所有企业存储。
- 重复提交先批量查消息身份，避免每条一次身份查询和已入账正文重复 PUT。事务提交时再次查身份并校验冲突；准入后已知消息被 reset 清理时明确拒绝，不把未准备的大正文偷偷内联。该检查不能替代完整的运行代际撤销。

### 10.2 读取与检索边界

- 运行恢复窗口及 API 默认 `includeContent=true` 在所属关系校验后读取完整来源；正文长度、精确 SHA-256、Msg 语义摘要、ID/role 均校验。对象缺失、损坏、状态不允许或后端不匹配时明确失败，绝不把预览作为完整来源继续规划。
- `GET /api/agents/{agentId}/sessions/{sessionId}/runtime-messages?includeContent=false` 对 offloaded 消息只读取 PG 预览，不访问对象存储，保留 seq/ID/role，并标注 `archiveBodyOffloaded`、`archiveContentBytes`。现有 seq 游标、员工隔离及窗口字节预算继续有效；预算仍按原正文大小计算，预览并非无限容量历史列表。
- MinIO 新增有界读取，最多读取预算加一个字节并关闭流；PG 通过 SQL 同时验证元数据大小和 `OCTET_LENGTH(data)` 后才返回 BYTEA。单正文上限仍为 32 MiB，窗口上限仍受配置约束。Msg 编码、摘要和完整读取仍使用有界 String/byte[]，没有宣称已完成全链路流式序列化、Range 或断点下载。
- `session_search` 仅扫描数据库已存 payload；新大正文是有界预览，**不保证检索到正文后段的关键词**。工具结果显式说明“预览检索、无匹配不代表完整历史不存在”。本批未创建正文全文索引，也不通过遍历整个 MinIO 伪装为可扩展搜索。原有内联 payload 仍按既有 SQL 子串方式查找。
- 新 Mapper 和对象 BYTEA Mapper 默认 INFO，避免 local 包级 DEBUG 把运行正文写进 SQL 参数日志。没有增加新的前端轨迹页面或替换问答 UI。

### 10.3 配置与部署

```yaml
saas:
  runtime-archive:
    large-bodies-enabled: true
    inline-max-bytes: 65536
    body-grace-seconds: 3600
    body-gc-enabled: true
    body-gc-fixed-delay-seconds: 300
    body-gc-batch-size: 100
    body-gc-max-attempts: 10
    max-body-user-bytes: 5368709120
    max-body-org-bytes: 107374182400
```

环境变量对应 `SAAS_RUNTIME_ARCHIVE_LARGE_BODIES_ENABLED`、`SAAS_RUNTIME_ARCHIVE_INLINE_MAX_BYTES`、`SAAS_RUNTIME_ARCHIVE_BODY_GRACE_SECONDS`、`SAAS_RUNTIME_ARCHIVE_BODY_GC_ENABLED`、`SAAS_RUNTIME_ARCHIVE_BODY_GC_DELAY_SECONDS`、`SAAS_RUNTIME_ARCHIVE_BODY_GC_BATCH_SIZE`、`SAAS_RUNTIME_ARCHIVE_BODY_GC_MAX_ATTEMPTS`、`SAAS_RUNTIME_ARCHIVE_BODY_MAX_USER_BYTES`、`SAAS_RUNTIME_ARCHIVE_BODY_MAX_ORG_BYTES`。非法容量关系和回收参数在装配时拒绝。

部署先迁移 V38。开关只影响之后是否产生对象正文；关闭开关不会删除、迁回或阻止读取已有对象。数据库备份须与对应对象桶共同保留；更换文件存储后端不自动迁移已有正文，后端不匹配会明确失败，不能盲目改配置后宣称历史可恢复。

### 10.4 验证记录

- 正文协议 14 项、归档 17 项、只读 API 4 项、搜索覆盖边界 2 项均通过，共 37 项相关回归；本批新增 17 项。包含真实 MyBatis/H2 元数据事务和真实本地 MinIO 的 50 万字符正文往返/回收、PG BYTEA 与 MinIO 超预算读取拒绝。DDD/MyBatis Repository 集成及 11 项数据访问架构检查一并通过。
- 完整功能 reactor 返回 0：`SAAS_RUNTIME_BODY_MINIO_TEST=true mvn -o -pl agentscope-saas/agentscope-saas-app -am -Djacoco.skip=true '-Dtest=*,!AgentPerformanceTest' -Dsurefire.failIfNoSpecifiedTests=false test -q`。已有 `AgentPerformanceTest` 仍排除，未修复或计为性能门禁通过；其它条件性外部测试的跳过不算真实依赖验收。
- 首次定向测试发现测试夹具对 Spring 代理直接使用 Mockito spy 无法正确解包，已改为委托 mock 注入消息事务故障；10 万条历史夹具生成遇到测试默认 SQL 超时，为这一条造数语句单独设置有界 120 秒，未放宽生产查询/提交超时或减少容量测试数据，完整回归最终通过。
- 真实 PG 已迁移 V38；核对新表 `ENABLE/FORCE RLS` 和应用角色 CRUD 授权。只读事务中切换到 `app` 角色，确认本组织正文可见、另一组织不可见；不以管理角色绕过隔离的查询冒充 RLS 验证。
- 真实 PG/Redis/MinIO/OpenSandbox 配合 scripted 模型执行本地发布门禁，用户流程 **13/13**、企业诊断 **8/8** 通过。临时设置 `SAAS_RUNTIME_ARCHIVE_INLINE_MAX_BYTES=256` 和 `SANDBOX_SMOKE_REQUIRE_OFFLOAD=true`，强制验证真实对象分离；沙箱资源释放后，从 API 完整还原 **4 条** MinIO 正文，预览保留相同 seq/ID/role、带分离标记且不返回对象键，另一员工访问返回 404。标准门禁继续覆盖 HTTP 登录、multipart 上传/下载、HITL、快照恢复、实际沙箱命令、最终 SSE 及后端资源回收。
- 格式检查、`bash -n` 和 `git diff --check` 通过。smoke 自动关闭应用，应用端口确认已关闭；其清理时 Maven 子进程终止产生的 `BUILD FAILURE` 不等于根脚本失败，根脚本退出 0，门禁两组均 `FAIL=0`。
- 本批未修改前端页面，也未进行浏览器点击型全量 UI 验收、企业模型提炼质量/真实断流或 CubeSandbox/E2B 验证。受控模型的辅助记忆调用无文本返回不作为记忆质量通过证据。GC 故障/令牌竞态由真实 MyBatis/H2 加可控对象端口验证，真实 MinIO 覆盖往返、容量拒绝及删除；不将这些结果描述为完整的生产 PG 故障注入矩阵。

本批命令与日志：

```bash
SAAS_RUNTIME_BODY_MINIO_TEST=true mvn -o \
  -pl agentscope-saas/agentscope-saas-app -am -Djacoco.skip=true \
  '-Dtest=*,!AgentPerformanceTest' -Dsurefire.failIfNoSpecifiedTests=false test -q
APP_PORT=18082 START_DOCKER_DEPS=false START_DOCKER_DAEMON=false \
  SAAS_RUNTIME_ARCHIVE_INLINE_MAX_BYTES=256 SANDBOX_SMOKE_REQUIRE_OFFLOAD=true \
  bash agentscope-saas/agentscope-saas-app/scripts/start-opensandbox-local.sh --smoke
```

日志：`/private/tmp/chugou-runtime-body-tests.log`、`/private/tmp/chugou-runtime-body-functional-tests.log`、`/private/tmp/chugou-runtime-body-opensandbox-gate.log`。验证用阈值只作用于这次进程，默认部署值仍为 64 KiB。

### 10.5 仍需推进

1. 文件式记忆提炼/整合后台任务、页级结果、水位及确定性记忆发布仍未完成，Mem0 投影不代替此能力。
2. 轻量 Checkpoint（cursor/摘要/有界窗口）、旧 JSONL/内联正文迁移、冷归档导出、全文索引、流式 Msg 编码和 Range 下载仍未完成；本批不改全部已有状态快照。
3. 完整 reset/delete 运行代际 fencing、工具 Journal 补建稳定消息 ID、强杀与数据库中断恢复矩阵、生产 PG 延迟和旧性能门禁仍需验证。
4. 正文 GC 有界领取/尝试，但尚未交付整轮扫描总 deadline、达到最大尝试后的管理端重放、墓碑保留/清除政策和跨后端迁移工具；不能将永久失败的清理记录视为已删除。对象 SDK 的阻塞和已发出 PUT 仍受既有客户端行为限制。
5. B2-B8 的其它事项继续按文档 37 的依赖顺序推进。JWT 和企业身份/内部模型建设保持用户要求的暂缓范围。

## 11. 第八批：轻量检查点与工作窗口精确还原

### 11.1 为什么不能只按原消息 ID 恢复

运行账本保存规范化来源，模型工作窗口可能包含附件展开、压缩/驱逐后的工具结果和更新后的工具状态。直接从来源重建会偷换工作视图。原检查点还固定只保存末尾 20 条；它不是完整工作窗口，也未证明能覆盖当前模型需要的上下文。本批改造检查点数据表达和窗口选择，不修改模型上下文压缩阈值。

### 11.2 实现与提交边界

- 增加版本 2 清单，包含已认证组织/员工/Agent/根 session、归档流 ID/水位、工作窗口指纹、顺序项和正文引用。小项保留 inline Msg；大项只保留 Msg 头部及不可变正文引用。summary、facts/workspace 版本和待处理操作继续使用既有检查点字段。
- 只有归档正文的精确字节摘要与当前规范化 payload 一致时才复用；工具状态变化或附件展开不同则保存独立工作快照。metadata、timestamp、usage 等工作头部独立保留。不是按语义摘要相等就用原始来源替代，不重复上传已经一致的正文。
- Core 新增检查点端口的 `retainedWindow` 默认方法，独立 Core 保留旧 20 条行为；SaaS 启用轻量模式后接收完整的**当前工作窗口**，不读取全部运行历史。应用端限制最多 500 条、完整 Msg 总量不超过配置的 `max-window-bytes`；超限拒绝，不静默丢掉头部。关闭轻量模式的新写入保留旧尾部选择和有界内联格式。
- 补齐人工确认/待处理工具续跑的执行快照：新调用直接进入 acting 时，没有上一调用的 `StepSnapshot`，原逻辑会跳过工具后的检查点。续跑现在先捕获当前工具/权限/身份的独立执行边界，再调用工具；不额外调用模型，也不绕过当前安全检查。Journal → 消息 → 检查点顺序在这一分支同样生效。
- 准备只读取消息身份、摘要、大小和 body ID；SQL 明确不返回旧内联 payload。普通来源必须已由消息提交屏障入账，系统/压缩摘要等非来源项可单独保存。来源缺失或冲突时不创建检查点。
- 先短事务校验当前 attempt 租约，再在事务外准备和校验必要的工作快照；最终短管理事务锁所属根 session、重验 attempt 租约、更新正文可回收时刻、插入检查点并登记引用。所有引用与检查点原子提交；引用登记失败全部回滚。外部对象成功但 SQL 失败时留下可回收的暂存对象，不宣称跨存储事务。
- 新增 V39 `context_checkpoint_body_refs`（PG/H2），带组织 RLS、正文反向索引和检查点级联删除。GC 领取条件和领取后复查同时计算消息引用与检查点引用；消息账本清理不再让仍被检查点持有的正文被误删。所有正文对象仍使用隔离命名空间和既有独立容量预留。
- 恢复在检查点查询事务完成后读取对象；验证所属 Run/session/员工、正文长度和 SHA-256、消息 ID/role 以及整个还原窗口指纹。缺失、损坏或错绑定时明确失败，不使用预览、旧检查点或另一后端替代。
- 检查点的水位是已提交来源锚点，并不代表只靠一个 seq 区间就能还原经过变换的工作视图。没有把所有 AgentState、工作区快照或工具 Journal 改成仅有游标。
- 修复原有 H2 检查点 JSON 二次编码：写入复用 DAL `JsonTypeHandler`，不引入业务 JDBC；读取兼容旧 Msg 数组及旧 H2 单层 JSON 字符串包装。Checkpoint Mapper 默认 INFO，避免私有工作上下文出现在 SQL 参数 DEBUG 日志中。

### 11.3 配置与部署

```yaml
saas:
  runtime-archive:
    lightweight-checkpoints-enabled: true
    checkpoint-inline-max-bytes: 65536
    checkpoint-max-json-bytes: 1048576
```

对应 `SAAS_RUNTIME_ARCHIVE_LIGHTWEIGHT_CHECKPOINTS_ENABLED`、`SAAS_RUNTIME_ARCHIVE_CHECKPOINT_INLINE_MAX_BYTES`、`SAAS_RUNTIME_ARCHIVE_CHECKPOINT_MAX_JSON_BYTES`。inline 阈值不得超过单条正文限制；清单、summary 和待处理操作的 JSON 采用独立总预算，默认 1 MiB。完整工作窗口采用已有窗口字节上限，默认 32 MiB。两种上限都不是模型 token 窗口，不放宽模型准入。

先迁移 V39；滚动升级阶段显式设置轻量开关为 `false`，升级所有回收器和读取实例后再启用新写入，避免旧实例忽略检查点引用误删对象。关闭轻量开关只改变后续写入/窗口选择；已有版本 2 仍须保留读取器和对象存储。来源大正文开关与检查点开关独立，关闭来源 offload 不阻止检查点独立工作快照。数据库备份须包含引用表并与对象桶共同保留。

### 11.4 验证记录

- 本批新增 **16 项**测试全部通过：14 项应用层真实 MyBatis/H2 集成及 2 项 Core 窗口/确认续跑测试。覆盖重复检查点不重新 PUT/GET 已归档正文、metadata 保留、附件展开工作视图、工具批准状态、正文 pin/解除 pin 与 GC、来源未提交、过期租约、引用登记回滚、缺失正文、员工隔离、旧数组与旧 H2 包装格式、篡改头部指纹拒绝、JSON 容量及开关兼容。Core 与 SaaS Port 均验证超过 20 条时保留整个工作窗口，而不是只测试 DTO。续跑夹具采用与实际持久状态一致的 Agent 名称、原始 JSON 参数及 ConfirmResult；验证真实工具和 Journal 提交，且在检查点前没有模型调用。
- 完整功能 reactor 返回 0：`SAAS_RUNTIME_BODY_MINIO_TEST=true mvn -o -pl agentscope-saas/agentscope-saas-app -am -Djacoco.skip=true '-Dtest=*,!AgentPerformanceTest' -Dsurefire.failIfNoSpecifiedTests=false test -q`。新窗口扩展完成后同步重建 Core/SaaS 再运行最终回归；不把接口升级期间旧 classpath 的编译失败算作通过。旧性能测试仍排除，未修复或计为性能门禁通过。
- 既有正文协议 14 项（含真实 MinIO）、PG 账本分页、租约 Journal、治理及 DDD/MyBatis 架构检查一并通过。引用失败和对象缺失由可控存储端口注入，事务和外键是真实 H2/MyBatis；不能冒充生产 PG 服务中断或强杀演练。Spotless 与 `git diff --check` 通过。
- 首轮与重复 OpenSandbox 门禁均通过 13/13 + 8/8，但运行期间观测未看到检查点。这一证据暴露了确认续跑缺少执行快照的分支问题，不能据正常门禁通过就宣称检查点已接线。修复后完整功能 reactor 再次返回 0；最终真实门禁仍为 **13/13 + 8/8**，运行期间只读 PG 观测确认 **1 个版本 2 检查点、3 条正文引用、归档水位 4** 实际落库。测试 Agent 删除后这些检查点及引用随既有 Run 关系清理，不把结束后的空表误当未执行。
- 最终门禁使用真实 PG/Redis/MinIO/OpenSandbox 和 scripted 模型，临时将来源及检查点 inline 阈值设为 256 字节，覆盖登录、HTTP multipart 上传/下载、HITL 确认、沙箱执行、快照恢复、最终 SSE、释放后完整/预览历史读取和资源回收。PG 已迁移 V39，新引用表的 ENABLE/FORCE RLS 和应用角色 CRUD 授权已核对；不把管理角色的正向查询称为普通员工授权验证。
- 未进行浏览器点击型全量验收、企业私有模型/记忆质量或 CubeSandbox/E2B 验证，也不将此门禁等同强杀后的检查点恢复演练。应用端口确认已关闭，正常清理的 Maven 143 日志不是根门禁失败；根脚本退出 0，两组 FAIL 均为 0。
- 验证结束将本轮从停止状态启动的 PG/Redis/MinIO/OpenSandbox 四个容器恢复为停止；镜像、容器和数据卷保留，未关闭 Docker Desktop 或干预其他容器。本批未提交、推送代码。

最终命令与日志：

```bash
SAAS_RUNTIME_BODY_MINIO_TEST=true mvn -o \
  -pl agentscope-saas/agentscope-saas-app -am -Djacoco.skip=true \
  '-Dtest=*,!AgentPerformanceTest' -Dsurefire.failIfNoSpecifiedTests=false test -q
APP_PORT=18082 START_DOCKER_DEPS=false START_DOCKER_DAEMON=false \
  SAAS_RUNTIME_ARCHIVE_INLINE_MAX_BYTES=256 \
  SAAS_RUNTIME_ARCHIVE_CHECKPOINT_INLINE_MAX_BYTES=256 \
  SANDBOX_SMOKE_REQUIRE_OFFLOAD=true \
  bash agentscope-saas/agentscope-saas-app/scripts/start-opensandbox-local.sh --smoke
```

日志：`/private/tmp/chugou-lightweight-checkpoint-functional-tests-final.log`、`/private/tmp/chugou-checkpoint-confirmation-core-tests.log`、`/private/tmp/chugou-lightweight-checkpoint-opensandbox-gate-final.log`。测试阈值只作用于该进程，未改为部署默认值。

### 11.5 仍需推进

1. 文件式记忆后台提炼/整合、页级结果和水位、岗位及个人记忆治理仍待实施；本批不改变同步记忆维护。
2. session reset/delete 与活动 Run 的代际撤销尚未完成。检查点引用保留字节不等于授权旧运行继续执行；后续需要明确废止检查点并取消旧代际。当前不宣称 reset 并发恢复已解决。
3. 最终 AgentState 仍按既有方式保存，旧检查点不自动迁移/删除；轻量化全状态、检查点版本保留/清除、工具 Journal 补建稳定消息 ID、强杀/数据库中断矩阵和生产性能基准仍待完成。
4. 快照编码/恢复仍是有界 String/byte[]，不是完整的流式 Msg 序列化；全文检索、Range/冷导出和前述 GC 终态管理边界仍保留。
5. B2-B8 剩余依赖不因本批端口和测试通过而视为全部完成。JWT、企业身份和内部模型部署保持暂缓。
6. 检查点的 500 条/窗口字节/清单 JSON 上限是独立写入资源预算；模型能够接受的上下文不保证落在这些预算内。当前超限明确失败，按模型与持久化预算统一预压缩、清单总量自适应 offload 及生产延迟优化仍需后续实施，不能称为任意规模工作窗口都能保存。

## 12. 第九批：检查点清单总量自适应转存

### 12.1 修复范围与算法

本批解决多条消息均低于单项 inline 阈值、但清单总量超过 JSON 预算时的可避免失败。使用原版本 2 清单和 V39 引用关系，不新增数据库表，不修改模型 token 压缩策略，也不把全部上下文截成末尾 20 条。

1. 保持单条正文、完整工作窗口和 500 条上限。逐项验证来源已提交，先按现有 inline 阈值规划清单；不在预算检查前上传对象。
2. 清单使用实际 UTF-8 JSON 字节计量，包含消息 JSON 嵌套为字符串的转义开销。预算已由服务扣除 summary 和待处理操作的 JSON 大小，不能让它们成为预算之外的额外字段。
3. 清单超限时，为仍内联的项计算转存后的**净节省字节数**；仅保留正收益候选。按净节省从大到小、同收益按原位置排序，选取满足预算的必要项；替换不改变窗口顺序、消息内容、metadata 或完整窗口指纹。不在每次替换后重复序列化整个窗口。
4. 再次序列化最终规划清单校验预算。仍超限的不可缩减头部等明确返回 `CHECKPOINT_JSON_BYTE_LIMIT`，此时没有 PUT 或暂存正文。不会隐式删除工作 metadata，也不会放大配置限额。
5. 为选中的正文查找已归档且字节摘要一致的所属 READY 对象；精确复用不产生额外 PUT/GET。必要的新对象在 SQL 事务外暂存与校验，最终引用登记、租约复查和检查点插入仍采用原有原子提交边界。登记失败的孤立对象交由既有 GC 回收。

恢复端另补两项检查：内联项的真实 JSON 字节必须等于声明长度；引用正文与 Msg 头部合并后的完整窗口同样受 `max-window-bytes` 限制，不能只累计正文而漏算头部。另外，只读 PG 验证确认 `JSONB::text` 会增加格式空格，读取改为先限制输入大小（JSON 预算的两倍），再按同一 typed 清单规范化计量，避免边界清单因数据库排版被误拒绝；规范化后超预算仍拒绝。不改变旧数组、H2 包装文档及版本 2 清单的格式兼容性。

### 12.2 配置与部署

继续使用 `lightweight-checkpoints-enabled`、`checkpoint-inline-max-bytes`、`checkpoint-max-json-bytes` 和 `max-window-bytes`，默认值不变。inline 阈值是初始单项策略，不再代表低于该值的消息一定保留内联；JSON 总预算不足时仍可转存。

不增加按用户选择的模式，不绑定具体沙箱厂商。DDL 和存储端口保持不变，DDD/MyBatis 边界不变；部署前仍须遵循上一批的 V39 全读取器/回收器升级顺序。来源大正文开关独立：即使来源内联保存，工作检查点也可按总预算保存必要的正文快照。

### 12.3 验证进度

- 本批新增 **11 项**集成测试，最终检查点集成 **25/25**、Core 提交屏障 **10/10**，错误/失败/跳过均为 0。新增覆盖总量超限的小消息转存、精确来源复用且零额外 PUT/GET、无需转存时零 PUT、最大净收益选择与窗口顺序、UTF-8/转义 JSON 精确边界、不可缩减头部拒绝且零 PUT、summary/待处理操作共享预算、恢复合并头部限额、伪造 inline 长度拒绝、自适应对象的引用回滚与 GC、JSONB 格式化兼容且真实规范化超限仍拒绝。
- 首轮全量运行在补入 JSONB 测试后被 Spotless 阻止，没有计为通过。格式化后重新从完整 reactor 起点执行最终命令并返回 0：`SAAS_RUNTIME_BODY_MINIO_TEST=true mvn -o -pl agentscope-saas/agentscope-saas-app -am -Djacoco.skip=true '-Dtest=*,!AgentPerformanceTest' -Dsurefire.failIfNoSpecifiedTests=false test -q`。包括真实 MinIO 正文测试及 DDD/MyBatis 架构检查；旧性能测试继续排除，不宣称生产性能门禁通过。
- 真实 PG/Redis/MinIO/OpenSandbox 门禁根脚本返回 **0**，用户流程 **13/13**、诊断门禁 **8/8**。单项 inline 为 64 KiB、清单 JSON 为 4 KiB、工具 stdout 约 8 KiB，验证 HTTP 登录/上传、HITL 确认、沙箱执行、SSE 输出、生成文件下载、长 session 分页、释放后完整/预览历史读取与员工隔离。采用 scripted 模型，不产生企业模型费用或将固定输出当作模型质量验收。
- scoped 只读 PG 观测在该测试员工的 Agent 清理前捕获版本 2 检查点：**3 项工作消息、1 项 offload、1 条持久正文引用、归档水位 4**，PG 存储文本 **2,530 字节**，项声明总量 **10,095 字节**、最大项 **8,557 字节**。所有单项低于 64 KiB，但窗口大于 4 KiB；只转存必要项后保存整个窗口，证明实际运行触发的是总量自适应分支。此收据不是强杀后恢复或生产延迟基准。测试前/后全库检查点计数均为 2；新增 Agent 清理会级联清理本轮检查点，不能仅凭清理后的空测试范围判定未执行。
- 最终 reactor 已包含 Spotless 检查；`git diff --check` 通过。应用脚本退出后确认 18082 无监听；验证结束恢复 PG/Redis/MinIO/OpenSandbox 四个容器的原停止状态，镜像、容器、卷和 Docker Desktop 保留。本批未提交或推送，也没有验证 CubeSandbox/E2B、浏览器点击型全量流程或企业模型/记忆质量。

真实门禁命令（阈值仅用于本次进程）：

```bash
APP_PORT=18082 START_DOCKER_DEPS=false START_DOCKER_DAEMON=false \
  SAAS_RUNTIME_ARCHIVE_INLINE_MAX_BYTES=256 \
  SAAS_RUNTIME_ARCHIVE_CHECKPOINT_INLINE_MAX_BYTES=65536 \
  SAAS_RUNTIME_ARCHIVE_CHECKPOINT_MAX_JSON_BYTES=4096 \
  SANDBOX_SMOKE_REQUIRE_OFFLOAD=true \
  SANDBOX_SMOKE_EMAIL=adaptive-checkpoint-20261007-budget@e2e.test \
  SANDBOX_SMOKE_COMMAND="grep -q '^browser-upload-' inputs/browser-upload.txt && mkdir -p outputs && printf '%s\n' opensandbox-enterprise-ok > outputs/opensandbox-report.txt && cat outputs/opensandbox-report.txt && printf '%8192s' 'checkpoint-padding'" \
  bash agentscope-saas/agentscope-saas-app/scripts/start-opensandbox-local.sh --smoke
```

日志：`/private/tmp/chugou-adaptive-checkpoint-focused-tests.log`（首轮 22/22 + 10/10）、`/private/tmp/chugou-adaptive-checkpoint-functional-tests-final.log`（最终 25/25 + 完整功能 reactor）、`/private/tmp/chugou-adaptive-checkpoint-opensandbox-gate.log`、`/private/tmp/chugou-adaptive-checkpoint-pg-receipt.log`。PG 收据通过临时只读、有 deadline 的观测脚本采集，没有新增业务直连 SQL；代码仍由领域仓储和既有 DAL/MyBatis 端口持久化。

### 12.4 剩余边界

本批只完成上一批 11.5 第 6 项中的**清单总量自适应 offload**，不代表任意规模工作窗口都能保存。完整窗口/单条字节数、500 条上限、不可缩减头部和 summary/操作列表、存储容量或可用性仍可能导致明确失败；模型与持久化预算统一预压缩、全状态轻量化、生产延迟基准仍待实施。

记忆后台提炼/整合及水位、session reset/delete 活动运行代际撤销、旧检查点保留/清理与迁移、Journal 修复稳定消息 ID、强杀/数据库中断矩阵、全文检索/流式导出及 GC 终态管理继续按 11.5 和文档 37 推进。JWT、企业身份与内部模型部署继续暂缓。本批不宣称浏览器全量验收或真实企业模型质量通过。

## 13. 第十批：会话重置/删除的执行代际边界

### 13.1 语义与数据模型

V40 为 `chat_sessions` 增加 `execution_generation`，为 `assistant_runs` 增加创建时的 `session_generation`，均为非负 BIGINT，旧数据从 0 起步。UI 和运行上下文的逻辑 session ID 不变。新 Run 在所属会话锁内创建并捕获当前代际；以这个提交顺序区分重置前后的执行，而不是通过进程内的 Agent 对象或请求时间猜测。

重置在同一主事务内锁会话、递增代际、取消非终态 Run/Task/AgentRun/attempt、清除 attempt 租约、废止上下文检查点及其正文引用，再清理聊天/运行消息并归零会话计数。已完成任务的终态审计保留，但旧代际幂等请求和续跑不再被视作有效执行。删除和 Agent 级删除先撤销，再沿既有关系清理聚合。失败时代际、撤销和历史清理全部回滚。

重置不是清空员工工作区、删除所有上传文件、清除个人长期记忆或撤销已经发生的外部副作用。文件/长期记忆的保留与删除仍是各自独立的治理流程；本批不宣称完整数据遗忘或副作用回滚。

### 13.2 运行入口与状态隔离

- 新 `SessionRunFenceService` 通过领域仓储读取已认证 org/user/agent/Run 的根会话代际。子 Agent 保留自己的逻辑 session key，同时绑定父 Run 的根会话；关闭 durable Run 的已持久化会话也可捕获代际，非 Run 子 Agent 必须继承并重验根绑定。
- `SessionGenerationMiddleware` 在 Core 激活状态之前设置通用 `AgentStateNamespace`。代际 0 继续读取旧存储键，后续代际采用独立物理状态键；旧调用即使晚写 Redis，也不覆盖新窗口。Core 的自动加载/保存、显式状态读写和权限快照恢复使用同一命名空间。持久化存储存在时丢弃被替代的本机缓存引用，不把旧缓存当作当前状态。
- 调用期间有界轮询所属执行代际。重置/删除或绑定失效以异常取消原始事件流，而非等下一次模型/工具 preflight 才发现。仅保留最新待检查 tick，避免 DB 查询慢时积累无界待处理项；查询异常不能被解释为仍允许继续执行。实际响应时间取决于轮询、DB 和客户端取消行为，不宣称已发出的远程工具动作能被撤回。
- 取消测试暴露 Reactor 的边界：伴随流直接报错虽会向用户传递异常，但未取消已经订阅的主流。实现改为先以正常撤销信号触发主流取消，再在取消完成的下游报告原始撤销/监测异常；不能仅根据网页收到错误就宣称模型/工具已停止。
- 真实门禁进一步暴露工具 Journal 取消回调的竞态：attempt 已被撤销时，写取消终态会失去租约，原回调将异常丢入 Reactor `onErrorDropped`。取消分支现在明确记录预期租约撤销，其他运行时提交失败保留警告并要求后续对账，不再向已经取消的订阅抛错；正常结果/失败的 Journal 提交仍保持原有失败语义，不把取消收尾处理扩展成忽略正常提交错误。
- 物理旧 Redis 键、全量 AgentState 编码以及长期闲置的状态/子任务缓存清理仍不是本批交付范围；不能把命名空间隔离称为完整存储回收或任意规模性能门禁。

### 13.3 提交与锁边界

1. 最终回复按所属会话/Run 的当前代际锁定后保存，旧 Run 不得借幂等回复查询恢复已清空的内容；不带 Run 的会话回复也检查入口捕获的代际。
2. PG 运行消息在入场及最终提交事务中锁根会话并重验代际。对象暂存仍在既有事务外；重置发生在传输期间时，最终发布拒绝，孤立正文走既有 GC。
3. 上下文检查点最终锁和读取均要求 Run 与会话代际相等，重置同时解除其正文 pin。旧 attempt 的租约被取消，不允许旧调用保存或恢复检查点。
4. 产物目录登记带入口捕获的根绑定。先进行只读代际预检查与内容传输，之后按“会话 → 文件”顺序重新锁定、读取当前版本/状态并复算容量，再提交元数据；新文件行延后至传输和代际检查之后创建，避免其 FK 提前持有会话锁。未变化的正文继续复用，旧投影删除同样要先通过代际锁。Run artifact/工作区 READY 发布也验证 Run/Task/attempt 与当前代际，不只检查前面的目录登记。
5. 组织/用户容量锁及正文配额锁在 PG 使用 `FOR NO KEY UPDATE`，既串行化容量写入又与引用其稳定主键的外键检查兼容；H2 保留 `FOR UPDATE`。数据库厂商识别仅在 MyBatis 基础设施内，SQL 留在 DAL，业务层仍只调用领域仓储。
6. 已读标记改为所属会话的窄字段更新，不再整行保存旧 Entity，避免覆盖重置后的计数/摘要或通过 UPSERT 复活删除的会话。

产物目录的既有组织/用户配额事务仍涵盖部分对象 IO；本批没有宣称完成 R02 的整个“短预留 → 事务外传输 → 短提交”协议。新增的会话/文件代际锁不跨传输；对象成功、目录提交被拒绝时，MinIO 可能留下未登记不可变对象，文件域完整补登/回收协议仍需后续推进。原始沙箱/工作区缓存中的已发生写入也不等同权威目录发布，重置不会把它们当作可回滚业务事务。

### 13.4 配置与升级

`saas.orchestration.session-fence-poll-millis` / `SAAS_ORCHESTRATION_SESSION_FENCE_POLL_MILLIS`，默认 1,000 ms，接受 100–60,000 ms。代际提交检查不因调大轮询间隔而关闭，轮询只影响正在进行的调用多久察觉撤销；不得用性能调节取消最终发布检查。

先迁移 V40，并升级全部 HTTP writer、Worker、检查点读取器及相关 DAL，维护期间停止新任务和重置操作。代际 0 兼容旧键不代表支持新旧写入实例长期混跑；旧实例不认识代际，无法提供此保证。升级不绑定 OpenSandbox 或新增用户侧沙箱选项，部署级 provider 配置保持原有方式。

### 13.5 验证进度

专项回归已返回 0：本批新增 **23 项**测试，包含 **15/15** 真实 MyBatis/H2 会话代际集成、**3 项**新增 Core 状态/权限命名空间测试（所在提交屏障套件 **13/13**）、**2/2** PG/H2 SQL 方言测试及 **3 项**新增工具 Journal 取消/提交测试（所在套件 **8/8**）。既有检查点 **25/25**、文件目录/投影、工作区 artifact、Run 服务和 DDD/MyBatis 架构检查一并通过。H2 的锁测试不是 PG 压测；SQL 方言测试检查生成语法，不冒充真实外键并发验证。

验证中修复子 Agent 策略省略时的真实空指针：原校验使用默认策略，但任务创建仍读取 `null`。现在校验及任务预算一致使用有效策略，父子撤销用例同时验证该默认入口。专项测试过程还修正可变缓存断言及 Java 17 的线程池关闭夹具；修复 Reactor 取消传播后才通过实际已订阅流和监测失败的取消断言，之前失败/编译中止的轮次均未计为通过。

新增可重复的 `scripts/session-generation-smoke.py`，通过 `SANDBOX_GATE_SESSION_GENERATION=true` 接入已有 OpenSandbox 企业门禁。基于真实 HTTP/SSE 与 scoped 只读 PG 观测：确认工具已进入 RUNNING 后重置会话，要求旧 Run 变为 CANCELLED、代际加一、检查点归零、流终止且不能成功结束；旧确认请求不能续跑，旧生成文件不能登记到目录；随后在相同逻辑 session 中完成新代际任务、下载并核对内容，最后删除会话并验证晚到的已读标记不能复活它。模型采用 scripted 测试服务，不产生企业模型费用，也不替代模型/记忆质量验收。

首轮基础流程 13/13，但新增脚本只识别 `RUN_ERROR`，未识别平台现有的 `CUSTOM/error`，因此根脚本失败；未计为通过。修正解析后仍严格禁止撤销流出现 `RUN_FINISHED`，第二轮根脚本退出 **0**，基础流程 **13/13**、代际用例 **4/4**、包含代际门禁的总诊断 **9/9**。该轮日志暴露上述 Journal 取消回调问题，修复后再次从完整 reactor 起点执行最终功能回归并返回 **0**，包含真实 MinIO 与 DDD/MyBatis 检查。仍排除原 `AgentPerformanceTest`，不宣称性能门禁通过。

最终真实 PG/Redis/MinIO/OpenSandbox 门禁再次返回 **0**，基础流程 **13/13**、代际用例 **4/4**、总诊断 **9/9**，所有 FAIL 为 0，未再观测到取消回调的 `onErrorDropped`。基础流程包含登录、上传、确认、执行、生成文件下载、历史读取及沙箱回收；代际用例验证实际执行中的重置和同 session 后续任务，而非仅创建 DTO 或在工具执行前拒绝。预期的旧确认拒绝及远程工具取消日志不代表正常任务失败，不据此忽略其他错误。取消和重置仍不提供远程副作用回滚保证。

验证后确认应用端口 18082 无监听，将本轮由停止状态启动的 `saas-pg`、`saas-redis`、`saas-minio`、`agentscope-opensandbox-server` 四容器恢复为 `exited`。镜像、容器、数据卷和 Docker Desktop 保留，未操作其他容器。Spotless、脚本语法检查及 `git diff --check` 通过；本批未提交、推送。

最终验证命令（阈值只作用于测试进程，不修改部署默认配置）：

```bash
SAAS_RUNTIME_BODY_MINIO_TEST=true mvn -o \
  -pl agentscope-saas/agentscope-saas-app -am -Djacoco.skip=true \
  '-Dtest=*,!AgentPerformanceTest' -Dsurefire.failIfNoSpecifiedTests=false test -q
APP_PORT=18082 START_DOCKER_DEPS=false START_DOCKER_DAEMON=false \
  SANDBOX_GATE_SESSION_GENERATION=true \
  SAAS_RUNTIME_ARCHIVE_INLINE_MAX_BYTES=256 \
  SAAS_RUNTIME_ARCHIVE_CHECKPOINT_INLINE_MAX_BYTES=65536 \
  SAAS_RUNTIME_ARCHIVE_CHECKPOINT_MAX_JSON_BYTES=4096 \
  SANDBOX_SMOKE_REQUIRE_OFFLOAD=true \
  bash agentscope-saas/agentscope-saas-app/scripts/start-opensandbox-local.sh --smoke
```

专项日志：`/private/tmp/chugou-session-generation-focused-tests-cancellation.log`、`/private/tmp/chugou-session-generation-cancel-core-tests.log`。最终完整功能 reactor 日志为 `/private/tmp/chugou-session-generation-functional-tests-final.log`；最终真实门禁日志为 `/private/tmp/chugou-session-generation-opensandbox-gate-verified.log`。第二轮日志 `/private/tmp/chugou-session-generation-opensandbox-gate-final.log` 保留取消回调问题的发现证据，不用它替代修复后的最终验收。不把错误注入的预期失败日志或清理时子 Maven 退出码代替根门禁退出结果。

### 13.6 剩余工作与验收边界

1. 文件式长期记忆的后台提炼/整合、页级结果与水位、企业维护的岗位信息和个人记忆治理仍待实施；本批的会话撤销不代表这些长期信息已按同一规则治理。
2. 完整 AgentState 的轻量化、历史检查点保留/清理与迁移、Redis 旧代际键清理、Journal 补建稳定消息 ID 仍需推进。
3. 文件域 R02 的完整短预留/事务外 IO/短发布及未登记对象回收、全文检索/流式导出、正文 GC 终态管理仍未收尾，不能把当前代际拒绝视为跨 MinIO/PG 原子事务。
4. 强杀、数据库中断、生产多副本竞态/资源回收矩阵及性能基准仍需专项验证。已完成的 H2 并发测试与真实 PG 用户工作流不是上述矩阵，也不包含浏览器点击型全量验收、企业私有模型质量或 CubeSandbox/E2B 验证。
5. 模型与持久化预算统一预压缩、超大工作窗口与不可缩减清单的处理仍需后续设计；上一批总量自适应 offload 不等于任意规模窗口均可保存。
6. JWT、企业身份和内部模型部署保持暂缓。DDD/MyBatis、部署级沙箱切换和父子共享沙箱约束继续保留，不因本批运行撤销实现而改变。

## 14. 第十一批：文件发布预留、短事务与孤立对象回收

### 14.1 范围与持久模型

本批先完成 R02 的文件级提交协议基础，而非同时重写记忆和编排。V41 增加 `file_publications`，保存组织/员工、Agent/根会话/Run/代际、逻辑路径、不可变对象键/后端、长度/摘要、容量预留、租约、版本收据和 GC 领取凭证。PG 强制组织 RLS；配额、路径和回收扫描具有对应索引。所有 SQL 仍在 DAL/MyBatis，应用层只调用领域仓储。

```text
STAGED -> STORED -> PUBLISHED
     \       \-> ABORTED -> DELETING -> DELETED
      \------------------------^
```

`PUBLISHED` 是文件版本与预留结算在同一事务内提交的收据，不等同整个工作区或整个 Run 已 READY。对象记录不设置到会话/Agent 的级联删除 FK，避免上层删除后丢失物理字节回收责任。`owns_object=false` 的未变化版本只引用已有对象，不能因为本次提交失败就删掉原文件。

### 14.2 实际提交顺序

1. 入口对文件大小、路径、认证身份和存储可用性沿用既有规则。生产装配强制使用新的协调器；旧构造器仅保留既有独立测试/调用的兼容形状，不是可配置的生产降级路径。
2. 短预留事务锁组织/员工，检查在途路径、会话代际和当前版本，按**正向净增量**预留容量。额度计算包含该组织/员工其他未过期预留，不把传输中的文件当成零用量；最终提交重读当前版本、复算容量，并排除自己已经计入的预留，避免双重扣减。
3. 事务外 PUT 新对象，并读取验证实际长度及 SHA-256；失败不登记版本。未变化且仍有效的当前版本复用原对象，零额外 PUT，不新建版本。用户上传、任务投影、版本恢复及附带工作区写入的入口均使用该顺序，工作区副作用也在 SQL 事务外执行。
4. 短发布事务按配额/会话/文件/发布记录的顺序取锁，重验代际、对象来源、租约和状态；文件/版本元数据、`PUBLISHED` 收据及预留结算原子提交。GC 已认领或租约失效时全部回滚，不发布已经失去对象所有权的版本。不能先锁发布记录再等会话，否则会与重置的会话→预留撤销顺序形成循环。带会话的用户上传虽然不撤销个人文件，也须在文件锁前锁定并验证所属会话，避免会话外键与回复/附件保存的反向锁顺序；重置后允许上传完成，但会话删除后拒绝晚到的关联写入。
5. 对象已经写入而 SQL 发布失败时，单独短事务将预留转为 ABORTED；DB 同时不可用时保留原预留，恢复后按到期记录发现。每次新对象具有独立发布 ID 和键，旧失效调用不能覆盖新调用的对象。
6. 重置/删除会话在原撤销事务内将带代际的 STAGED/STORED 预留作废，立即释放容量和路径占用。无代际的用户文件不随会话重置自动删除。删除/移动文件须先检查在途路径；冲突明确返回 409，不绕过预留修改原版本。

发布、恢复以及对象读取入口拒绝带外层事务的调用。只挂起外层事务不会释放它已经持有的锁，因此不能仅用 `NOT_SUPPORTED` 宣称没有锁跨 IO。协调器也检查实际事务状态，便于发现手动构造/自调用路径的误用。代码中的短事务操作不引入业务 JDBC。

原始工作区写入仍不是可与 PG 回滚的事务：发布失败可能留下原始沙箱/本地工作区字节，但权威目录不返回成功。上传正文及读回校验继续采用有界 byte[]；本批不是完整流式传输或内存性能优化。

### 14.3 回收与配置

新增文件发布 GC，复用已有组织对象存储端口和管理事务。先短事务取得带 token 的删除权，提交后再检查版本引用，随后事务外删除，仅删除匹配组织/员工/发布 ID/摘要的自有对象；后端或键不匹配时保留并有限重试，不能尝试另一后端。删除完成保留每天复查的墓碑，覆盖租约超时后远程 PUT 晚完成的情况。重复认领后的旧 token 不能修改新领取状态。

既有文件 GC 同时识别有效在途发布引用。临时引用不能直接转为永久 `referenced` 终态，否则撤销后会遗留不可发现的孤立对象；它保持可再检查的 pending 状态，不因正常等待引用解除而耗尽失败重试次数。永久版本引用沿用既有保护行为。

```yaml
saas:
  file-store:
    publication-lease-seconds: 300
    publication-gc-fixed-delay-seconds: 300
```

环境变量为 `SAAS_FILE_STORE_PUBLICATION_LEASE_SECONDS`、`SAAS_FILE_STORE_PUBLICATION_GC_FIXED_DELAY_SECONDS`。租约必须为正；GC 开关/批大小/失败上限复用原 `file-store` 配置。过期记录不继续占用容量；调大租约不允许取消最后的状态/代际校验。保留故障重试上限与墓碑，不自动清理全部员工目录或删除所有历史对象。

升级时先迁移 V41，再升级全部 writer、GC 和 Worker。维护期间停止新任务及文件修改；旧实例不认识新预留，不能与新实例混跑后宣称配额和路径串行化仍成立。备份包含预留/收据/墓碑及对应对象桶，修改存储后端需要保留旧对象的读取和回收安排，不是静默切换一个配置即完成历史迁移。

### 14.4 验证进度

新增 14 项真实 MyBatis/H2 发布集成测试，覆盖准确收据、未变化版本零 PUT、慢 PUT 期间配额可并发访问且不能超额、同路径/删除/移动冲突、目录事务回滚和孤立对象重复回收、响应丢失型 PUT 失败、实际正文损坏拒绝、过期/GC 认领后晚完成的 PUT、会话重置释放预留与新代际同路径发布、事务外工作区副作用及外层事务拒绝、组织/员工隔离，以及带会话上传在重置后保留/删除后拒绝的边界。另增加临时发布引用解除后的既有文件 GC 集成测试，共 15 项新增测试。

首轮受限环境的 Mockito 初始化失败没有计为通过；解除限制后发布 12/12，但旧 GC 独立夹具缺少 V41，随后补用实际迁移。新增临时引用测试又暴露 H2 CHECK IN 优化器保留建表会话，而单连接工厂关闭该会话后求值失败的问题。夹具改为持有并在测试结束关闭实际物理连接，没有取消约束或用 mock SQL 假装成功；相关失败轮次不计为最终通过。

初版最终专项 47 项全部通过，其中发布 12/12、既有文件 GC 3/3、会话代际 15/15、架构 11/11。完整功能 reactor（含真实 MinIO）随后返回 0，但继续审查带会话的上传路径发现反向锁边界，因此补齐所属会话锁和两项上传边界测试后重新执行最终完整回归，不把前一版通过直接当作最新代码通过。

最终完整功能 reactor 返回 **0**：发布集成 **14/14**、文件 GC **3/3**、会话代际 **15/15**、DDD/MyBatis 架构 **11/11**，错误/失败/跳过均为 0；本批新增 **15 项**全部通过。既有正文集成 **14/14**（含真实 MinIO）及其他功能套件一并通过。仍排除原 `AgentPerformanceTest`，不宣称性能门禁通过。故障及慢传输由可控对象端口注入，事务、锁、回滚和仓储是真实 MyBatis/H2；不能将其称为真实 PG/MinIO 中断或强杀演练。

真实 PG/Redis/MinIO/OpenSandbox 门禁根脚本返回 **0**，基础流程 **13/13**、代际与新增收据用例 **5/5**、总诊断 **9/9**，所有 FAIL 为 0。覆盖登录、multipart 上传/下载、HITL 确认、沙箱执行/恢复、SSE、历史读取及资源回收；新增 scoped PG 断言确认新代际生成文件的 PUBLISHED 行与对应版本的组织/员工、对象键、后端、摘要、长度完全匹配，预留归零且没有残余 STAGED/STORED。HTTP 文件可下载不再被单独当作持久收据已落库的证据。采用 scripted 模型，没有访问外部付费模型，也不把固定输出当作企业模型/记忆质量验收。

只读 PG 核对确认 V41 迁移成功、新表的 ENABLE/FORCE RLS 均开启；应用角色 SELECT/INSERT/UPDATE/DELETE 四项授权分别为 true，不将组合授权函数的一次返回替代四项独立检查，也不把管理角色查询称为员工授权矩阵。Spotless、脚本语法检查及 `git diff --check` 通过。最终应用端口 18082 无监听，四个本轮由停止状态启动的 PG/Redis/MinIO/OpenSandbox 容器恢复为 `exited`；镜像、容器、数据卷、其他容器和 Docker Desktop 保留。本批未提交、推送，不宣称浏览器点击型全量验收、生产 PG 并发压测或 CubeSandbox/E2B 验证。

最终命令与日志：

```bash
SAAS_RUNTIME_BODY_MINIO_TEST=true mvn -o \
  -pl agentscope-saas/agentscope-saas-app -am -Djacoco.skip=true \
  '-Dtest=*,!AgentPerformanceTest' -Dsurefire.failIfNoSpecifiedTests=false test -q
APP_PORT=18082 START_DOCKER_DEPS=false START_DOCKER_DAEMON=false \
  SANDBOX_GATE_SESSION_GENERATION=true \
  SAAS_RUNTIME_ARCHIVE_INLINE_MAX_BYTES=256 \
  SAAS_RUNTIME_ARCHIVE_CHECKPOINT_INLINE_MAX_BYTES=65536 \
  SAAS_RUNTIME_ARCHIVE_CHECKPOINT_MAX_JSON_BYTES=4096 \
  SANDBOX_SMOKE_REQUIRE_OFFLOAD=true \
  bash agentscope-saas/agentscope-saas-app/scripts/start-opensandbox-local.sh --smoke
```

日志：`/private/tmp/chugou-file-publication-focused-tests-verified.log`（初版专项）、`/private/tmp/chugou-file-publication-functional-tests-final.log`（最终完整功能回归）、`/private/tmp/chugou-file-publication-opensandbox-gate.log`（真实门禁）。未将中间失败轮次或清理时子 Maven 的 143/BUILD FAILURE 日志当成最终根门禁结果；测试阈值只作用于本次进程，不修改部署默认值。

### 14.5 尚未完成的范围

1. 本批只闭合**文件级**预留、传输与目录发布边界；新增租约是发布预留租约，未替代业务 attempt 租约。完整期望产物清单、任务/attempt 精确发布范围、OBJECT_STORED 后的跨进程自动补登、RunArtifact/Outbox 统一发布收据及 retention lease/不可恢复终态仍需后续实施，R02 不标记全部完成。当前进程崩溃后的已保存但未登记对象由孤立对象协议收敛，不宣称已经能自动还原所有原始发布意图。
2. 完整流式上传/归档/恢复、传输并发与内存基准、物理存储容量治理、历史发布记录/墓碑保留及失败超过上限的管理处置仍需推进。
3. 文件式记忆后台提炼/整合和水位、岗位及个人记忆治理、全状态轻量化/旧代际键清理、Journal 稳定消息修复、强杀/数据库中断与生产多副本故障矩阵等继续按第 13.6 节及文档 37 实施。JWT、企业身份和内部模型部署继续暂缓。

## 15. 第十二批：持久发布意图与有界跨进程补登

### 15.1 实施范围

V42 新增组织强制 RLS 的 `file_publication_intents`，保存原文件/版本/状态、内容类型、来源、有限 JSON 元数据、工作区副作用要求以及可信 task/AgentRun/attempt/lease owner。发布预留与意图在同一短事务提交，元数据最多 32 KiB，不保存文件正文、模型凭证或完整对话。旧发布记录默认不可恢复，不凭已有对象猜测原始意图。

发布表新增独立的恢复领取 token、次数、due 和总 deadline。只有已过原调用租约的记录才可被新进程认领；领取期间仍占用容量和路径，原进程不能再登记 STORED、PUBLISHED 或作废新领取者的记录。领取前重新准入配额和原版本，避免租约到期后容量已被其他写入使用而盲目恢复。原版本或状态变化时拒绝补登，不能以旧对象覆盖后来上传的版本。

- 纯目录投影：STAGED 对象可在验证长度、摘要及当前授权范围后补登，覆盖 PUT 成功但 STORED 尚未提交的崩溃窗口。
- 带工作区写入的上传/恢复：仅恢复 STORED，证明原写入回调已完成；STAGED 不重放未知工作区副作用。
- 验证与恢复仅读取一个有界已保存对象和持久意图，不重新调用模型、工具或沙箱，不读取员工全部历史/工作区。
- 补登文件版本、收据和预留结算在同一短事务完成。新进程不将单个文件补登当成整个 Run 已成功；任务完成门禁保持既有方式。
- 暂时 SQL/事务问题在完整传输后保留可恢复事实；对象读取失败按次数/总 deadline 退避。内容损坏、原版本变化、配额拒绝、会话失效、执行范围失效等拒绝自动发布。关闭恢复开关时失败直接作废预留，不能留下无消费者的“等待恢复”。
- GC 的查询和原子领取同时保护恢复 deadline 内的可恢复记录；到期或拒绝后按既有自有对象与墓碑机制回收。原 GC token 与恢复 token 分离，过期 token 不得修改新状态。

### 15.2 运行时与锁顺序

实际沙箱投影入口从可信 `StepSnapshot.Identity` 和 `ExecutionLeaseSnapshot` 传入精确执行范围。根会话代际、所属 Run、task/AgentRun/attempt 关联、RUNNING 状态和 lease owner/到期时间在发布前重验，不能只凭 Run ID 或会话代际授权旧 attempt。没有完整执行身份的旧调用不进入自动补登。

发布/恢复保持配额→根会话→Run→attempt→文件→发布记录的短事务顺序。Worker 的领取、start 和终态事务先锁 Run，再改变任务/attempt 并追加事件，避免与发布的 Run→attempt 检查形成反向等待。心跳仍只更新 attempt，不额外锁 Run。IO 不持有这些锁；基线领取、重试、取消和完成均需要继续回归。

SQL 继续只在 DAL/MyBatis，Domain 只定义 Java 端口和可信值对象；Core 无 SaaS/数据库依赖。新意图 Mapper 默认 INFO，避免私有元数据出现在参数 DEBUG 日志中。恢复服务不提供模型或沙箱执行权限。

### 15.3 配置与部署

```yaml
saas:
  file-store:
    publication-recovery-enabled: true
    publication-recovery-deadline-seconds: 900
    publication-recovery-max-attempts: 3
    publication-recovery-retry-seconds: 30
    publication-recovery-fixed-delay-seconds: 5
```

对应 `SAAS_FILE_STORE_PUBLICATION_RECOVERY_ENABLED`、`SAAS_FILE_STORE_PUBLICATION_RECOVERY_DEADLINE_SECONDS`、`SAAS_FILE_STORE_PUBLICATION_RECOVERY_MAX_ATTEMPTS`、`SAAS_FILE_STORE_PUBLICATION_RECOVERY_RETRY_SECONDS`、`SAAS_FILE_STORE_PUBLICATION_RECOVERY_FIXED_DELAY_SECONDS`。次数和退避必须为正，总 deadline 必须大于发布租约；错误预算在装配时拒绝。开关关闭停止后台扫描及新记录的自动恢复标记，但不移除意图/收据读取与 GC 的保护协议。

先迁移 V42，再升级全部 writer、恢复器、GC 和 Worker；维护期间停止新任务/文件修改。旧 GC 不认识恢复保护，不能支持新旧实例长期混跑。启用旧记录恢复必须另做显式迁移，本批不将缺少意图的 V41 历史记录自动变成可恢复状态。部署级沙箱配置不变，验证仍只用 OpenSandbox。

### 15.4 验证进度

发布集成初版 **25/25**，本批新增 **11 项**通过；既有 Worker 租约 **8/8**、文件 GC **3/3**、DDD/MyBatis **11/11** 一并通过。覆盖新的协调器/Job 从已提交意图补登、重复扫描单版本、已完成工作区写入不重放、原版本更新拒绝、存储失败重试上限、损坏拒绝、GC 保护、旧 token/旧进程拒绝、前台精确身份/取消校验、纯投影 STAGED 恢复、未知副作用 STAGED 不恢复及取消 attempt 的补登拒绝。SQL 故障和对象缺失由可控端口注入，事务/回滚/仓储是真实 MyBatis/H2，不冒充生产 PG 中断或 JVM 强杀演练。

新增夹具的租约 DTO 字段引用及导入造成的编译失败没有计为通过；修复后专项根脚本返回 0。关闭恢复时预留释放的补充用例已加入，最终发布集成 **26/26**，本批新增 **12 项**全部通过。Worker 租约 **8/8**、DDD/MyBatis **11/11**、Redis guard **2/2** 及既有文件 GC、正文/检查点等功能一并通过；最终完整功能 reactor 返回 **0**，包含真实 MinIO，仍排除原 `AgentPerformanceTest`，不宣称性能验收。

中间全量轮次一次在补充开关边界后被 Spotless 阻止，一次被旧 Redis guard 成功用例的 100ms 冷启动预算阻止；均未计为通过。后者日志显示首次 mock SET 调用冷启动约 3.6 秒，成功用例改为 5 秒等待并要求恰好两次 SET 和按成功 token 释放，原 5ms 负向超时测试及生产实现/配置不变。格式化和夹具修复后重新从完整 reactor 起点运行最终回归，而非只忽略失败套件。

真实 PG/Redis/MinIO/OpenSandbox 门禁根脚本返回 **0**，基础流程 **13/13**、会话及执行身份收据用例 **5/5**、总诊断 **9/9**，所有 FAIL 为 0。新增 scoped PG 断言将生成文件的发布收据、持久意图及对应 task/AgentRun/attempt 一起核对，确认实际投影入口具有可信执行身份，而非仅正常下载一个文件。覆盖登录、上传/下载、HITL、沙箱执行/恢复、最终 SSE、历史读取、执行中重置及资源回收；使用 scripted 模型，不调用外部付费模型、不代替企业模型/记忆质量验收，也不是浏览器点击型全量验收。

只读 PG 核对确认 V42 成功，新意图表 ENABLE/FORCE RLS 为 true，应用角色 SELECT/INSERT/UPDATE/DELETE 四项授权分别为 true。恢复故障矩阵仍是可控端口与真实 H2/MyBatis 事务验证，本轮未对运行中的 PG/MinIO 注入中断或强杀 JVM，也未验证已失效 Worker 的独立归档恢复，不以正常 PG 门禁代替这些证据。

Spotless、脚本语法检查和 `git diff --check` 通过。验证后确认 18082 无监听，恢复本轮从停止状态启动的 PG/Redis/MinIO/OpenSandbox 四容器为 `exited`，镜像、容器、卷和 Docker Desktop 保留，未操作其他容器。本批未提交、推送。

最终命令与日志：

```bash
SAAS_RUNTIME_BODY_MINIO_TEST=true mvn -o \
  -pl agentscope-saas/agentscope-saas-app -am -Djacoco.skip=true \
  '-Dtest=*,!AgentPerformanceTest' -Dsurefire.failIfNoSpecifiedTests=false test -q
APP_PORT=18082 START_DOCKER_DEPS=false START_DOCKER_DAEMON=false \
  SANDBOX_GATE_SESSION_GENERATION=true \
  SAAS_RUNTIME_ARCHIVE_INLINE_MAX_BYTES=256 \
  SAAS_RUNTIME_ARCHIVE_CHECKPOINT_INLINE_MAX_BYTES=65536 \
  SAAS_RUNTIME_ARCHIVE_CHECKPOINT_MAX_JSON_BYTES=4096 \
  SANDBOX_SMOKE_REQUIRE_OFFLOAD=true \
  bash agentscope-saas/agentscope-saas-app/scripts/start-opensandbox-local.sh --smoke
```

日志：`/private/tmp/chugou-publication-recovery-focused-tests-verified.log`（初版 25/25 专项）、`/private/tmp/chugou-publication-recovery-functional-tests-verified.log`（最终 26/26 与完整功能 reactor）、`/private/tmp/chugou-publication-recovery-opensandbox-gate.log`（真实门禁）。中间格式/编译/冷启动失败的轮次保留但不计入通过，清理时子 Maven 143/BUILD FAILURE 不能替代根脚本退出码。测试阈值只作用于验证进程，不修改部署默认值。

### 15.5 尚未闭合

1. 文件级持久补登不等于完整 R02：仍缺期望产物清单、RunArtifact/Outbox 统一发布收据、ARCHIVING 状态及独立 retention lease。原 Worker attempt 已失效时本批明确拒绝恢复，不能把恢复 token 当作重新授权业务 attempt；后续需独立归档生命周期，不能盲目重跑有副作用工具。
2. 临时故障/过期/拒绝状态的管理页面、超过重试上限的处置、历史意图/收据/墓碑保留策略，以及生产多实例/强杀/数据库中断矩阵和内存性能基准仍需推进。
3. 文件式记忆后台化、岗位/个人记忆治理、全状态与旧代际键清理等其他范围仍按文档 37 和第 14.5 节推进。JWT、企业身份和内部模型部署保持暂缓；不宣称本轮已经全部完成。
