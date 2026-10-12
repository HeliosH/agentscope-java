# 38. 运行时可靠性实施记录

> 方案来源：[37. 运行时可靠性修复与能力收尾落地方案](./37-runtime-reliability-and-feature-closure-plan.md)
>
> 更新日期：2026-10-10。状态：运行时治理、Mem0 投影队列、PG 增量运行消息归档、关键消息提交屏障、大正文分离存储、轻量检查点及总量自适应转存、会话执行代际撤销、文件发布预留、持久补登、原子任务产物收据、传输前冻结清单、独立归档接管、准确清单恢复、计算后提交边界、不可变字节源保管、显式附件权威版本恢复、Provider 有界保留、关键正文 PG 降级、已计算结果原子交接、主文件链路有界流式、真实恢复消费者及临时文件失效 owner 回收已实施；最新验收与剩余范围见第 26–28 节，全量矩阵见文档 39。不代表 B0-B8 全部完成。

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

## 16. 第十三批：原子任务产物收据与持久完成门禁

### 16.1 消除文件提交与任务登记之间的崩溃窗口

此前文件版本和 PUBLISHED 已可在同一事务提交，但 RunArtifact 仍由执行结束后的另一个事务登记。进程在两者之间退出，会出现文件已经存在、所属 attempt 却没有产物事实的问题。V43 在现有发布账本新增 `artifact_id`、`receipt_event_id` 和按组织/Run 查询的索引；普通上传两列均为空，任务文件两列必须同时存在且处于 PUBLISHED。不新增事实库、消息队列或并行任务状态机。

正常发布与后台补登共用同一原子顺序：

```text
事务外：PUT / GET 校验已完成的不可变对象
短事务：FileVersion + 文件当前版本 + PUBLISHED / 容量结算
        + 精确 attempt 的 RunArtifact
        + workspace_file_published Outbox
        + 发布记录的 artifact/event 链接
提交后：返回文件收据
```

其中任何写入失败，整个短事务回滚；恢复开启时保留已传输对象与 STORED/意图，由既有有界恢复器重试，不重新调用模型、工具或沙箱。没有恢复条件时沿用原作废/孤立对象回收机制。此处的事务原子性只涵盖同一 PG 数据域，不把 MinIO 和 PG 宣称为跨系统事务。

RunArtifact 的稳定键仍为 attempt+不可变版本，Outbox 使用由 artifact 导出的稳定 ID。同一 attempt 重复投影相同版本，不再次 PUT、不创建第二个版本/产物/事件；每次发布预留仍分别保存其原子收据。租约/配额锁已对同一员工的写入串行化，不能把检查后插入当成脱离现有锁协议仍安全。

事件只带 event、artifact、Run、task、attempt、文件和版本 ID，不写文件正文、对象键、任意工具元数据或凭证。Outbox 继续至少一次投递，消费者按事件 ID 幂等；收据表示事件意图已提交，不表示所有外部消费者已经成功处理。

### 16.2 完成门禁和展示规则

`WorkspaceArtifactService` 不再从内存文件列表补写产物，也不因产物 ID 存在就直接放行。门禁在同一短事务按会话→Run→attempt 锁定当前执行范围，拒绝取消/终态 Run、非 RUNNING 的 task/AgentRun/attempt 及过期 Worker。随后只读取该精确 attempt 的持久收据，一次关联发布、意图、版本、文件归属、RunArtifact、Outbox 和原 Run/attempt；逐项检查文件 ID、版本、路径、对象键、后端、摘要和长度。重复路径、缺失事件、缺失产物或字段不一致均拒绝 READY；空投影也不能绕过执行有效性检查。

交互请求原有的运行前文件版本基线移入 checkpoint，并由可信投影入口写入有界意图元数据。产物首次登记即可得到稳定的 `producedInRun`，避免文件提前生成/登记后到最终投影时被误判为历史文件。没有交互基线的 Worker 沿用发布意图的原版本比较，不声称本批完成任务级输入清单。用户文件、运行大正文及记忆的既有隔离方式不变。

DDD/MyBatis 边界保持：Domain 定义收据和仓储端口；关联查询、锁和更新均在 DAL；应用协调原子用例；Core/Harness 不引入 PG/SaaS。正常上传不产生虚构的 RunArtifact，普通请求继续使用租户通道，不调用管理 Mapper 绕过 RLS。

### 16.3 部署与历史兼容

先执行 V43，再同时升级文件 writer、补登器和完成门禁；升级前停止新任务并处理在途运行。不支持旧 writer 与新完成门禁长期混跑，也不从 V41/V42 历史缺失链接的记录猜测新收据。历史文件和既有产物查询继续可用；未完成旧任务需要显式排空/处置，不自动篡改其成功状态。

新增链接不对 RunArtifact/Outbox 设置级联外键，发布和删除责任仍须在上层 Run 被删除后保留。新列继承发布表既有强制组织 RLS 和授权；检查约束只描述两列是否同时存在，完整身份与内容一致性由租户关联查询验证。正在归档的收据/Outbox 不能提前按通用事件保留策略物理删除，否则完成门禁会正确拒绝；完整历史保留和运维处置策略仍是后续工作。

### 16.4 验证进度

专项 reactor 返回 **0**：发布集成 **39/39**（本批新增 **13 项**）、持久门禁 **13/13**、Worker 租约 **8/8**、DDD/MyBatis 架构 **11/11**，错误/失败/跳过均为 0。新增集成测试覆盖三个 SQL 提交阶段写入后失败的整体回滚、独立恢复器单版本/单事件补登、同版本重复收据、普通文件无任务副作用、取消/租约失效、PUT 中取消、跨组织/Run/task/attempt 拒绝、缺失 Outbox、持久版本摘要损坏及产物展示基线。门禁单测覆盖七类字段差异、缺失收据、重复路径、失效执行及不能从内存补写事实。

测试使用真实 MyBatis/H2 事务和仓储；对象与故障点由可控端口提供，不称为生产 PG 中断或 JVM 强杀。首轮新增夹具缺少 Delete 注解导入的编译失败不计为通过，修复后重新运行完整专项 reactor。

完整功能 reactor 已返回 **0**，包含真实 MinIO 正文 **14/14**、发布 **39/39**、门禁 **13/13** 及其他功能回归；仍排除原 `AgentPerformanceTest`，不宣称性能验收。真实 PG 已成功执行 V43，原发布表 ENABLE/FORCE RLS 均为 true；新增两列的应用角色 SELECT/UPDATE 四项授权分别为 true。

首轮真实门禁在 OpenSandbox 重新创建容器时遇到 Docker 随机端口 55090 绑定冲突，基础流程返回 **8/13**、根脚本退出 **1**，未计入通过。OpenSandbox 日志确认创建失败的容器已自行清理；只读检查没有遗留测试沙箱容器及冲突端口监听。保持相同验证入口和全部断言重试，不跳过收据测试、不修改业务实现或部署默认配置。

重试真实 PG/Redis/MinIO/OpenSandbox 门禁根脚本返回 **0**：基础任务流程 **13/13**、会话代际与收据 **5/5**、总诊断 **9/9**，所有 FAIL 为 0。实际生成文件的 scoped PG 断言同时核对发布记录、不可变版本、精确 task/attempt 的 RunArtifact、Outbox 及其版本/产物/Run 身份；事件不含对象键。覆盖登录、multipart 上传/下载、HITL、沙箱运行/恢复、SSE、历史读取、执行中重置、删除和资源回收。使用 scripted 模型，不验证企业模型/记忆质量、不调用外部付费模型；未宣称浏览器点击型全量验收、真实故障中断/强杀或 CubeSandbox/E2B 验证。

Spotless、脚本语法检查和 `git diff --check` 通过。根门禁完成后停止临时应用，确认 18082 无监听，四个本轮启动的 PG/Redis/MinIO/OpenSandbox 容器均恢复为 `exited`；镜像、容器、数据卷及 Docker Desktop 保留，其他容器未操作。子 Maven 因清理显示的 143/BUILD FAILURE 不替代根脚本结果。验证进程的正文/检查点阈值不写回部署默认值。本批变更尚未提交、推送。

最终命令：

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

日志：`/private/tmp/chugou-artifact-receipts-focused-verified.log`、`/private/tmp/chugou-artifact-receipts-functional-tests.log`、`/private/tmp/chugou-artifact-receipts-opensandbox-gate-verified.log`。失败的首轮真实日志另存为 `/private/tmp/chugou-artifact-receipts-opensandbox-gate.log`，不计入最终通过证据。

### 16.5 后续仍未完成

1. R02 仍缺**传输前持久期望产物清单**、完整扫描/清单收据、ARCHIVING 状态和独立 retention/归档租约；本批闭合文件+任务产物+事件的提交窗口，不把逐文件 PUBLISHED 当成全任务成功。失效原 Worker 的补登仍拒绝，不重放未知工具副作用。
2. 管理端归档/补登故障处置、历史收据/事件/墓碑保留、真正的 PG/MinIO 中断和 JVM 强杀、多副本并发、流式容量/内存性能基准仍待实施/验收。
3. 文件式记忆后台提炼及水位、企业只读岗位/个人记忆治理、任务工作集、安全人工重试/副作用对账、实质产物验证和其他 B3-B8 继续按文档 37 推进。JWT、企业身份及内部模型部署保持暂缓。

## 17. 第十四批：传输前冻结清单与完整工作区发布门禁

### 17.1 范围和两阶段投影

本批先完成 R02 的发布预期清单，而非直接扩大业务 attempt 的租约。此前边扫描边上传，清单只存在于 checkpoint 内存：尾部文件超限可能发生在部分文件已经投影后，重启/错误计数也缺乏独立的完整预期事实。

现在从 Provider 获取一次工作区归档，按既有 regular-file 投影范围扫描，先验证规范路径、重复路径、数量、单文件和总字节限制，并计算每个文件的实际长度和 SHA-256。使用受限本地临时 TAR 冻结这些字节，第一次扫描不写远程投影、不创建文件发布预留。非 regular-file 条目不解引用，沿用既有投影策略；两项内部投影/Volume 元数据不作为产物。目录和符号链接的完整备份/业务验收不属于本批。

```text
一次 Provider 归档 -> 有界扫描/临时快照 -> 冻结完整清单到 PG
                  -> 从同一快照投影/登记每项文件
                  -> 记录完整传输扫描 -> 校验持久清单/收据 -> READY
```

准入失败或完整扫描被拒绝时，任何 regular-file 产物均不开始本次投影。临时快照在正常/异常退出的 finally 中删除，既有完整远程清单保留。上传期间不再调用 Provider 重新扫描；内容不能来自与入库摘要不同的第二份工作区。临时快照是本次进程的受限缓存，不是跨进程恢复事实；本批未把它当成独立 retention lease，JVM 强杀后的缓存清理/容量运维仍需实施。

保留 Provider 配置化选择，不让用户选择 Provider。持久卷按预扫描决定未变化文件和冲突副本目标，目标路径也写入冻结清单，不在传输时重新生成一个随机目标。未变化且没有远程冲突的文件仍触发目录登记，并实际读取不可变对象核对长度/摘要；零额外 PUT 不等于零验证。沙箱原文件未变化但浏览器已更新/删除远程版本时，拒绝本次扫描，不将旧内容谎称为 verified，也不覆盖新的远程版本；两边都改变时继续保留既有冲突副本行为。

### 17.2 权威数据与短事务边界

V44 新增 `workspace_publication_manifests` 和 `workspace_manifest_entries`。头记录保存组织/员工/Agent/根会话/代际、Run/task/AgentRun/attempt/lease owner、单调扫描序号、结构化指纹、预期数量/字节和扫描状态；条目独立保存来源路径、实际发布路径、长度、摘要、未变化标记和发布收据 ID，不保存正文、凭证或整段对话 JSON。

状态为 `DISCOVERED -> SCANNED -> READY`：DISCOVERED 表示完整预扫描清单已冻结，SCANNED 表示实际投影循环完整结束，并不宣称所有目录写入都已成功；READY 只由完整持久门禁产生。此状态属于工作区发布，不是新增的业务任务调度状态，也不表示业务内容验收已完成。

- 准入按会话→Run→attempt 取短事务锁，验证当前用户、代际、状态、准确持有者和租约，随后分批写头/条目。没有 SQL 锁跨 Provider、临时文件或对象存储 IO。
- 每次成功预扫描产生该 task/attempt 的下一序号。新清单存在后，旧清单的晚到发布与完成检查明确拒绝；旧记录保留审计，不更新为当前清单。
- 文件发布意图保存可信 `workspaceManifestId`。其条目与 FileVersion、RunArtifact、Outbox、PUBLISHED/容量结算在原短事务内一起链接；范围/摘要/长度/路径不匹配时全部回滚。
- SQL 暂时失败后，原有恢复器从持久意图读回同一清单 ID，并仅恢复尚有效、尚未被替代的授权范围。不从孤立对象或同名最新文件猜测清单，不重跑模型或工具。
- 头/条目开启并强制组织 RLS，条目用包含组织/员工的复合外键绑定头记录。删除 Run 不级联丢失清单事实；记录保留/物理对象回收继续需要明确策略。普通请求全部使用 Tenant MyBatis 通道。

Core/Harness 只新增通用 `WorkspaceProjectionPlan` 和关键准入/完成回调，不引用 SaaS/PG。Domain 定义持久清单端口；DAL 实现所有关联查询和更新；应用负责冻结、事务和门禁。Mapper 日志为 INFO，避免私有路径和意图出现在 SQL bind DEBUG 日志中。

### 17.3 完成门禁与重试上下文

完成时先检查既有 checkpoint 和不可变产物收据，再以原冻结身份重验当前租约持有者并锁定最新清单。重读头和全部该清单条目，验证数量、总字节和长度编码的结构化 SHA-256 指纹；关联条目的准确 publication、文件版本、RunArtifact 和 Outbox，逐项比较内存版本、文件 ID、路径、后端、对象键、长度及摘要。少项、错项、未完成扫描、租约换主、旧清单以及条目篡改均拒绝 READY。完整零文件清单可幂等通过，缺少清单的零上传计数不能通过。

持久卷 checkpoint 的数量包含已验证的 unchanged 文件，不只包含上传数量。远程投影返回失败收据而不抛异常时也会停止，不记录成功完成。当前调用的后台补登不会自动把整个任务标为成功。

请求内重试可能复用 `WorkspaceCheckpointContext`。新的合法冻结清单重置上一扫描的文件列表、错误和生命周期标记，避免重复计数或保留已恢复的临时错误；基线版本仍属于原业务任务。同一任务的新合法 attempt/持有者必须先通过冻结准入；不能复用一个 checkpoint 切换组织、员工、会话代际、Run 或 task。旧身份完成时仍按旧持有者拒绝，不因此授权工具重放。

先迁移 V44，再同时升级 Harness、writer、补登器和完成门禁，排空旧实例在途运行。旧 writer 不会绑定完整清单，不能与新门禁长期混跑，也不自动为 V43 历史记录补造清单。历史已提交文件/产物查询不变。

### 17.4 验证进度

初版专项 reactor 返回 **0**：文件发布/清单集成 **50/50**、沙箱文件系统 **27/27**（新增 **6 项**）、持久产物门禁 **13/13**、DDD/MyBatis 架构 **11/11**；沙箱生命周期、计量观察者和目录 sink 一并通过。初版完整功能 reactor（含真实 MinIO）返回 **0**。构造器更新遗漏两个旧 sink 测试的编译失败不计为通过，补齐夹具后重新从 reactor 起点运行。

随后补充清单指纹计数、同上下文重试清单重置、同业务任务边界和既有非 regular-file 投影兼容，最终完整功能回归正在执行，尚未将上一版结果直接作为最终通过。关键集成覆盖冻结清单缺项、未完成扫描、非预期路径/正文、指纹篡改、旧清单晚到、准确租约换主、零文件幂等、伪造员工、unchanged 真实字节校验，以及清单链接写入后 SQL 故障的整体回滚和单版本补登。Harness 覆盖先准入再传输、只读取一次 Provider 归档、重复规范路径、unchanged 目录回执、返回型上传失败、远程冲突拒绝及稳定指纹。

故障注入使用可控端口和真实 MyBatis/H2 事务，不冒充 PG/MinIO 中断或 JVM 强杀。不调用外部付费模型、不把 scripted 模型当成企业模型或记忆质量验收。JWT、身份和内部模型部署不在本批范围。

首轮真实门禁根脚本返回 **1**，基础流程 **7/13**：SaaS 的运行依赖提供旧版 Commons Compress，Harness 编译用的新具体类 TAR 方法签名不存在，触发 `NoSuchMethodError`，继而 SSE 未正常终止且执行槽未释放；没有把该轮计为通过。改用仓库已有的 `ArchiveOutputStream` 基类调用方式，不升级/替换整个依赖图。新增 SaaS 模块依赖图下的真实 TAR 预扫描/投影测试 **1/1**；新夹具先因旧库的非泛型 API、匿名子类私有字段访问失败，修复后重新运行专项，失败轮次不计入最终通过。

同时将释放流程的后台释放尝试、本地执行租约关闭和 scoped sandbox 清理纳入嵌套 finally。projection/persist/release 的致命错误继续传播，不返回假成功，但不能绕过本地执行槽释放；新增三项 LinkageError 注入，生命周期专项 **12/12**。借用父沙箱的子 Agent 仍不拥有释放权。不能以此宣称进程强杀、OOM 或 Provider 永久不可用也必然已删除远程资源；持久回收对账仍承担该职责。

真实 PG 已成功迁移 V44，两个新表的 ENABLE/FORCE RLS 均为 true，应用角色 SELECT/INSERT/UPDATE/DELETE 八项授权分别为 true。错误修复后的最终专项 reactor 返回 **0**：发布/清单 **52/52**、文件系统 **27/27**、生命周期 **12/12**、应用依赖兼容 **1/1**、产物门禁 **13/13**、DDD/MyBatis **11/11**。

最终完整功能 reactor 返回 **0**，包含真实 MinIO 正文 **14/14**、上述清单/兼容/清理及其他功能回归，仍排除原 `AgentPerformanceTest`，不宣称性能验收。中间完整轮次发现旧 Core 取消测试在异步上游实际取消前立即断言的竞态；夹具改为在原 3 秒预算内等待真实取消回调，继续检查取消标记、终态以及没有 dropped errors，生产 ToolExecutor 和等待配置不变。失败轮次不计为通过；重新从完整 reactor 起点运行后取消日志 **8/8**。

本批新增 **23 项**测试：发布/清单 **13 项**、Harness 预扫描/冻结/unchanged/失败 **6 项**、错误清理 **3 项**、应用依赖图兼容 **1 项**。

修复后真实 PG/Redis/MinIO/OpenSandbox 门禁根脚本返回 **0**：基础任务流程 **13/13**、会话及完整清单收据 **5/5**、总诊断 **9/9**，所有 FAIL 为 0。新增 scoped PG 断言将实际输出与冻结条目、READY/scan_complete 头记录、预期数量/字节、不可变版本、准确 task/attempt 的 RunArtifact 和 Outbox 一起核对，要求清单无未链接条目。覆盖登录、上传/下载、HITL、沙箱执行/恢复、SSE、历史读取、执行中重置、删除和后端释放。负向撤销过程的 archive commit 错误是预期拒绝路径，不作为成功提交；不把这些 API 检查称为浏览器点击型全量验收、模型质量验收、真实 PG/MinIO 中断、JVM 强杀或 CubeSandbox/E2B 验证。

Spotless、脚本语法检查和 `git diff --check` 通过。根门禁结束后临时应用停止，确认 18082 无监听；本轮从停止状态启动的 PG/Redis/MinIO/OpenSandbox 四容器均恢复为 `exited`，镜像、容器、数据卷和 Docker Desktop 保留，其他容器未操作。子 Maven 清理时的 143/BUILD FAILURE 不替代根脚本退出码。测试的归档阈值仅作用于本轮进程，不改部署默认值。本批及上一批变更尚未提交、推送。

最终命令：

```bash
SAAS_RUNTIME_BODY_MINIO_TEST=true mvn -o \
  -pl agentscope-saas/agentscope-saas-app -am -Djacoco.skip=true \
  '-Dtest=*,!AgentPerformanceTest' -Dsurefire.failIfNoSpecifiedTests=false test -q
SAAS_MODEL_TYPE=scripted APP_PORT=18082 \
  START_DOCKER_DEPS=false START_DOCKER_DAEMON=false \
  SANDBOX_GATE_SESSION_GENERATION=true \
  SAAS_RUNTIME_ARCHIVE_INLINE_MAX_BYTES=256 \
  SAAS_RUNTIME_ARCHIVE_CHECKPOINT_INLINE_MAX_BYTES=65536 \
  SAAS_RUNTIME_ARCHIVE_CHECKPOINT_MAX_JSON_BYTES=4096 \
  SANDBOX_SMOKE_REQUIRE_OFFLOAD=true \
  bash agentscope-saas/agentscope-saas-app/scripts/start-opensandbox-local.sh --smoke
```

日志：`/private/tmp/chugou-workspace-manifest-runtime-focused.log`（最终专项）、`/private/tmp/chugou-workspace-manifest-functional-tests-final-verified.log`（最终完整功能）、`/private/tmp/chugou-workspace-manifest-opensandbox-gate-verified.log`（最终真实门禁）。首轮 ABI 故障日志为 `/private/tmp/chugou-workspace-manifest-opensandbox-gate.log`，以及中间编译/取消夹具失败轮次，均不计入最终通过。

### 17.5 仍需继续

以下为第 14 批结束时的待办；独立归档的后续实施范围见第 18 节，不能将历史待办等同于最新状态。

1. R02 仍缺业务调度层 `ARCHIVING`、独立归档/retention lease、缺字节时的有界 Provider/快照保留，以及原业务 attempt 失效后的独立归档接管。清单和逐文件补登已具备事实基础，但不能自动续期旧 Worker、更不能盲目重放未知工具副作用。
2. 此清单是**工作区发布清单**，不替代执行前输入版本/任务工作集（R06），也不证明模型达成业务验收目标（R09）。业务要求三项产物却只生成两项时，本批不能单独证明目标已达成；仍需确定性验证器及独立只读验证流程。
3. 全链路流式 IO、临时快照强杀清理/并发磁盘容量、历史清单/收据/墓碑保留与管理处置、多副本/真实故障/压测和灾备矩阵仍待推进。文件式记忆后台提炼及水位、岗位/个人记忆治理、安全人工重试/副作用对账和其他 B3-B8 继续按文档 37。

## 18. 第十五批：已材料化输出的独立归档接管

### 18.1 闭合范围

本批处理一个确定的故障窗口：模型和工具已经返回结果，工作区扫描完整，预期文件全部有不可变 `STORED`/`PUBLISHED` 对象，但目录登记或发布收据提交失败。此时不应把归档失败当成业务执行失败，更不能重跑已经完成、可能产生外部副作用的工具。

保留正常即时发布路径。只有成功计算结果的工作区发布失败进入接管判断；计算没有返回结果、扫描不完整、缺少持久字节、清单被替换、原执行租约过期或会话已经撤销，都不伪造后台归档成功。接管提交本身失败返回明确的归档交接失败；Durable Worker 使用终止策略，不走普通业务重试。此保证针对本批新增的归档交接异常边界，不等同于已经闭合所有数据库全断、最终答复提交异常和 JVM 强杀窗口。

### 18.2 状态与存储

新增 V45，两种方言均维护相同事实结构：

| 事实 | 保存内容 | 用途 |
|---|---|---|
| `workspace_archive_jobs` | 完整员工/Agent/session/generation/run/task/attempt 范围、原执行 owner、冻结 manifest ID、待交付结果 body ID/字节、状态、独立 token/租约、deadline、重试次数与下次时间 | 有界、可接管的归档任务，不授予模型、工具或 Provider 执行权限 |
| `workspace_archive_items` | 冻结清单路径与本次选定的 publication ID；同一 job 不得重复路径或重复 publication | 恢复只能补齐选定的原对象，不能读取一个后来改变的“当前工作区” |
| `runtime_message_bodies` | 待交付结构化结果与完整答复块的私有不可变正文、摘要、大小和存储后端 | 复用 PG/MinIO 存储、配额、校验及正文回收；job 表不放大正文，不出现在用户输入/输出文件目录或记忆目录 |

正文对象 PUT/GET 校验发生在事务外；随后在短事务内锁定 session → Run → 原 attempt，校验清单指纹、完整扫描与每个选定源的大小/摘要，附着正文引用、写 job/items，原 task、agent run 和 attempt 同时转为 `ARCHIVING`。原 attempt 的执行租约清空，原 owner 仅用于追溯，不能恢复执行授权。写入 `TASK_ARCHIVING` Run 事件及对应 Outbox；事件只携带归档 ID，不携带文件正文或存储密钥。

Run 保持 `RUNNING`，因为一个 Run 可以同时有其他仍在执行的 DAG 节点；`ARCHIVING` 是节点/attempt 生命周期，不把整个 DAG 错误地改成单一归档状态。普通 Worker 只领取 READY 节点，过期执行接管只扫描 LEASED/RUNNING attempt，不能领取或重跑 ARCHIVING 节点。交接异常释放本地 Worker 槽位；心跳识别已交接的执行，避免中断已经完成交接的线程。

两张新表使用 FORCE RLS、组织隔离策略、应用角色四类 CRUD 权限、owner/due/body/publication 索引与带组织/员工的 items → jobs 外键。原 session/Run 删除不级联删除归档证据，以便晚到 IO 与有界保留仍可发现。继续使用同一个数据库的租户和管理角色连接，未新增第三套数据库；所有生产 SQL 仍在 DAL MyBatis，App 仅依赖领域端口。

### 18.3 接管与交付

1. 后台扫描 PENDING 或已失效的归档 RUNNING 租约，先重新锁定员工、session 代际、Run 和 ARCHIVING attempt，条件抢占一个新的独立 token。旧 token 在后续补登和最终提交处均拒绝。
2. 校验读取待交付正文。逐项跳过已 PUBLISHED 对象；STORED 对象只能在归档授权、选定 item、准确 task/attempt/owner 和最新完整清单共同成立时补登。GET 后再次核对字节数和 SHA-256，IO 不占事务锁。
3. 目录版本、RunArtifact、Outbox 与清单条目链接继续在原短发布事务中原子提交。归档路径不使用原业务 lease，也不能把归档 token 传给模型或工具执行。
4. 全部条目有精确收据后，校验清单指纹、数量、大小和完整关联，置 READY，计算不可变工作区引用。若存在对应已释放沙箱租约，只更新其快照引用/版本，供后续合法业务 attempt 恢复；不重新启用、续期或创建沙箱。
5. 保留原任务 CompletionGate。规则未通过不交付最终答复，也不启动业务重试。通过时先在归档 token 和会话 fence 下幂等保存最终答复，再由管理侧短事务结算 attempt、节点、DAG 后续调度/Run、事件/Outbox 和 job。二者通过 durable job 与幂等答复键收敛，不在持有租户事务锁时嵌套管理写事务或进行对象 IO。
6. 临时失败只重试归档。归档 actor 仅释放自己持有的逐文件 recovery token，避免等完整文件租约才能下一轮重试，也不能释放另一个 actor 的租约。deadline 或次数耗尽后按终止/人工处置策略结算，不生成新的业务 attempt；过期发现不受未来退避时间阻碍，锁等待后按新时间再次校验授权。取消、重置、删除或员工范围失配会撤销接管，不能重新写文件或回复；取消同时结算 ARCHIVING AgentRun，原普通成功入口不能绕过归档门禁。

收尾另发现并修复旧候选竞态：普通补登 actor 可能在交接前已经读到 DTO，随后被新的 ARCHIVING 执行 fence 拒绝。仅从扫描列表排除新 job 不够，旧 actor 的异常处理仍可能中止它。普通 reclaim/abort/abandon/defer 的更新条件现同样排除独立归档持有的对象，只有正在持有准确归档 token 的 actor 才能释放自己的逐文件 token。定向测试验证旧候选没有破坏 STORED 字节，独立归档仍能完成。

对象保留同步纳入已有机制：未结束且 deadline 未到的归档保护选定 publication 与待交付正文，普通补登队列不得抢占它们；文件 GC、正文 GC 在候选/claim/引用复查处尊重该保护。即使逐文件 lease 失效，存储容量预留和路径互斥仍计算归档保留，不能借交接绕过配额或覆盖原路径。保留是有界的，并不延长原文件恢复的硬 deadline；结束后回到原正文 grace 与文件 GC/tombstone 回收策略。

### 18.4 配置与用户交互

```yaml
saas:
  file-store:
    workspace-archive-enabled: true
    workspace-archive-lease-seconds: 60
    workspace-archive-deadline-seconds: 900
    workspace-archive-max-attempts: 8
    workspace-archive-retry-seconds: 5
    workspace-archive-fixed-delay-seconds: 2
```

对应环境变量为 `SAAS_WORKSPACE_ARCHIVE_ENABLED`、`SAAS_WORKSPACE_ARCHIVE_LEASE_SECONDS`、`SAAS_WORKSPACE_ARCHIVE_DEADLINE_SECONDS`、`SAAS_WORKSPACE_ARCHIVE_MAX_ATTEMPTS`、`SAAS_WORKSPACE_ARCHIVE_RETRY_SECONDS`、`SAAS_WORKSPACE_ARCHIVE_FIXED_DELAY_SECONDS`。非法预算在装配时失败；本批接管还要求已有文件补登策略启用。禁用新交接不会让已经接收的 job 永久失去后台收敛；后台仍按原 deadline 完成或终止它们。

HTTP 接管成功发出 `run_archiving`，携带准确 Run/session/job ID，不发最终 RUN_FINISHED，也不伪装成模型恢复。前端显示归档状态；新会话同样保存 session 关联，刷新页面会从节点状态恢复等待。待 Run 终止后自动加载持久历史及产物，并保留当前输入草稿。等待不再次提交任务；状态、任务列表与窄屏图标操作使用原设计系统。修正已有恢复提示与答复挤在同一行的问题，移动端不隐藏归档提示。

### 18.5 验证证据

本批新增 **21 项 Java 测试**：文件发布/归档 **16 项**、Worker **2 项**、Harness 交接 **2 项**、恢复分类 **1 项**。完整功能 reactor 返回 **0**（该轮文件发布/归档 66/66）；之后补强截止时间、GC claim、重新授权、旧候选竞态和生命周期一致性，最后专项再次返回 **0**：文件发布/清单/归档 **68/68**、Worker **5/5**、Harness durable executor **8/8**、恢复分类 **7/7**、编排服务 **3/3**、DDD/MyBatis 边界 **11/11**、真实 MyBatis 领域仓储 **2/2** 均通过。此前完整回归中正文存储 **14/14** 通过，真实 MinIO 项显式启用；以上列出的专项无跳过。不把 reactor 其他环境相关可选跳过计作外部集成已经实测。

覆盖：完整扫描/持久源准入、已发布与待补登混合清单、旧业务租约/旧成功入口不能续期或成功提交、旧归档 token、锁等待后的重新授权、取消/代际撤销及 AgentRun 收尾、配额/路径保留、正文完整性故障与 GC claim 保护、目录重试时的逐文件 token 释放、退避不阻碍 deadline 终止、旧普通补登候选不能中止或抢占归档、业务验收失败不交付、已释放租约的快照元数据更新，以及伪造员工不能消费或撤销另一个归档 job。

真实 PG/Redis/MinIO/OpenSandbox 根门禁返回 **0**：基础任务流程 **13/13**、新增归档故障恢复 **4/4**、会话代际/收据验证 **5/5**、总门禁 **10/10**，FAIL 均为 0。新脚本仅允许本机地址，并对独立测试 Run 的 RunArtifact 插入暂时注入 SQLSTATE 40001；观察 ARCHIVING 和原执行租约失效后撤销故障，验证自动补登完成、唯一业务 attempt、唯一 execute 工具调用、模型调用次数不增长、READY 完整清单、工作区快照、可下载产物与唯一最终回复。另验证 FORCE RLS、八项独立角色权限与外组织不可读取，临时触发器/函数均清理。它是受控收据提交故障，不是断开整个 PG/MinIO 服务或 JVM 强杀试验。

前端生产构建通过；新增 Playwright/Chrome API 夹具在 **1440×1000**、**390×844** 两种尺寸验证：归档状态、刷新恢复、自动载入最终答复/产物、等待时禁止再发起任务、后台完成保留草稿、无页面脚本错误与无横向溢出。截图已逐张检查，移动端任务详情使用图标按钮，归档状态与正文各占独立行。这是浏览器交互契约测试，不把夹具和真实 API 门禁拼成“真实后端浏览器全量验收”。

最终命令与日志：

```bash
SAAS_RUNTIME_BODY_MINIO_TEST=true mvn -o \
  -pl agentscope-saas/agentscope-saas-app -am -Djacoco.skip=true \
  '-Dtest=*,!AgentPerformanceTest' -Dsurefire.failIfNoSpecifiedTests=false \
  spotless:apply test -q
SAAS_MODEL_TYPE=scripted APP_PORT=18082 \
  START_DOCKER_DEPS=false START_DOCKER_DAEMON=false \
  SANDBOX_GATE_SESSION_GENERATION=true SANDBOX_GATE_WORKSPACE_ARCHIVE=true \
  SAAS_RUNTIME_ARCHIVE_INLINE_MAX_BYTES=256 \
  SAAS_RUNTIME_ARCHIVE_CHECKPOINT_INLINE_MAX_BYTES=65536 \
  SAAS_RUNTIME_ARCHIVE_CHECKPOINT_MAX_JSON_BYTES=4096 \
  SANDBOX_SMOKE_REQUIRE_OFFLOAD=true \
  bash agentscope-saas/agentscope-saas-app/scripts/start-opensandbox-local.sh --smoke
# NODE_PATH 使用本机已有 Playwright 依赖路径，无需下载浏览器。
BROWSER_CHANNEL=chrome node agentscope-saas/agentscope-saas-app/scripts/workspace-archive-ui-smoke.cjs
```

日志：`/private/tmp/chugou-archive-lease-functional-tests-final.log`（完整回归）、`/private/tmp/chugou-archive-lease-closure-verified.log`（最后收尾专项）、`/private/tmp/chugou-archive-lease-opensandbox-gate-final.log`（强化后重新运行的真实门禁）、`/private/tmp/chugou-archive-ui-smoke-final-verified.log`、`/private/tmp/chugou-archive-ui-build-verified.log`。截图：`/private/tmp/chugou-workspace-archive-ui/archiving-{1440,390}.png`、`completed-{1440,390}.png`。首轮 H2 JSON 字符串断言、运行期间编辑导致的中间 Spotless 检查、测试夹具 `org` 变量对包名的遮蔽，以及浏览器可执行路径/夹具拦截/文本选择器失败均已修正，不计入最终通过。真实门禁根进程的成功结果不以清理时子 Maven 的 143 退出码替代。

验证后确认临时故障触发器/函数及未结算归档 job 均为 0；应用 18082 和前端 5190 均无监听。本轮从停止状态启动的 PG/Redis/MinIO/OpenSandbox 四个容器恢复为 exited，镜像、容器、数据卷和 Docker Desktop 保留，其他服务未操作。

### 18.6 尚未闭合

1. **R02 剩余**：缺持久字节时的 Provider/独立不可变快照 retention、计算完成到 durable result 交接之间的强杀窗口、其他最终答复/状态提交异常路径、运行中断后已冻结输出的独立归档调和，以及真实多副本/进程崩溃验证。准确清单恢复及多次投影/路径变更问题在后续第 19 节处理。本批不能安全接管未完成扫描或仅保存在易失临时文件中的数据，也没有把正常即时发布改成全任务强制独立结算。
2. **R06/R09**：冻结发布清单不替代执行前输入版本/任务工作集，更不替代具体业务的确定性验收与独立只读验证器。本批保留现有 CompletionGate，但不宣称它能判断“要求三份报告却只生成两份”的全部业务语义。
3. **后续工程**：全链路流式 IO、快照临时盘并发容量/强杀清理、历史清单/收据/归档证据保留与管理处置、文件式记忆后台提炼/水位和个人/岗位治理、安全人工重试/副作用对账、压测/多副本/灾备矩阵，仍依文档 37 推进。JWT、企业 IAM 和内部模型部署继续遵循此前延期范围。

本批只使用 scripted 模型与真实 OpenSandbox，不验证 CubeSandbox、E2B 或外部付费模型；沙箱 Provider 仍是部署侧配置。已完成批次的变更尚未提交或推送。

## 19. 第十六批：准确清单驱动的检查点恢复

### 19.1 问题与决策

同一个 attempt 可以因请求内恢复、多次释放投影而产生多份冻结清单。RunArtifact 是不可变历史事实，不能作为某个检查点的文件集合：同路径的多个版本会造成重复路径；被删除、移走或替换的路径也可能被错误带回。只选择“最新 READY 清单”同样不准确，它未必就是沙箱租约已经提交的那份检查点。

正常发布与独立归档统一写入明确绑定清单的目录引用：

```text
workspace-catalog://runs/<run-id>/attempts/<attempt-id>/manifests/<manifest-id>
```

`workspace_version` 仍是路径、不可变 version ID 和内容摘要的整体指纹，`plan_hash` 仍是预期扫描清单指纹，二者不混用。引用是逻辑目录标识，不是 Provider 地址或 MinIO key；使用 URI 解析并验证精确 Run/attempt 和规范格式，不允许任意 URL、查询参数或片段。

本批复用 V44 的清单事实，不新增表、数据库或数据源。Domain 扩展读取端口；DAL 以 MyBatis 实现准确 ID/员工/Run/task/attempt 查询，并复用现有唯一索引和 FORCE RLS；App 负责恢复编排与校验，不引入 JDBC 或业务 SQL。

真实门禁还发现普通发布的持久关联缺口：Harness 的 `ensureSessionDefaults` 派生 RuntimeContext，运行期间新增的 `SandboxLeaseContext` 没有回写外层，而发布原先仅依据外层内存绑定决定是否写检查点，可能出现 READY 文件清单却没有对应的租约引用。普通发布现在复用沙箱领域服务，内存绑定存在时核对指定租约；缺失时按准确 attempt 查询已持久租约，并检查组织、员工、Run、task 和 attempt 全部一致后写引用。资源 lease owner 与业务执行 owner 是不同职责，不混作同一个授权。已释放租约允许补写元数据，不激活或续期；明确传入的错误/不存在绑定不得回退或更新其他任务的租约。确实没有资源租约的目录发布维持原可用性，本批不凭空创建资源。

### 19.2 恢复流程与授权

1. 仅在 RUN_ISOLATED、ATTEMPT_ISOLATED、DEDICATED_SANDBOX 的合法后继 attempt 恢复；共享/NONE 模式维持原行为。从数据库解析当前执行范围，必须匹配调用 Worker 明确传入的 lease owner。旧 Worker 不能从数据库重新读取新 owner 后冒用它。
2. 短事务锁定 session → Run → 当前 attempt，校验员工、Agent、session 代际、取消状态、节点/AgentRun 状态和执行租约。取得同一 task 的前序检查点；读取准确引用的 READY 且扫描完整清单，而不是查询当前最新扫描。锁等待后重新取时间校验，不能用等待前的时间放行已过期租约。
3. 校验清单归属、数量、总字节、安全路径和扫描指纹；每条 entry 必须通过 publication → intent → RunArtifact → file_version → Outbox 的精确持久关联，匹配路径、大小、摘要、任务和原执行 owner。缺收据、缺版本、范围失配或工作区版本不一致均拒绝，不从历史产物补猜。
4. 关闭 SQL 事务后读取选定不可变版本。校验真实内容 SHA-256、目录大小、清单大小以及恢复容量，再组装 Provider 中立的恢复计划。修复旧 `readOnly` 外层事务与 `FileCatalogService.readVersion(NEVER)` 冲突；对象 GET 不持有数据库锁。
5. GET 完成后再进入短事务，校验当前 Worker 授权、会话代际、原检查点引用/版本和准确清单集合是否仍成立。取消、重置、租约转移、读取期间检查点替换均不能返回可用计划；失败不生成新的业务 attempt。
6. 沙箱启动时只要存在恢复计划，就不再先加载当天的整个可变远程投影。文件来自选定检查点；显式空清单同样不加载旧输出、后来上传的文件或当前可变记忆。没有检查点的新任务仍保持原初始化流程。任务重试不借恢复偷偷改用新的输入版本；完整执行前任务工作集仍属于 R06。

因此，同一 attempt 的旧产物继续保留用于审计，但不参与已选检查点恢复；目录后来改名不改变原检查点路径/版本；出现后续未完成扫描，不会覆盖或阻止已绑定的旧 READY 快照。

### 19.3 兼容与边界

- 仅当该前序 attempt 完全不存在 V44 清单时，兼容旧未绑定清单的检查点，仍核对 Run/task、员工版本读取权限、路径、内容与整体指纹。重复历史路径或版本不一致仍拒绝，不尝试猜测。
- 已存在清单却保存旧未绑定引用、明确引用不存在/未完成清单或准确收据缺失时拒绝恢复。升级中的此类异常检查点需重新通过正常发布产生明确引用，不能静默回退为全部历史产物。
- 本批不清空用户文件或整个持久 Volume，也不改变部署侧 Provider 配置。真实恢复验证针对新建 OpenSandbox；复用/恢复 Provider 自有快照或 Volume 时对既有残留文件的完整替换、隔离与清理仍需独立设计和故障验证，不能把“不再下载历史远程投影”宣传为所有持久卷残留都已解决。
- 工作区文件集合不等于模型上下文完整快照，也不等于确定性业务验收。缺持久字节 retention、流式传输、强杀与最终结果交接等 R02/R03 剩余仍未闭合。

### 19.4 验证

本批 Java 测试净增 **39 项**：恢复服务 **20 项**、文件发布/恢复集成 **10 项**（含真实 OpenSandbox 两种恢复）、沙箱租约绑定 **7 项**、Harness 生命周期 **2 项**。最终完整功能 reactor 返回 **0**，最后业务代码未经后续修改：恢复服务 **24/24**、文件发布/清单/归档/恢复 **78/78**、租约服务 **9/9**、生命周期 **14/14**、文件系统 **27/27**、工作区发布校验 **13/13**、DDD/MyBatis 架构边界 **11/11**、领域仓储 **2/2** 和正文存储 **14/14** 均通过；上述专项无跳过。真实 MinIO 正文测试显式启用，真实 OpenSandbox 恢复探针显式启用。性能测试被排除；其他环境相关可选跳过不视为已经验证的外部集成。

恢复覆盖同 attempt 的多版本和历史路径排除、准确绑定的旧 READY 清单在新扫描未完成时仍可读取、目录后来改名、明确空清单、缺/坏收据、目录摘要与真实字节不一致、容量和安全路径、会话重置、旧 owner、读取期间检查点替换及员工隔离。对象存储夹具明确断言 GET/PUT 不处于 SQL 事务。真实 OpenSandbox 探针使用 MyBatis/H2 与受控对象存储夹具构造目录事实，再通过生产生命周期中间件在新建 Provider 内验证选定文件、空清单及不载入可变远程历史输出；它不是 PG/MinIO 整链路恢复或浏览器验收。

真实 PG/Redis/MinIO/OpenSandbox 发布门禁最终根进程返回 **0**：基础任务 **13/13**、归档恢复 **4/4**、会话代际/清单绑定 **5/5**、总门禁 **10/10**，FAIL 均为 0。新增 SQL 断言核对正常发布及归档的租约 URI 精确包含 READY manifest ID，并保留不可变版本/产物/Outbox、完整清单、员工隔离与 FORCE RLS 验证。仍使用 scripted 模型，无外部付费调用；本批未改前端，也未重新做浏览器全量页面验收。

最初受限 JVM 附加导致 Mockito 失败，后已获授权正常执行；完整回归发现旧文件 GC 夹具未安装 V45，已补齐而不是弱化生产归档保护；强化的正常检查点关联门禁揭示派生上下文绑定缺失，已修复持久租约关联并重新运行完整回归和真实门禁。中间失败日志不计入最终通过证据。

```bash
SAAS_CHECKPOINT_OPENSANDBOX_TEST=true SAAS_RUNTIME_BODY_MINIO_TEST=true mvn -o \
  -pl agentscope-saas/agentscope-saas-app -am -Djacoco.skip=true \
  '-Dtest=*,!AgentPerformanceTest' -Dsurefire.failIfNoSpecifiedTests=false \
  spotless:apply test -q
SAAS_MODEL_TYPE=scripted APP_PORT=18082 \
  START_DOCKER_DEPS=false START_DOCKER_DAEMON=false \
  SANDBOX_GATE_SESSION_GENERATION=true SANDBOX_GATE_WORKSPACE_ARCHIVE=true \
  SAAS_RUNTIME_ARCHIVE_INLINE_MAX_BYTES=256 \
  SAAS_RUNTIME_ARCHIVE_CHECKPOINT_INLINE_MAX_BYTES=65536 \
  SAAS_RUNTIME_ARCHIVE_CHECKPOINT_MAX_JSON_BYTES=4096 \
  SANDBOX_SMOKE_REQUIRE_OFFLOAD=true \
  bash agentscope-saas/agentscope-saas-app/scripts/start-opensandbox-local.sh --smoke
```

最终日志：`/private/tmp/chugou-checkpoint-manifest-functional-closure.log`、`/private/tmp/chugou-checkpoint-manifest-opensandbox-gate-verified.log`。脚本语法和 Python 编译检查通过。验证后临时故障触发器/函数和未结算归档 job 均为 0；临时应用已停止。本轮启动的四个测试容器恢复关闭，镜像、容器、数据卷和 Docker Desktop 保留，不操作其他服务。

### 19.5 后续顺序

1. R02：缺持久字节时的保留策略、冻结输出的中断调和、计算完成至 durable result 之间的强杀窗口、其余最终答复/状态提交失败，以及复用 Provider 快照/Volume 的精确替换边界。
2. R06/R09：执行前冻结输入版本和任务工作集、确定性业务产物验收与独立只读验证器。
3. R03 与 B3-B8：流式 IO/临时盘治理、历史清单及归档证据保留和管理处置、安全人工重试/副作用对账、文件式记忆后台提炼/水位及岗位/个人治理、多副本与灾备门禁。

继续遵守 JWT、企业 IAM、内部模型部署及 PromptGuard 的既定延期范围；本批不验证 CubeSandbox/E2B，不调用付费模型，不自动提交或推送。

## 20. 第十七批：计算后提交失败不重跑任务

### 20.1 闭合范围

第十五批已经区分工作区发布失败与模型失败，但成功计算后的最终答复保存、Worker 成功状态提交和网页协调器状态提交仍位于原执行失败路径中。Worker 收到成功结果后，`succeed` 抛错可能进入普通任务重试；网页端则可能把已经执行过工具的任务直接判失败。异常带有 ModelException 因果链时，风险更明显。

本批闭合上述提交边界，保留正常即时发布路径。只有已经返回计算结果、扫描完整、文件已有不可变材料且仍持有有效执行授权的失败，才复用独立 ARCHIVING 交接；没有可封存材料不承诺归档自动恢复，也不重新运行已经完成的计算。本批没有实现缺字节时的 Provider/独立快照保留协议。

### 20.2 处理机制

1. 编排执行端口增加 `TaskResultCommitException`，明确表示计算已返回、失败属于结果提交。恢复分类先检查完整的受限因果链，再识别模型错误；外层 ModelException 不能覆盖内层结果提交或归档边界。
2. Harness 在计算返回之后，分别保护交付范围查询、检查点发布、输出序列化和最终答复保存。可以安全交接的发布/答复异常使用原完整 ContentBlock 答复，不用有界任务摘要代替完整内容；交付范围无法确认或序列化本身失败时明确终止，不猜测是否应向用户发送答复。
3. `ExecutionResult` 保留旧单参数构造器，并增加仅本次调用内使用的提交故障回调。Worker 提交成功状态失败时，回调只能交接已经计算出的内容，不再次调用执行器。回调和内存上下文不写入 PG/JSON，不作为跨进程恢复事实；真正的跨进程交付仍由完整正文与 V45 job/items 承担。
4. Worker 遇到提交异常，先以准确 attempt ID 与 lease owner 查询已提交成功事实。确认只是成功提交的响应丢失时直接收敛，不创建第二个归档 job；首次查询与交接之间发生成功提交的竞态，在决定终止前再次核对。显式返回租约失效则不使用旧 owner 的回调。旧执行器没有回调、回调失败或结果为空时使用终止策略，不落入普通 `fail/recover` 的业务重试。
5. 网页端把检查点、最终答复和协调器成功结算纳入同一个结果提交异常边界。正常成功仍发 RUN_FINISHED；已成功提交但响应丢失时核对 attempt 事实后发正常完成；需要归档时发原 `run_archiving` 事件，由既有网页轮询机制等待持久交付，不发送错误的最终完成事件。
6. 归档交接自身也可能已提交但确认响应丢失。`defer` 保留本次准备的 job 标识；收到提交异常后，只读取这个已知 ID 并核对完整执行范围、manifest、正文 ID/大小和可接管/已完成状态。符合时返回该 job，不按“当前最新任务”猜测，不制造新 job，不撤销已完成的接管。没有匹配事实或读取仍失败时保留原异常，不能假报接管成功。
7. 交接成功继续使用原独立租约、deadline、次数限制、会话代际/取消门禁、幂等答复、文件收据和 CompletionGate。状态提交前已经保存的答复，在归档中仍只保留一份；归档失败不重新运行模型或工具。业务验收本身的既有语义没有被绕过。

DDD/MyBatis 边界保持：编排端口不依赖 App、Spring 或 ORM；新增成功事实查询经 Domain Port 与 DAL 管理角色 MyBatis Mapper 执行，只按内部可信的准确 attempt/owner 读取，不新增公开的越租户查询 API；App 不写业务 SQL。复用既有 V45 表和两种数据库角色，无数据库迁移或第三个数据源。

### 20.3 边界与剩余风险

- 本批不能保证计算返回与 durable result 交接之间遭遇 JVM 强杀时仍能完整交付；提交故障回调不是持久队列。
- PG/对象存储整体不可用、执行租约已失效、材料缺失或扫描不完整时，不能建立可信交接。尽力登记终止并避免当前调用普通重试，不等同于已经解决整库不可用后的过期调度、所有跨进程竞态或断电窗口。
- 不改变沙箱部署侧切换；不以延长业务 lease 代替独立归档，也不在没有持久材料时仅凭内存标志阻止释放。
- 不实现 Provider/独立不可变快照的保管、取回、TTL、配额与回收；不实现任务输入工作集、确定性业务验收、人工安全重试或副作用对账。以上仍按文档 37 和第 19.5 节推进。

### 20.4 验证

最终完整功能 reactor 返回 **0**；Java 测试净增 **19 项**：Worker **8 项**、Harness **5 项**、恢复分类 **2 项**、MyBatis/H2 文件发布与交付集成 **4 项**。最后 Worker **13/13**、Harness **13/13**、恢复分类 **9/9**、文件发布/归档/恢复/交付 **82/82**、DDD/MyBatis 边界 **11/11**、领域仓储 **2/2**、正文存储 **14/14** 均通过。真实 OpenSandbox 恢复与 MinIO 正文探针显式启用；这些专项无跳过，性能测试和其他环境相关可选集成不计作已验证。

覆盖提交失败后执行器调用次数不增长、原构造器兼容、嵌套模型异常仍不重试、显式 lease loss、空结果、提交确认丢失、交接期间成功提交的竞态，以及真实事务提交后的确认异常。数据库交付用例验证任务摘要被截断时完整答复末尾仍可交付、只产生一个 attempt/回复/文件收据、已有回复不会重复保存。归档确认丢失通过事务管理器在真实 H2 commit 后抛错验证，不用假设未提交的内存对象代表成功。

新增 `result-commit-smoke.py`，由 `SANDBOX_GATE_RESULT_COMMIT=true` 纳入本机 OpenSandbox 发布门禁。分别对准确测试 Run 的 assistant 消息 INSERT 和 attempt 成功状态 UPDATE 暂时注入 PostgreSQL SQLSTATE 40001，观察 `run_archiving`、业务 lease 失效和 READY 清单，撤销故障后核对后台交付、唯一 attempt/tool/reply、模型调用次数不增长，以及真实文件下载和历史 API。脚本不允许非本机目标，并在 finally 清理故障触发器/函数和测试 Agent。

真实 PG/Redis/MinIO/OpenSandbox 门禁最终根进程返回 **0**：基础任务 **13/13**、已材料化输出归档 **4/4**、新增结果提交故障 **2/2**、会话代际与清单关联 **5/5**、总门禁 **11/11**，FAIL 均为 0。两种提交故障都在生成 READY 文件清单后交接，恢复期间模型/工具调用次数未增长；状态提交故障前已经保存的最终回复没有重复插入。管理诊断无阻断聊天的降级，Prometheus 计量正常。

```bash
SAAS_CHECKPOINT_OPENSANDBOX_TEST=true SAAS_RUNTIME_BODY_MINIO_TEST=true mvn -o \
  -pl agentscope-saas/agentscope-saas-app -am -Djacoco.skip=true \
  '-Dtest=*,!AgentPerformanceTest' -Dsurefire.failIfNoSpecifiedTests=false \
  spotless:apply test -q
SAAS_MODEL_TYPE=scripted APP_PORT=18082 \
  START_DOCKER_DEPS=false START_DOCKER_DAEMON=false \
  SANDBOX_GATE_SESSION_GENERATION=true SANDBOX_GATE_WORKSPACE_ARCHIVE=true \
  SANDBOX_GATE_RESULT_COMMIT=true \
  SAAS_RUNTIME_ARCHIVE_INLINE_MAX_BYTES=256 \
  SAAS_RUNTIME_ARCHIVE_CHECKPOINT_INLINE_MAX_BYTES=65536 \
  SAAS_RUNTIME_ARCHIVE_CHECKPOINT_MAX_JSON_BYTES=4096 \
  SANDBOX_SMOKE_REQUIRE_OFFLOAD=true \
  bash agentscope-saas/agentscope-saas-app/scripts/start-opensandbox-local.sh --smoke
```

最终日志：`/private/tmp/chugou-result-commit-functional-verified.log`、`/private/tmp/chugou-result-commit-opensandbox-gate.log`。初轮新集成夹具未启用原文件补登策略，导致交接按设计拒绝，已修正夹具并完整重跑，不弱化生产准入。脚本语法、Python 编译及差异空白检查通过。验证后故障触发器/函数和未结算归档任务均为 0，临时应用已停止，四个本轮测试容器恢复关闭；保留镜像、容器、数据卷和 Docker Desktop，不操作其他服务。本批未改前端，HTTP/SSE 故障脚本不视为新的浏览器全量验收；SQL 事务故障和模拟确认异常不等同于整库断开或 JVM 强杀验证。

### 20.5 后续顺序

1. R02 缺持久字节：建立独立不可变快照或有界 Provider retention 的准入、存储引用、恢复授权、配额、TTL 和回收协议；完整字节未交接前不能销毁唯一来源。
2. R02 强杀/中断：计算结果封存与释放的顺序、冻结输出调和、整库故障后的过期调度、复用 Provider 快照/Volume 的精确替换，以及多副本和真实崩溃验证。
3. R06/R09 与 R03：冻结输入版本/任务工作集、确定性产物验收和独立只读 Verifier、流式传输与临时盘治理。
4. 其余 B3-B8：安全人工重试/副作用对账、文件式记忆后台提炼/水位、岗位与个人记忆治理、生产部署/压测/灾备门禁。

JWT、企业 IAM、内部模型部署和 PromptGuard 继续暂缓；只验证 OpenSandbox，不验证 CubeSandbox/E2B，不使用付费模型。本批变更未提交或推送。

## 21. 第十八批：不可变字节源保管与缺文件归档恢复

### 21.1 闭合范围

释放时已有完整冻结 TAR，但逐文件传输/预留失败可能尚无 STORED/PUBLISHED 文件对象。以前归档拒绝这类缺字节任务，随后临时 TAR 和沙箱都被释放。本批在逐文件发布前将同一份冻结 TAR 保管到独立对象命名空间，再允许独立归档恢复原清单内尚未材料化的文件。恢复不启动新沙箱，不运行模型或工具。

这不是对 Provider 最新快照或会话可变目录的盲目重放，也不是在 PG 中保存巨型 JSON/Base64。PG 只记录授权、清单关联、摘要、容量、文件版本基线和回收状态；生产字节由 MinIO/S3 流式保存。已有材料化输出仍可以沿用原 V45 路径，不强制依赖快照恢复。

### 21.2 存储与授权

1. Harness 在完整扫描、安全路径、数量、容量和摘要预检之后增加 `onProjectionCaptured` 回调，传入只在当前调用有效的冻结 TAR 路径；回调位于所有逐文件上传之前。路径不是持久引用，原临时文件仍在 finally 清理。
2. V46 新增 `workspace_sources` 和 `workspace_source_files`。快照与准确 manifest、org/user 绑定，通过组合外键和 FORCE RLS 保护；不级联绑定会被删除的 Run/session。源 UUID、对象键、大小和 SHA-256 固定，不能用部署变化或“最新快照”替代。
3. 对象键位于 `runtime/workspace-sources/org=.../user=.../<source>.tar`，不加入 files/file_versions、附件、下载清单或用户预览 API。源保管、用户上传/任务产物与记忆文件的读写授权保持分离。
4. 短事务锁组织配额与原会话/Run/attempt，确认最新清单和有效执行 owner，然后预留 STAGING 记录、冻结每个目标路径的 file ID/version/status。对象 PUT 和完整读回验证在事务外进行；校验后再以有效执行授权将源标记 RETAINED。
5. SCANNED 表示完整字节清单已冻结并已保管，并不表示任务交付成功。READY 仍要求全部文件版本、任务产物和 Outbox 原子收据通过原门禁。正常完成仍走原同步文件发布路径。
6. 归档准入在原授权有效时锁定 RETAINED 源。V46 允许 archive item 在缺字节时暂时没有 publication ID；只有存在完整源时才允许该状态，未保管源不能单凭元数据声明自动恢复。归档 deadline 不晚于源 deadline，原业务执行 lease 仍按原设计撤销。
7. 恢复 worker 以独立 job token、session generation 和取消门禁授权读源；数据库事务外限量复制到私有临时盘，核对整个 TAR 大小/摘要，再完整验证所有条目的路径、类型、唯一性、数量、长度及摘要，随后只发布缺失条目。已 PUBLISHED/STORED 文件继续复用原收据/补登，不生成重复版本或产物。
8. 缺文件的 publication 创建、archive item 绑定与 intent 在同一个短事务内进行。DAL 条件写同时验证 job token/lease/deadline、准确执行范围、最新 manifest、RETAINED 源、清单大小/摘要和冻结版本基线。用户后续上传/修改同一路径时拒绝覆盖；更换 token、取消或代际撤销后不能继续发布。
9. 归档 PUT 失败可以撤销自己尚未 STORED 的预留，再用自己的有效 token 替换 ABORTED/DELETED 或已过期 STAGED 记录。不能忽略其他任务的预留，也不能替换已材料化文件；最终结算继续使用 V45 完成门禁、幂等回复和有界次数。

App 只依赖 Domain Port；持久化 SQL 均在 DAL MyBatis Mapper，租户角色处理业务授权，管理角色仅执行系统回收。没有增加数据源、JDBC 业务代码或用户侧沙箱切换选项。

### 21.3 容量、期限与回收

- `workspace-source-enabled` 默认 true，仅当文件目录和新归档准入同时启用时捕获；关闭新捕获不停止已有归档/回收。禁用后的缺字节任务明确拒绝自动归档。
- 单份 TAR 默认上限 320 MiB，硬上限 512 MiB；组织保管预留默认 2 GiB，包括 STAGING、RETAINED、COLLECTING，不能因上传超时或回收失败释放预算。预算由组织行锁串行准入，独立于用户逻辑文件配额。
- 源期限为捕获时归档 deadline 预算加 600 秒交接余量，默认 1500 秒；不随业务重试无限延长。归档另有原有独立 lease、次数和 deadline。
- 每 60 秒扫描到期源。候选扫描与带 token 的回收 claim 均排除 deadline 内的 PENDING/RUNNING 归档引用，防止旧候选在交接后删除源。读取/上传不持有 SQL 锁。
- 回收只按记录中的 org/key/backend 删除私有对象。失败记录保留并延迟重试，不从组织预算中消失；删除后保留每日复查的 COLLECTED 墓碑，处理超时上传晚于删除完成的孤儿对象。当前不包含历史源元数据自身的归档删除策略。
- MinIO/S3 新增流式路径上传与有界流式下载，不为完整 TAR 分配 byte[]。PG/测试 fallback 明确限制为 32 MiB，不将大快照塞入 PG；归档条目仍按单文件上限分配，R03 的全链路流式与临时盘总配额尚未全部完成。

```yaml
saas:
  file-store:
    workspace-source-enabled: true
    workspace-source-max-bytes: 335544320
    workspace-source-max-org-bytes: 2147483648
    workspace-source-gc-fixed-delay-seconds: 60
```

对应环境变量为 `SAAS_WORKSPACE_SOURCE_ENABLED`、`SAAS_WORKSPACE_SOURCE_MAX_BYTES`、`SAAS_WORKSPACE_SOURCE_MAX_ORG_BYTES`、`SAAS_WORKSPACE_SOURCE_GC_FIXED_DELAY_SECONDS`。生产采用 MinIO/S3；初始捕获需要额外完整 PUT/GET 完整性校验，部署时应为网络、临时盘和执行 deadline 预留相应预算。

### 21.4 验证

最终完整功能 reactor 返回 **0**，最后一次收紧对象键校验后已重跑。Java 测试净增 **10 项**，均为真实 MyBatis/H2 集成：完整缺字节恢复、混合 PUBLISHED/缺字节恢复、PUT 失败再次恢复、整个源损坏拒绝、较新用户上传保护、取源时会话撤销、源到期/回收拒绝、伪造 token 拒绝、跨 attempt 源容量预留、旧 GC 候选交接后不能 claim。文件发布/归档/恢复集成最终 **92/92**；Harness 文件系统 **27/27**，补充捕获先于文件传输、引用同一冻结清单和临时源删除的断言；DDD/MyBatis 边界 **11/11**、领域仓储 **2/2**、正文存储 **14/14** 均通过。真实本地 OpenSandbox 和 MinIO 可选探针已显式启用；这些专项无跳过，性能测试和其他环境相关可选集成不计作已验证。

真实 PG/Redis/MinIO/OpenSandbox 门禁根进程返回 **0**：基础任务 **13/13**、已材料化输出归档 **4/4**、计算后提交故障 **2/2**、新增缺字节源恢复 **6/6**、会话代际 **5/5**、总门禁 **12/12**，全部 FAIL=0。新增 `SANDBOX_GATE_WORKSPACE_SOURCE=true` 使用 `workspace-archive-smoke.py --missing-bytes`，对准确测试 Run 的 file_publications INSERT 注入 SQLSTATE 40001，阻止任何文件字节材料化到目录发布对象：

- 在沙箱释放后确认 RETAINED 源、archive item 无 publication、原 attempt ARCHIVING 和业务 lease 撤销，不把 Redis/Provider 最新快照当恢复凭据。
- 撤销故障后，只从独立源恢复，核对原始唯一 attempt/tool/reply、模型调用次数不增长、准确 READY 清单与持久检查点，以及用户文件下载、完整最终答复和历史 API。
- 核对 V46 两表的 FORCE RLS、app 角色权限与外组织不可读；原 V45 两表仍通过相同门禁。
- 仅将已完成测试源的 eligible_at 提前，等待真实后台 GC 从 MinIO 删除并记为 COLLECTED，再下载已发布用户文件确认内容不变。没有删除业务文件、修改活跃任务或放宽生产 deadline。

```bash
SAAS_CHECKPOINT_OPENSANDBOX_TEST=true SAAS_RUNTIME_BODY_MINIO_TEST=true mvn -o \
  -pl agentscope-saas/agentscope-saas-app -am -Djacoco.skip=true \
  '-Dtest=*,!AgentPerformanceTest' -Dsurefire.failIfNoSpecifiedTests=false \
  spotless:apply test -q
SAAS_MODEL_TYPE=scripted APP_PORT=18082 \
  START_DOCKER_DEPS=false START_DOCKER_DAEMON=false \
  SANDBOX_GATE_SESSION_GENERATION=true SANDBOX_GATE_WORKSPACE_ARCHIVE=true \
  SANDBOX_GATE_RESULT_COMMIT=true SANDBOX_GATE_WORKSPACE_SOURCE=true \
  SAAS_RUNTIME_ARCHIVE_INLINE_MAX_BYTES=256 \
  SAAS_RUNTIME_ARCHIVE_CHECKPOINT_INLINE_MAX_BYTES=65536 \
  SAAS_RUNTIME_ARCHIVE_CHECKPOINT_MAX_JSON_BYTES=4096 \
  SANDBOX_SMOKE_REQUIRE_OFFLOAD=true \
  bash agentscope-saas/agentscope-saas-app/scripts/start-opensandbox-local.sh --smoke
```

最终日志：`/private/tmp/chugou-source-functional-final.log`、`/private/tmp/chugou-source-final-scoped.log`、`/private/tmp/chugou-source-opensandbox-gate.log`。过程中发现并修复恢复 PUT 失败的原路径预留阻塞，以及 Harness 带斜杠路径与 DAL 标准化清单的指纹兼容；没有放宽普通调用的路径互斥或完整性门禁。初次默认沙盒 JVM attach 受限，测试未运行，使用已授权的本地 JVM attach 后重跑；编译/格式修正后的最终回归和门禁均成功，不将早期失败计作验收。

脚本 bash 语法、Python 编译及差异空白检查通过。验证后故障触发器/函数和活跃归档任务均为 **0**，没有运行中的临时沙箱，应用已退出。仅关闭本轮原先停止的四个服务容器，保留镜像、容器、数据卷和 Docker Desktop，不操作其他服务。本批未修改前端；HTTP/SSE 故障脚本不是新的浏览器全量验收，也不是整库中断、真实进程崩溃、多副本或大容量压测证明。

### 21.5 明确剩余边界

1. 只有已完成不可变源交接的缺字节任务具备本批恢复能力。对象存储整体中断、源超限/配额拒绝、保管未提交或无支持的大文件后端时，会继续尝试原正常逐文件路径；正常收据全部提交仍可成功，否则明确终止，不假报 ARCHIVING 恢复。**本批没有实现这种情况下的 Provider retention；唯一字节源仍在 Provider 而所有持久化不可用时，释放顺序的问题尚未全部闭合。**
2. 完整答复仍在计算返回之后才交接到 durable body/job。JVM 强杀、整库故障、捕获/交接中断与过期调度的调和不因有 TAR 就自动解决。不能把本批记作完整 R02 或跨副本崩溃恢复。
3. 复用 Provider 快照/Volume 的精确替换、R06/R09 输入工作集与确定性业务验收、R03 全链路流式/临时盘治理、历史清单保留、人工安全重试/副作用对账、记忆后台提炼/岗位治理和生产灾备门禁仍需继续。新缺字节恢复发布的是权威文件目录与准确任务检查点，不补写可变 BaseStore/Provider 镜像；后续任务必须从权威版本构建工作集，不能以可变镜像是否存在代替对准确产物的恢复。

JWT、企业 IAM、内部模型部署、PromptGuard 继续暂缓。本批仅验证本地 OpenSandbox，不验证 CubeSandbox/E2B，不调用付费模型，不自动提交或推送。

## 22. 第十九批：显式附件权威恢复与局部工作集发布保护

### 22.1 闭合范围

已有附件记录固定了 version ID，但运行时初始化仍读取可变 BaseStore/Provider 快照；确认暂停期间重新上传同名文件，可能使实际执行读到新版本。独立归档补齐目录文件后，下一任务也没有显式版本初始化通道。另外，附件路径原先临时取自 files 当前路径，文件改名后历史引用会变；恢复旧版输入再释放沙箱，旧工作副本还可能重新发布，覆盖用户的新上传。

本批解决显式附件的上述链路，不将用户持久文件域当作任务工作集。复用现有附件与版本记录，没有把文件正文或清单塞进消息 JSON。这里是 R06 的显式输入子范围，不是全部任务工作集功能已经完成。

### 22.2 绑定与取回

1. V47 给 file_attachments 增加独立 logical_path 字段与版本引用索引。新绑定同时固定 path/file ID/version ID；路径改名不改变历史消息引用。旧记录在迁移时按组织、员工、file ID 回填当时路径，无法反推迁移前已改名的原路径，不宣称重建历史。
2. 对话附件允许 inputs/ 与 outputs/ 用户文件，仍拒绝记忆、技能、runtime 及逃逸路径。开始 Run 时复用既有事务验证当前版本、文件归属并绑定到触发消息；确认恢复使用原 Run 的绑定，不接受确认请求中的新版本重新绑定。历史版本在运行中仍可读，但创建新附件时保持原有“必须为当前版本”的门禁。
3. WorkspaceInputRepository 查询仅选择本 Run 的触发消息附件及准确 task 绑定，以 limit + 1 限量读取；同时验证 org/user/agent/session/generation，并核对文件和版本所有者，不扫描员工所有文件来推断输入。子任务可以继承同 Run 触发输入；依赖产物自动清单尚未纳入本批。
4. WorkspaceInputService 短事务核对当前 Run/attempt/task、lease owner、会话代际和取消状态，并冻结本次读取的绑定事实。大小、总量、文件数、冲突路径、后端和摘要先校验；对象 GET 在事务外，以已授权的限额读取。读完再锁定并按新时间复查授权及全部绑定，损坏、撤销或读取期间变更都拒绝启动。
5. 初始化形成 Provider-neutral WorkspaceInputPlan，使用版本和摘要生成稳定指纹。文件是任务工作副本，不能改变持久化原版本；目前单文件读取硬上限 32 MiB，仍受任务总传输和数量预算约束。对象字节不写入 Runtime input JSON，授权和历史版本保留继续由 PG 附件引用承担。
6. 交互与 Durable Worker 均接入取回；显式输入使用 Run 隔离槽，原有更严格的 attempt 隔离可以保留。同任务重试的准确 checkpoint 与原绑定输入合并，输入路径以权威绑定为准，不回退到当前可变缓存；checkpoint 输出和进度仍保留。

DDD/MyBatis 边界不变：查询和附件列读写只在 DAL Mapper，Domain Port 不依赖框架；App 编排短事务和对象 IO。继续使用原两种数据库角色，无第三数据源。

### 22.3 系统资源与共享沙箱

- 显式输入调用只初始化固定系统资源 AGENTS.md、MEMORY.md、tools.json，以及 skills/、subagents/、memory/ 前缀，再加载明确绑定的文件。系统文件数/字节与输入共享预算。
- BaseStore 增加 key-only 前缀分页能力。Redis 使用 ZRANGEBYLEX，不 HGET 未选中文件正文；InMemoryStore 提供同等契约。不支持的适配器明确拒绝，不能静默回退全用户正文搜索。已知固定文件仍按直接 key 取回；系统资源本身的不可变版本锁定不属于本批。
- 父沙箱已加载的输入指纹和环境 ID 记录在调用上下文。子 Agent 借用时校验二者与请求一致，匹配时仍共享父沙箱，不新建。不同附加输入需要显式工作集准入，当前拒绝隐式借用错误文件，不声称已支持动态扩展。
- 显式输入要求隔离运行槽。当前未提供持久 Volume 的 Run 子目录适配，所以在 start 前明确拒绝这类输入与非隔离持久工作区组合，不删除卷数据、不冒充可安全覆盖。非 Volume Provider 保持接口中立；真实验证只做 OpenSandbox。

### 22.4 发布保护

1. 显式输入调用的 inputs/ 是工作副本，释放时不进入文件发布或归档清单，不回写用户已经更新的版本。输入副本中的临时修改不会被当作用户新上传。
2. 显式引用的 outputs/ 文件未改变时不重新发布；原地改变时发布为新的 outputs/revisions/ 路径，冻结新目标路径并走原版本/产物/Outbox 收据，不覆盖原引用或用户较新版本。用户应以实际产物收据路径读取结果。
3. 局部工作集不是员工全局文件的完整扫描结果。因此不按它做全局删除调和，也不覆盖用户全局投影清单；只发布本次扫描的系统变化和新产物。
4. 新发布清单中不含输入副本，所以 checkpoint 恢复必须与显式输入绑定合并；否则 fresh sandbox 上会缺输入。子 Agent 仍使用父沙箱现有工作副本，不用重复恢复覆盖父任务进度。
5. 显式工作集中的文件工具写入不再立即双写可变 BaseStore，避免绕过上述发布保护。工作副本先留在当前沙箱，随后按完整冻结清单发布系统变化与实际产物；旧调用与对话外上传保持原有行为。新增用例同时核对输入、已有输出与新输出不会在工具写入时污染持久缓存。

真实 5 MiB 输入验证暴露了 OpenSandbox 原适配器的传输缺陷：TAR 先 Base64 编码，按 4 KB 每块调用一次 command，导致约 1700 次请求；持久化又经受限 stdout 输出 Base64，存在截断风险。本批按[官方 execd 文件 API](https://github.com/opensandbox-group/OpenSandbox/blob/main/specs/execd-api.yaml)改用 metadata-first multipart 二进制上传和有界流式下载。平台与 execd 认证头保持原配置；不支持接口时明确失败，不回退慢速命令通道。

Provider 传输使用独立 UUID 远端 TAR 与私有本地临时文件，避免共享固定临时文件名。单份传输硬上限 512 MiB、单次文件 HTTP call deadline 300 秒；创建/解包 TAR 仍用少量命令，检查执行结果。下载核对 Content-Length（存在时）与实际字节限额，不经过命令 stdout；返回快照流关闭时删除本地临时文件，远端临时文件在 finally 清理。上层仍有 byte[]/内存 TAR，不能把这一修复计作 R03 已全链路流式或已有全局临时盘配额。

实测 execd 镜像要求 metadata part 是有文件名的 JSON 文件：不带 filename 的普通 part 返回 400 INVALID_FILE_METADATA。适配器固定发送 metadata.json，并增加 multipart 次序、文件名及两类认证头的协议断言；独立诊断沙箱已删除。连续任务还暴露 dev scripted 模型忽略最新用户轮次、复用历史工具结果的缺陷；已按最新 USER 边界查找工具结果，两个回归用例覆盖新任务发起工具调用与本轮工具结果结束响应。不通过更换会话来掩盖该问题。

### 22.5 验证

完整 SaaS reactor 功能回归返回 0，保留性能测试排除与可选外部集成边界。最终补入文件工具缓存保护后，重新构建并执行相关测试，返回 0：文件系统 31/31、生命周期 18/18、文件发布集成 101/101、Redis key-only 前缀 1/1、OpenSandbox HTTP 协议 7/7、scripted 连续轮次 2/2、Durable Worker 13/13、App 数据访问架构 11/11；交互附件、Worker 执行与运行时发布兼容用例同次通过。启用本地 OpenSandbox/MinIO 探针，没有用跳过探针替代成功。

真实 PG/Redis/MinIO/OpenSandbox 门禁返回 0：基础用户任务 13/13、不可变源故障恢复及 GC 6/6、会话代际撤销 5/5、显式输入 4/4，整体门禁 11/11。显式输入脚本验证：

1. multipart 上传真实 5 MiB 文件，PG 附件固定原版本及原路径。
2. 首次调用暂停后通过工作区 API 写入新内容；确认请求故意携带较新版本，实际任务仍读取原绑定的 5 MiB 版本。
3. 释放后的输出可下载；员工新上传内容未被旧工作副本覆盖。
4. 同一逻辑会话创建第二个 Run，显式引用第一轮生成输出，成功生成并下载新的产物；两个 Run 与原输出都保留。不把无工具执行的 RUN_FINISHED 当作通过。

```bash
SAAS_CHECKPOINT_OPENSANDBOX_TEST=true SAAS_RUNTIME_BODY_MINIO_TEST=true mvn -o \
  -pl agentscope-saas/agentscope-saas-app -am -Djacoco.skip=true \
  '-Dtest=*,!AgentPerformanceTest' -Dsurefire.failIfNoSpecifiedTests=false \
  spotless:apply test -q
SAAS_MODEL_TYPE=scripted APP_PORT=18082 \
  START_DOCKER_DEPS=false START_DOCKER_DAEMON=false \
  SANDBOX_GATE_WORKSPACE_SOURCE=true SANDBOX_GATE_WORKSPACE_INPUT=true \
  SANDBOX_GATE_SESSION_GENERATION=true \
  SAAS_RUNTIME_ARCHIVE_INLINE_MAX_BYTES=256 \
  SAAS_RUNTIME_ARCHIVE_CHECKPOINT_INLINE_MAX_BYTES=65536 \
  SAAS_RUNTIME_ARCHIVE_CHECKPOINT_MAX_JSON_BYTES=4096 \
  SANDBOX_SMOKE_REQUIRE_OFFLOAD=true \
  bash agentscope-saas/agentscope-saas-app/scripts/start-opensandbox-local.sh --smoke
```

功能回归日志：`/private/tmp/chugou-input-batch19-functional-final.log`；最终相关回归：`/private/tmp/chugou-input-batch19-scoped-final.log`；完整真实门禁：`/private/tmp/chugou-input-final-release-gate.log`。早期 Base64 通道超时、metadata 文件 part 400、连续任务复用历史工具结果的失败均已记录并修复，不计作通过。该门禁使用 scripted 模型，不证明企业模型的推理质量或记忆提炼质量；本批没有前端改动，HTTP/SSE 与静态入口检查不是新的浏览器全量交互验收。

最终工具回写保护编译进应用后，再运行 `SANDBOX_GATE_RUN_SMOKE=false SANDBOX_GATE_WORKSPACE_INPUT=true`：5 MiB 显式输入仍为 4/4，补验门禁 9/9、启动脚本根退出码 0，日志 `/private/tmp/chugou-input-batch19-input-final.log`。该补验明确跳过基础任务，不将跳过项当作新增任务成功。`--smoke` 结束会主动停止 Maven 启动的应用进程，因此被终止的内部 Maven 子进程可能记录 BUILD FAILURE；验收取实际门禁结果与启动脚本根退出码，两个 Maven 测试命令自身均返回 0。

验证后故障触发器/函数与活跃归档任务均为 0，应用 18082 端口无监听，没有运行中的临时沙箱。仅将本轮启动、原先已停止的 PG/Redis/MinIO/OpenSandbox 四个容器恢复为停止状态，保留数据卷、镜像、容器及 Docker Desktop，不操作其他服务。脚本语法、Python 编译与差异空白检查通过。

### 22.6 剩余范围

无显式附件的旧调用仍保留原初始化兼容行为，包括全用户可变投影读取；不将本批计作所有任务都已停止全量扫描。完整 RunWorkspaceManifest、依赖产物自动冻结、按需授权取回与清单扩展、历史产物选作输入的前端入口、只读/路径级执行策略、子任务输出命名空间和失联资源引用、Volume Run 子目录、系统资源版本锁定、输入基线的限量元数据查询，以及 R03 全链路流式/临时盘配额还需继续。

R02 对象存储整体中断时的 Provider retention、计算结果 durable 封存与进程强杀调和仍未闭合；本批不以可变缓存存在替代独立源保管，不改变原有完成门禁。其余业务产物验收、人工安全重试/副作用对账、记忆治理及生产灾备按文档 37 继续。JWT、企业 IAM、内部模型部署和 PromptGuard 继续暂缓；不验证 CubeSandbox/E2B，不使用付费模型。本批未提交或推送。

## 23. 第二十批：对象存储故障下的 Provider 有界保留

### 23.1 实施范围

补齐“源保管 PUT 失败、尚未取得全部发布收据，生命周期仍销毁唯一文件副本”的正常计算完成路径。PG 与 Provider 可用时，冻结的完整 TAR 可以改由原沙箱保管；计算结果提交后，原 attempt 进入 ARCHIVING，归档消费者只取回封存字节，不恢复用户执行、更不重跑模型或工具。

本批不是完整 R02 崩溃恢复，也不把“资源仍运行”当作已经发布成功。用户最终完成仍取决于准确文件清单、版本/摘要、产物及 Outbox 收据和最终答复全部通过原门禁。

### 23.2 保留与授权

1. 增加 Provider-neutral WorkspaceRetentionBackend：seal/retrieve/terminate。真实实现为 OpenSandbox；不耦合业务层到其 SDK，不改变部署时选 Provider 的机制。缺少适配器时不声称有这项恢复能力，正常配置切换保持兼容。
2. V48 增加 workspace_provider_retentions，绑定既有 source、manifest、org/user、实际 Provider/resource、tracking/lease 及绝对 deadline。FORCE RLS、应用角色权限与索引同步建立；新增查询和 CAS 仅在 DAL MyBatis Mapper，继续使用原 tenant/admin 数据库角色，不新增数据源。
3. 原 MinIO 源 PUT 失败后，其 STAGING 记录、大小/摘要和文件基线仍保留。短事务重新核对 Run/session/generation、准确 task/attempt/owner、源内容、租户资源数预算，锁定资源相关记录并持久登记保留意图。不把尚未成功的 MinIO 对象标为已保管；恢复能力由独立保留记录证明。
4. 生命周期在外层 Harness、资源登记在内层 middleware，复制上下文不会反向导出新增 lease/tracking 标记。因此先补齐已运行沙箱的 Provider 身份登记；释放阶段必要时按准确 attempt、org/user、agent、执行会话和实际资源从 PG 取回绑定，不依赖缺失的内存标记。
5. OpenSandbox 将已验证的投影 TAR 二进制上传至独立 /tmp/agentscope-retained-<source UUID>.tar，并以[官方绝对到期接口](https://github.com/opensandbox-group/OpenSandbox/blob/main/specs/sandbox-lifecycle.yml)设置有界 TTL。要求 Provider 确认不早于指定 deadline；使用秒精度，避免 Java 纳秒与服务端精度差异。再通过原资源的二进制下载核对完整大小及 SHA-256，最后短事务登记 RETAINED 与 SCANNED。
6. 正常释放只认可实际环境身份匹配、未到期且已完成持久登记的 Hold；跳过 stop/snapshot/shutdown，关闭本地计算互斥槽位并清除原复用槽。后续任务不能把保留沙箱当作可继续执行的缓存；Hold 在退出时清除，错误环境或过期 Hold 不得抑制正常清理。
7. sandboxes/sandbox_leases 以独立 retention_held 阻止旧候选的 CAS 删除；同一资源的关联记录共同受保护。普通对账候选和最终 claim 还按实际资源排除未清理的保留记录，不能只靠扫描时的状态判断。已经进入删除流程的资源不再接受保留交接。

源文件大小与租户字节预算沿用 workspace-source；另外默认每组织最多保留 4 个资源、900 秒，范围分别为 1-100 和 60-3600 秒。保留配额独立于计算并发槽位；超限或不支持明确拒绝归档接管，不无限占用业务 attempt。

### 23.3 结果封存与恢复

- 对于已经确认的 Provider 封存，归档 job 将不超过 1 MiB UTF-8 的 PendingResult 保存为独立 result_json，body_id 为 NULL，不再依赖故障中的 MinIO。文件字节和消息历史没有塞入这个字段；它只保存计算后提交所需的有限答复、输出与交付标志。
- 封存结果与常规运行正文共享 org/user 容量配额，组织行锁统一串行准入；常规正文提交也计入已有 PG 封存用量，不能各走一套配额。超过单份或租户预算不进入 ARCHIVING。终态 job 经过 deadline 加原 body grace 后，分批清除临时 result_json 并释放容量；已发布用户文件、问答和结果审计元数据保留。
- 重用原独立归档消费者、任务状态转换、恢复 token/lease、版本基线和原子收据。它只下载原 Provider/resource 上按 source UUID 定位的封存文件，不调用 resume/create/start，不从可变工作区或最新快照猜测内容。
- 先验证整个 TAR、全部路径/数量/大小/摘要，再补登缺失文件。SQL 发布门禁同时认可正确的 Provider 保留记录，仍核对源/清单/基线、准确业务归属、归档 token、deadline 和会话代际。覆盖、取消、会话重置、内容损坏或过期均不能绕过门禁。
- NULL body ID 的完成及撤销授权使用 IS NOT DISTINCT FROM，避免 PostgreSQL 对独立 NULL 参数无法推断类型；H2 与真实 PG 均验证。任务最终收尾继续生成准确 checkpoint、文件/产物/Outbox 与答复，不授予新的模型或工具执行权限。
- 归档失败对网页只返回固定提示，不向用户拼接内部对象存储地址等根异常内容；详细原因留在服务端日志。辅助模型脚本返回无文本不是记忆提炼质量验证，本批不修改记忆语义。

### 23.4 清理与配置

独立清理作业对终态归档、取消/失效会话、到期源进行带 token 的有界接管。删除 Provider 资源的 IO 不携带 SQL 锁，成功或资源已不存在才登记 CLEANED 并解除关联保护；中断或失败可在清理租约到期后重试。Agent 删除导致父 tracking/lease 行已级联消失时，仍能按保留记录清理原资源，不无限卡住元数据。Provider 自身的已确认 TTL 是最后一道上限，不依赖 JVM 内存中的延时任务。

```yaml
saas:
  file-store:
    workspace-provider-retention-enabled: true
    workspace-provider-retention-seconds: 900
    workspace-provider-retention-max-org-resources: 4
    workspace-provider-retention-gc-fixed-delay-seconds: 10
```

相应环境变量为 SAAS_WORKSPACE_PROVIDER_RETENTION_ENABLED、SAAS_WORKSPACE_PROVIDER_RETENTION_SECONDS、SAAS_WORKSPACE_PROVIDER_RETENTION_MAX_ORG_RESOURCES、SAAS_WORKSPACE_PROVIDER_RETENTION_GC_FIXED_DELAY_SECONDS。切换部署 Provider 前应先排空保留资源；消费者不会用新 Provider 的客户端处理旧 Provider 的资源。

### 23.5 验证

最终完整 SaaS reactor 功能回归返回 0，命令保留 AgentPerformanceTest 排除及可选外部集成边界，启用本地 OpenSandbox/MinIO 探针。相关测试为：文件发布 109/109、运行正文 14/14、生命周期 21/21、HTTP 协议 8/8、资源登记 5/5、释放对账 5/5、交互附件/错误提示 3/3、数据访问架构 11/11。覆盖封存恢复、复制上下文、损坏拒绝、会话撤销、过期失败、1 MiB 与租户配额、终态封存结果回收及正常释放兼容。

默认 `mvn -o -pl agentscope-saas/agentscope-saas-app -am -Djacoco.skip=true -DskipTests spotless:apply package -q` 返回 0，包括 Javadoc。初次发现已有 Mem0AddRequest Javadoc 链接未导入，只将链接改为完整类型名，不改变记忆行为；没有以持续跳过 Javadoc 掩盖打包失败。

真实本地 MinIO 整体中断门禁 5/5、补验门禁 9/9，启动脚本根退出码 0：

1. 注册独立夹具员工与 Agent，HITL 后真正执行 shell 生成唯一标记产物；工具运行期间停止测试 MinIO，验证冻结字节封存在原 OpenSandbox、结果封存在 PG。
2. 核对原业务 attempt ARCHIVING、计算 lease 已撤销、sandbox lease RELEASED 但 retention_held；等待多个普通对账周期，实际容器仍存在，且没有向网页谎报 RUN_FINISHED。
3. 核对新表 FORCE RLS、外组织应用角色不可见。
4. 恢复原 MinIO 后，独立消费者自动补登准确文件、收据与完整最终答复。核对工具执行仅一次、model invocation 数量不增长、attempt 仍只有一个、网页 API 可下载原始标记产物并读取最终答复。
5. 准确归档完成后，保留记录 CLEANED，原沙箱实际已删除。未重建沙箱来冒充恢复原文件。

```bash
SAAS_CHECKPOINT_OPENSANDBOX_TEST=true SAAS_RUNTIME_BODY_MINIO_TEST=true mvn -o \
  -pl agentscope-saas/agentscope-saas-app -am -Djacoco.skip=true \
  '-Dtest=*,!AgentPerformanceTest' -Dsurefire.failIfNoSpecifiedTests=false \
  spotless:apply test -q
SAAS_MODEL_TYPE=scripted APP_PORT=18082 \
  START_DOCKER_DEPS=false START_DOCKER_DAEMON=false \
  SANDBOX_GATE_RUN_SMOKE=false SANDBOX_GATE_WORKSPACE_RETENTION=true \
  SAAS_RUNTIME_ARCHIVE_INLINE_MAX_BYTES=65536 \
  bash agentscope-saas/agentscope-saas-app/scripts/start-opensandbox-local.sh --smoke
```

最终完整回归日志 `/private/tmp/chugou-retention-functional-final.log`；真实中断验收 `/private/tmp/chugou-retention-opensandbox-accepted.log`；最终打包 `/private/tmp/chugou-retention-package-complete.log`。实测修正了复制上下文未导出租约、已运行资源未登记实际 ID，以及 H2 不能暴露的 PostgreSQL NULL 参数推断缺陷。早期失败不计作成功；最初权限审查超时的完整回归没有启动，授权重试后的完整测试才是验收依据。`--smoke` 的内部 Maven 应用进程结束会被主动终止，可能记录 143/BUILD FAILURE；实际验收取门禁与启动脚本根退出码，不混淆失败测试与正常停止。

最终联合门禁同样返回 0、整体 **12/12**：正常用户任务 13/13、独立归档源恢复及 GC 6/6、会话代际撤销 5/5、整站 MinIO 中断恢复 5/5、真实 5 MiB 显式输入与同会话产物复用 4/4。保留路径没有破坏正常任务释放、原字节源恢复或输入版本保护。命令在上述启动行增加 `SANDBOX_GATE_WORKSPACE_SOURCE=true SANDBOX_GATE_SESSION_GENERATION=true SANDBOX_GATE_WORKSPACE_INPUT=true`，移除 `SANDBOX_GATE_RUN_SMOKE=false`；日志 `/private/tmp/chugou-retention-regression-gate.log`。这些检查使用真实本地服务和 scripted 模型，不是企业模型质量、全部浏览器交互、全库中断或 JVM 崩溃演练。

验证后故障触发器/函数、活跃归档 job、活跃 Provider 保留、tracking/lease 的保留保护位均为 **0**，没有运行中的临时沙箱，应用 18082 无监听。将本轮启动、原先停止的四个服务容器恢复为停止状态；不删除镜像、数据卷、测试历史或无关文件，保留 Docker Desktop。脚本 bash 语法、Python 编译与差异空白检查通过。

### 23.6 剩余边界

1. 当前真实实现仅覆盖 OpenSandbox 的原资源封存能力。Docker、E2B、CubeSandbox 尚未接入该能力；缺适配器、保留预算不足、源元数据未提交或验证失败时，不假报自动归档接管。没有验证 CubeSandbox/E2B，也没有为它们宣称相同的保留语义。
2. PG 整体不可用、Provider 故障或不支持绝对 TTL、执行/捕获时 JVM 强杀、意图提交 ACK 不确定与计算返回到结果封存之间的强杀窗口，尚不能据本批宣称闭合。已验证 RETAINED 和计算结果交接的正常路径具备恢复；没有完整持久证据时仍明确失败。故障验证保持运行消息在正常 PG inline 窗口内；需要 MinIO 转存的大运行正文在关键对话提交阶段失败，不属于本批已经验证的计算后交接路径，不据此声称对话正文也具备同等降级能力。
3. 完整父子后台资源引用/进程调和、保留资源的专项管理页面和指标、重启/多副本/大容量故障演练、Run 工作集清单和依赖冻结、Volume Run 子目录、全链路流式/临时盘预算、记忆治理和生产灾备按文档 37 继续。资源有关联行的保护不等于已经实现全部子任务引用计数。
4. JWT、企业 IAM、内部模型部署和 PromptGuard 继续暂缓；不自动提交或推送。本批不改前端交互，API/SSE 故障测试不是浏览器全量验收。

## 24. 第二十一批：关键运行正文的有界 PG 降级

### 24.1 目标与边界

第二十批故障验收将运行正文保留在普通 PG inline 窗口，尚未覆盖“工具已完成、关键消息需转存 MinIO、正文提交失败阻断归档交接”的路径。本批补齐这段提交链路，不改变文件源封存、结果门禁、消息 seq/cursor 或部署时 Provider 选择。

仅在已选择 MinIO 且正文 PUT/读回校验失败时，允许将原始可信 JSON 保存为有界 PG 降级正文。正常对象存储路径保持原样，PG 文件存储本身故障不再转入第二套 PG 存储。没有对象存储配置、PG 不可用、超限或授权失效时，仍明确拒绝，不把未持久化消息计入成功。

### 24.2 存储与准入

1. V49 增加独立 runtime_body_fallbacks 表，一份原始正文对应一个已有 body_id。org/user/agent/session 的复合外键约束保证归属一致；FORCE RLS、租户策略与应用角色权限同步建立。SQL 只在 DAL MyBatis Mapper，领域接口不引入 Spring、JDBC 或存储 SDK，保持原 tenant/admin 角色及数据源边界。
2. 消息依旧按独立 seq 存储并引用 runtime_message_bodies；不是把全部会话、文件和记忆重新合并到一个字段。普通消息的预览与索引不包含大正文，includeContent=false 不读取对象或降级 payload。
3. 默认单份最多 1 MiB UTF-8、用户累计 16 MiB、组织累计 128 MiB。数据库同时校验真实字节数与单份硬上限 1 MiB，不能通过多字节文本绕过。组织行锁串行准入，随后锁定所属 session；正文 READY 和降级 payload 在同一短事务提交，失败不分配消息序号。并发、未发布正文和此前归档 result_json 仍计入原共享正文容量预算；降级正文另外受较小的 PG 专项预算限制。
4. 原 object_key、backend、大小、SHA-256、body_id 不变。故障中的 PUT 可能已经完成，保留同一个不可变对象标识，不制造无法发现的第二对象。失败或回滚遗留的预留仍可由原 GC 发现。
5. 读取先校验准确 org/user/agent/session、READY、预期大小和对象前缀，再从独立 PG payload 或原对象取得字节，统一核对完整大小和 SHA-256。外用户不得借 UUID 读取正文；降级正文无需访问故障中的 MinIO。

### 24.3 恢复、并发与回收

独立 runtimeBodyRecoveryScheduler 默认每 10 秒扫描有界候选。按 next_attempt_at/body_id 索引扫描，仅在正文 READY 时取得 120 秒持久 token/lease；短事务先锁正文并延后 GC，再 CAS 降级记录。MinIO PUT 与完整读回校验均在 SQL 事务外，只有校验成功且 token/lease 仍有效才删除 PG payload。消息 ID、seq、引用、历史和对象 key 全部不变，不重新执行模型、工具或创建沙箱。关闭新的降级写入后，已有暂存仍继续排空。

单轮最多沿用 body-gc-batch-size，轮次启动 IO 预算为 30 秒；一份失败登记 30 秒后重试并结束本轮，避免一次故障放大为整批同步 IO。单次存储 IO 仍受既有客户端超时控制，这个轮次预算不是强制取消正在进行的请求。独立线程避免阻塞默认执行租约、deadline 与资源对账调度。

引用有效的降级历史没有自动 TTL 删除。容量满时拒绝新增降级正文，而不是丢弃有效用户历史。消息/检查点/活跃归档 job 均无引用且经过原 grace 后，正文 GC 才能清理；候选扫描和最终 claim 同时排除恢复中的有效 lease。物理对象删除成功后，在同一短事务将正文设为 DELETED 并删除降级 payload，释放两类容量。保留原周期复核 tombstone，覆盖 PUT 超时后迟到完成的对象；已过期或旧 token 的转存收据不能删除新消费者持有的暂存。

### 24.4 配置

```yaml
saas:
  runtime-archive:
    body-fallback-enabled: true
    body-fallback-max-bytes: 1048576
    body-fallback-max-user-bytes: 16777216
    body-fallback-max-org-bytes: 134217728
    body-fallback-recovery-delay-seconds: 10
```

环境变量对应 SAAS_RUNTIME_BODY_FALLBACK_ENABLED、SAAS_RUNTIME_BODY_FALLBACK_MAX_BYTES、SAAS_RUNTIME_BODY_FALLBACK_MAX_USER_BYTES、SAAS_RUNTIME_BODY_FALLBACK_MAX_ORG_BYTES、SAAS_RUNTIME_BODY_FALLBACK_RECOVERY_DELAY_SECONDS。单条不得超过数据库硬上限，用户预算不得小于单条预算，组织预算不得小于用户预算。已有降级未排空时，不应把部署文件存储从 MinIO 换成不兼容后端；不会用其他后端冒充已验证的 MinIO 对象。

### 24.5 验证

完整 SaaS reactor 功能回归返回 0：运行正文 23/23、文件发布 109/109、数据访问架构 11/11；启用本地 OpenSandbox/MinIO 探针，保留 AgentPerformanceTest 排除和可选外部集成边界。正文新增 9 项测试覆盖 UTF-8 字节计数、稳定序号与重复准入、故障期授权读取与预览、配置关闭/单份超限、个人及跨员工组织配额、并发配额、校验失败不删除暂存、关闭新增降级后的排空、有效引用保留、GC/恢复租约竞争、旧或过期收据拒绝以及消息事务回滚后的孤儿清理。

真实本地 OpenSandbox/PG/MinIO 故障门禁 **7/7**、外围门禁 **9/9**，启动脚本根退出码 0。将普通正文阈值降到 256 字节，HITL 确认工具开始运行后停止整个 MinIO，工具产生 8 KiB stdout 和独立标记文件：

1. 不出现 RUN_FINISHED 或错误终止；原 attempt 进入 ARCHIVING，原资源的输出 TAR 和有限 PendingResult 完成持久封存，计算槽位释放。
2. 确认确实存在 runtime_body_fallbacks，不能靠普通 inline 窗口冒充降级；故障期按 afterSeq 读取新增完整运行正文返回 200，includeContent=false 仅返回有界预览。此前已经仅在 MinIO 的历史正文不包含在故障期完整读取保证中。
3. 新表真实 PG FORCE RLS 生效；外组织 app 角色查询不可见。原沙箱经过多个普通对账周期仍保留。
4. 恢复 MinIO 后，原归档消费者补齐文件、收据和答复，Run 自动 SUCCEEDED；工具只执行一次、attempt 只有一个、model invocation 数不增长。网页 API 下载原标记文件成功。
5. 正文恢复消费者排空全部该会话暂存，再通过完整历史 API 读取正文；原 body_id 均存在且 READY/backend=minio，没有替换消息身份。最终 retention CLEANED、原容器已删除。

```bash
SAAS_CHECKPOINT_OPENSANDBOX_TEST=true SAAS_RUNTIME_BODY_MINIO_TEST=true mvn -o \
  -pl agentscope-saas/agentscope-saas-app -am -Djacoco.skip=true \
  '-Dtest=*,!AgentPerformanceTest' -Dsurefire.failIfNoSpecifiedTests=false \
  spotless:apply test -q
SAAS_MODEL_TYPE=scripted APP_PORT=18082 \
  START_DOCKER_DEPS=false START_DOCKER_DAEMON=false \
  SANDBOX_GATE_RUN_SMOKE=false SANDBOX_GATE_WORKSPACE_RETENTION=true \
  SANDBOX_GATE_RUNTIME_BODY_FALLBACK=true SAAS_RUNTIME_ARCHIVE_INLINE_MAX_BYTES=256 \
  bash agentscope-saas/agentscope-saas-app/scripts/start-opensandbox-local.sh --smoke
```

日志为 `/private/tmp/chugou-body-fallback-functional.log` 和 `/private/tmp/chugou-body-fallback-opensandbox-accepted.log`。最初受限环境中 Mockito attach 失败，授权重跑后的 scoped 与完整回归才计为成功；第一次门禁在启动前被 DAL 格式检查拒绝，格式化后重跑通过，不把失败日志纳入成功证据。只使用 scripted 模型，不验证 CubeSandbox/E2B、付费模型或全部浏览器功能；故障门禁中的辅助模型无文本不属于记忆语义质量验收。

默认 `mvn -o -pl agentscope-saas/agentscope-saas-app -am -Djacoco.skip=true -DskipTests spotless:apply package -q` 返回 0，包含 Javadoc；日志 `/private/tmp/chugou-body-fallback-package.log`。

最终联合门禁根退出码 0、整体 **12/12**：正常任务 13/13、独立归档源恢复 6/6、会话代际撤销 5/5、整站 MinIO 中断及正文降级 7/7、真实 5 MiB 显式附件与产物复用 4/4。命令在本节启动行增加 `SANDBOX_GATE_WORKSPACE_SOURCE=true SANDBOX_GATE_SESSION_GENERATION=true SANDBOX_GATE_WORKSPACE_INPUT=true`，移除 `SANDBOX_GATE_RUN_SMOKE=false`，保持正文阈值 256；日志 `/private/tmp/chugou-body-fallback-regression-gate.log`。`--smoke` 内部 Maven 应用被主动终止时出现 143/BUILD FAILURE 是正常停止记录，验收依据为各门禁与启动脚本的根退出码。

最终只读核对正文降级记录、活跃归档 job、活跃 Provider 保留、tracking/lease 保护位及测试故障触发器/函数全部为 **0**；真实数据库已迁移至 V49。应用 18082 无监听，没有运行中的临时沙箱。bash 语法、Python 编译和差异空白检查通过；恢复本轮原先停止的四个服务容器，不删除 Docker Desktop、镜像、数据卷、历史夹具或无关文件。本批未提交或推送。

### 24.6 仍未闭合

PG 与对象存储同时故障、JVM 强杀和提交 ACK 不确定窗口、原先已经只在 MinIO 的历史正文在 MinIO 中断时读取、超过降级单条/累计预算的结果、其他 Provider 的原资源封存、全量流式 IO/临时盘预算、完整父子资源引用、Run 工作集及依赖冻结、记忆治理与生产灾备仍按文档 37 继续。本批不将“新增正文有界降级”解释为“全量对象存储高可用或完整 R02 崩溃恢复”。

## 25. 第二十二批：已计算结果与字节源的原子交接

### 25.1 问题与范围

第二十一批覆盖对象存储中断的正常收尾，但原链路先完成独立源封存和资源释放，再由 Controller/Worker 保存答复、提交业务状态或登记 ARCHIVING。JVM 在两者之间退出，可能留下可恢复的文件源，却没有完整最终答复的交接凭据；普通过期 attempt 恢复仍有机会重跑计算。

本批将“已验证的独立源 + 已计算的最终结果”登记为原子待提交凭据。正常请求继续按原方式完成，不将所有任务强制改成异步返回；只有收尾异常或进程退出，才转入归档消费者。保证从这份双重持久证据提交后开始；不声称已解决工具运行中、内存结果尚未封存或 PG 整体不可用的全部崩溃窗口。

### 25.2 捕获与封存

1. Harness 增加可选、Provider-neutral InvocationResultObserver：阻塞 call 和 streamEvents 在 eager cleanup 前观察最终 Msg。只有拥有该沙箱的调用执行观察；借用父沙箱的子 Agent 不观察或重置父凭据，转发的子 Agent result 事件也不作为顶层结果。框架层不依赖 SaaS、SQL、Spring 或 Provider SDK。
2. WorkspaceComputedResult 以可信 task/attempt 绑定共享上下文对象，拒绝其他任务、USER 消息、空消息和仍含 ToolUseBlock 的暂停回合。调用重试只重置自身结果。Worker 捕获时临时绑定准确租户并在 finally 恢复线程上下文，不依赖模型回调线程继承 ThreadLocal。冻结完整答复、有限任务输出及是否递交答复的标记，不从可变会话“猜”最后一条消息。
3. MinIO 源 PUT/完整读回校验或 OpenSandbox 原资源 seal/完整取回校验仍在事务外进行。最后短事务先锁组织配额，再核对 session/generation、Run、task/attempt/owner 和准确冻结清单；源 RETAINED/保留记录 RETAINED、清单 SCANNED 与 prepared 结果在同一事务提交。登记结果失败时不会单独提交“可恢复源已经就绪”的新断言；已有外部字节与 STAGING 意图仍由原回收路径发现。
4. prepared 结果使用原 workspace_archive_jobs 的独立 result_json，单份不超过 1 MiB UTF-8，并计入原 org/user 共享正文配额。它不是完整会话或文件字节字段。重复登记以完整可信 scope 和唯一 attempt/manifest 核对既有凭据，持久冻结结果优先，不将重试时的局部答案覆盖进去。

### 25.3 状态、接管与撤销

V50 为原表增加 prepared 布尔字段及约束、到期扫描索引，延续原 FORCE RLS 和应用角色授权，不新增数据源。prepared 只能是带有 PG 封存结果的 PENDING job。初始给正常请求 30 秒提交优先期，业务 attempt/task/agent_run 此时仍为 RUNNING，不提前宣称完成。

普通过期计算扫描与最终 finishAttempt CAS 同时排除 prepared PENDING 凭据，阻止扫描旧候选在凭据提交后重新安排计算。原归档消费者到期后按 session、Run、attempt、job 的锁序重新核对全部所有权、当前执行代际和取消状态，再原子转换原 attempt/task/agent_run 为 ARCHIVING，撤销计算租约并写 TASK_ARCHIVING 事件与 Outbox。接管允许原计算租约已经过期，但只授予提交冻结字节和结果的权限，不恢复模型、工具或沙箱执行。

正常 owner 已成功完成时，消费者只将 prepared 记录置 COMPLETED，不再递交答案。取消、会话重置、父实体删除或所有权失效会撤销凭据；预算过期按原归档失败路径终态收尾，不再创建新 attempt。调用方收尾失败可主动激活同一 prepared job，复用原 job ID；关闭新登记开关不会停止已经准入的消费者。

接管之前原 owner 可能已发布部分文件。归档消费者在当前 token/lease/deadline 授权下，把准确 manifest 的已有 PUBLISHED 收据绑定到尚为空的 job item，核对 org/user、Run/agent/session/generation、路径、大小和摘要，再补缺失文件。不会把后来用户上传的版本当作当前清单收据；最终仍通过原 manifest/版本/产物/Outbox 与任务验收门禁。并发消费者和迟到旧租约继续用原归档 CAS，不能重复答复或生成重复版本。

### 25.4 配置与兼容

```yaml
saas:
  file-store:
    workspace-completion-handover-enabled: true
```

环境变量 SAAS_WORKSPACE_COMPLETION_HANDOVER_ENABLED，默认 true；关闭时保持原正常收尾及显式 defer 机制。该优化依赖已启用的文件源、归档和发布恢复链路，仍受各自容量、保留期和权限预算。部署时 Provider 配置不变，用户端不暴露切换；MinIO 独立源路径是 Provider-neutral，Provider 原资源保留的真实适配仍仅为 OpenSandbox。

### 25.5 验证

完整 SaaS reactor 功能回归最终返回 0，保留 AgentPerformanceTest 排除及可选外部集成边界，启用本地 OpenSandbox/MinIO 探针。关键测试：文件发布 **118/118**、捕获身份/线程租户 **3/3**、正文 **23/23**、Harness call/stream 清理顺序 **6/6**、生命周期/借用保护 **22/22**、独立租约恢复 **9/9**、数据访问架构 **11/11**。覆盖原子回滚、正常 owner 收尾、prepared 到期及主动接管、准确冻结答案、部分已发布文件复用、旧过期候选 CAS、两个消费者竞争、取消、预算和开关兼容。日志 `/private/tmp/chugou-prepared-functional-complete.log`。

实测还发现一条历史 ownerless 且已过期的 RUNNING 行会让 Worker 回收批次在 workerId 校验处报错。Worker 扫描只处理有合法非空 owner 的租约，不越权给 ownerless 请求/历史行安排计算；新增与有效 Worker 租约并存的测试。没有删除或改写历史行，遗留数据治理与真正的 Worker 过期恢复区分处理。早期独立租约测试只创建最小 H2 表，不含归档表，现补齐夹具结构；没有撤销生产 CAS 守卫或跳过失败测试。

真实本地强杀探针 **10/10**，根退出码 0。新脚本 `workspace-crash-smoke.py` 只启动自己拥有的独立进程组，拒绝覆盖已有 18082 listener；固定本地 E2E 数据库、scripted 模型、OpenSandbox/MinIO/Redis，不继承远程 Provider 选择。PG 故障触发器严格限定夹具 org/Run，取消数据库残余查询按本轮唯一 ApplicationName 定位，不终止其他应用。

1. 第一场景：最终模型结果与验证后的 MinIO TAR 已原子登记 prepared，但正常文件发布尚未完成。用夹具触发器控制该窗口，确认原 attempt 仍 RUNNING，再对自建 JVM 进程组发 SIGKILL。重启后消费者完成原 attempt，不新建模型调用、工具执行、沙箱租约或业务 attempt。
2. 第二场景：工具真实运行时停止整个 MinIO，原 OpenSandbox 中封存 TAR，最终结果/大运行正文进入 PG。确认原 attempt ARCHIVING 且消费者持有 RUNNING token/lease，再强杀进程。恢复 MinIO并重启，旧归档租约到期后自动接管；原文件、收据及答复仍准确，Provider 保留与正文降级全部排空。
3. 两场景均经用户 API 下载唯一标记文件，确认只有一个 PUBLISHED 文件收据及一条最终 assistant 问答；读取 turns/window 的 ASSISTANT 内容，与强杀前 PG 封存的实际答案逐字一致。不会因为 USER 消息里有标记而误判，也不把 scripted 模型主动选择的工具输出摘要误当作平台正文截断。
4. 确认原 job 完成且 prepared=false，模型调用数、工具成功次数、业务 attempt 和 sandbox lease 数不增长；真实 PG FORCE RLS 下，外组织 app 角色不可见。

```bash
SAAS_CHECKPOINT_OPENSANDBOX_TEST=true SAAS_RUNTIME_BODY_MINIO_TEST=true mvn -o \
  -pl agentscope-saas/agentscope-saas-app -am -Djacoco.skip=true \
  '-Dtest=*,!AgentPerformanceTest' -Dsurefire.failIfNoSpecifiedTests=false \
  spotless:apply test -q
PYTHONUNBUFFERED=1 python3 agentscope-saas/agentscope-saas-app/scripts/workspace-crash-smoke.py
```

强杀日志 `/private/tmp/chugou-workspace-crash-smoke-complete.log`，自建应用各次启动日志位于 `/private/tmp/chugou-crash-<fixture UUID>-boot-<n>.log`。最初探针把 SIGKILL 造成的正常 SSE IncompleteRead 当作失败，随后一次重启被未格式化的新源文件拒绝，再一次错误地要求最终答案包含全部 8 KiB 工具输出；均修正后完整重跑，不把这些失败记录算作通过。最终封存答案精确比较，而不是降低校验或删掉断言。

默认 `mvn -o -pl agentscope-saas/agentscope-saas-app -am -Djacoco.skip=true -DskipTests spotless:apply package -q` 返回 0，包含 Javadoc；日志 `/private/tmp/chugou-prepared-package.log`。真实数据库已迁移至 V50；强杀演练完成后 prepared/活跃归档、运行正文降级、活跃 Provider 保留及 computed_crash 故障触发器/函数均为 0，应用 18082 无监听。

最终联合门禁根退出码 0、整体 **12/12**：正常用户流程 13/13、独立源恢复与 GC 6/6、会话代际撤销 5/5、整站 MinIO 中断及正文降级 7/7、真实 5 MiB 附件权威版本及产物复用 4/4。保持正文转存阈值 256 字节及默认新交接开关，命令为第二十一批联合启动命令；日志 `/private/tmp/chugou-prepared-regression-gate-final.log`。第一次联合门禁因 Docker 选择了被无关本地应用监听的临时端口而启动失败，工具尚未执行；未停止该应用或加入盲目创建/计算重试，核对无失败沙箱残留后原命令完整重跑通过。失败日志 `/private/tmp/chugou-prepared-regression-gate.log` 保留，不算成功。内部 Maven 应用按脚本正常停止可记录 143/BUILD FAILURE，验收以各门禁和启动脚本根退出码为准。

联合脚本退出时，最后一个已删除父 Run 的短期 prepared 记录仍在 30 秒优先期内。另启动自建测试应用，让正式消费者按“父实体已删除”路径自动撤销并排空，不通过手工 SQL 改状态；日志 `/private/tmp/chugou-prepared-final-drain.log`，根退出码 0。这验证了启动发现和删除后清理，而不是把停止前的残余忽略为已完成。

最终 prepared、活跃归档、正文降级、活跃 Provider 保留、tracking/lease 保护位及 archive_fault/computed_crash 故障触发器/函数全部为 **0**。应用 18082 无监听，没有运行中的临时沙箱；bash 语法、Python 编译、差异空白检查通过。将本轮原先停止的 PG/Redis/MinIO/OpenSandbox 四个容器恢复为停止状态，保留 Docker Desktop、镜像、数据卷和无关历史；只清理夹具 Agent，不删除无关应用或文件。本批未提交或推送。

这些验证使用真实本地服务和确定性模型，不是企业模型质量、CubeSandbox/E2B、整套双副本应用混沌或全量浏览器点击验收。

### 25.6 剩余边界

源和结果原子交接以前的执行/捕获强杀、PG 与 Provider/对象存储同时故障、超过 1 MiB 或租户预算的封存、工具外部副作用对账、整套多副本/多机混沌、其他 Provider 保留适配、全链路流式 IO/临时盘预算、完整父子资源引用、工作集与依赖冻结、记忆治理和生产灾备仍按文档 37 继续。原对象存储中断时不能自动读取此前仅在 MinIO 的旧历史正文。JWT、企业 IAM、内部模型部署及 PromptGuard 继续暂缓；不自动提交或推送。

## 26. 第二十三批：主文件链路有界流式传输

### 26.1 传输与资源准入

- Framework 增加进程共享 `WorkspaceTransferResources`，统一 I/O 并发、缓冲及临时盘预留。默认两路传输、1 MiB 缓冲、1 GiB spool；准入可有界等待，取消/异常释放许可。创建前预留最坏临时空间并检查实际磁盘余量；写入越界拒绝，删除失败不会假装已释放临时盘预算。
- `WorkspaceFile` 允许不可变大小/摘要加惰性流来源，逐项校验真实长度及 SHA-256；大文件不再通过 `.content()` 隐式还原整份数组。旧 byte[] 入口仅作不超过 32 MiB 的兼容；PG 开发文件后端也明确保留这一能力边界。
- SaaS 装配向 Harness、Provider、上传、下载、目录、恢复和源保管注入同一资源池。启动校验 spool 至少覆盖并发捕获和一份校验归档，OpenSandbox transport 仍有明确总大小能力限制。

### 26.2 文件全链路

1. 上传使用 `PartEvent` 流直接进入应用受控 spool，再扫描与目录准入。不能先让 Spring multipart decoder 为整文件作第二份不受应用预算控制的磁盘副本；明确拒绝超限、附加 Part、不完整 Part 和非法声明。容量错误分别返回 413 或带 Retry-After 的 503。
2. MinIO `openRead/putStream` 真实流式，目录使用 Path/流上传和逐块读回验证，不绕过原授权、预留、配额、代际、attempt fencing、收据和短事务提交。旧 byte[] 接口保留小文件兼容。
3. 附件与检查点恢复只构建版本描述，读取前后重验准确绑定；在取得沙箱前预校验完整性，再逐项生成有界 TAR。预校验会增加一次读取，这是防止先启动沙箱再发现输入损坏的明确成本，而不是“零初始化 IO”的宣传。
4. OpenSandbox 使用原生文件流传输；工作区捕获只取一次源，逐项摘要与冻结，再将每个产物以 spool Path 交给目录。输入副本与输出计入同一个实际工作区总量；未变化的绑定输出不会再发布，变更副本流式比较后写到新 revisions 目标。
5. 捕获必须拒绝绝对/父路径、重复项、链接、设备和 FIFO 等非普通条目，拒绝时整份报告不完整且不会发布部分“成功”结果。不能仅相信归档库宽松的 `isFile()` 判断。
6. 源校验、归档-only 补发布及恢复使用有界 TAR/spool，不整文件 `readNBytes(size)`；恢复已材料化发布时通过既有对象的流式完整性证据完成目录登记，不重新从内存写一份。
7. 当前版本和历史版本下载支持授权单 Range、206/416、ETag 和完整长度；取消关闭对象流并释放 I/O 许可。文本预览在打开对象前检查独立的 512 KiB 上限，版本恢复也采用目录流式路径。收紧新写入上限不会让已有历史版本无法下载。

### 26.3 已验证证据

最新 scoped 回归返回 0，日志 `/private/tmp/chugou-stream-security-contract-complete.log`。覆盖共享物理容量、链接/设备/FIFO 拒绝、绑定输出不回写、流式输入长度/摘要、磁盘预留和中断释放、上传 Part 边界、Range、预览和历史下载、文件发布/归档原事务及 DDD/MyBatis 边界。环境可选用例未启用者仍记为跳过，不算真实沙箱验收。

真实 PG/Redis/MinIO/OpenSandbox、scripted 模型联合门禁根退出码 0，日志 `/private/tmp/chugou-stream-live-aligned.log`：

- 新流式 probe **7/7**：31 MiB 上传/准确摘要、默认 33 MiB 明确 413、原始版本实际进入沙箱并生成归档产物、完整流式下载、206/416、取消后四路下载完成、超大文本不预览及历史大文件恢复后准确下载。
- 基础用户流程 **13/13**；目录发布故障归档恢复 **4/4**；计算后提交故障 **2/2**；缺材料化字节的独立源恢复与 GC **6/6**；会话撤销 **5/5**；整站 MinIO 中断、Provider 封存和正文降级 **7/7**；5 MiB 原绑定输入与较新用户版本保护 **4/4**；总门禁 **14/14**，FAIL=0，归档队列排空。
- 64 MiB 策略下真实 33 MiB 旧版 probe **6/6** 已通过，日志 `/private/tmp/chugou-stream-large-complete.log`；该次发生在后来新增预览/版本恢复断言之前，不能据此宣称最新七项大文件门禁已全部重跑。

实测修复了三处真实问题：multipart 默认 32 MiB 预解析曾让 33 MiB 返回 500，而不是应用的 413；基础 probe 未携带前端使用的不可变附件版本，上传移除 Redis 重复文件后错误地期待文字路径自动成为输入；MinIO 的旧 commons-compress 1.21 间接依赖覆盖 Harness 1.27.1，导致运行时 NoSuchMethodError。应用依赖管理现与 Harness 对齐 1.27.1，完整重跑后才记录成功。另修复释放记录的 nullable 时间参数，在 PG 中改用列类型明确的 COALESCE，保留原失败/重试语义。

失败日志保留，包括 `/private/tmp/chugou-stream-live-complete.log`；之前脚本/编译/异常分类失败不能计作通过。探针只操作自身应用进程和明确授权的本地依赖，不调用 CubeSandbox/E2B 或付费模型。

### 26.4 仍未闭合

本批不将主链路流式和四路下载通过解释为全量 R03 完成。接受时策略版本与收紧后的恢复/归档契约、Cube/E2B 及增量兼容路径的全流式、进程强杀后的 spool 回收、真实多任务 heap/磁盘压力仍需实施与验收。四路下载不是四个完整任务的容量压测。其他 R01–R17 工作项继续以文档 39 为准；JWT、企业 IAM、内部模型部署和 PromptGuard 继续暂缓，未提交/推送。

## 27. 第二十四批：真实恢复消费者与网页交付闭环

### 27.1 能力与持久心跳

新增 Domain `WorkerPresenceRepository`、DAL MyBatis 管理/租户 Mapper 及 App `RecoveryCapability`，不新增业务 JDBC 或数据源。V51 的 `worker_heartbeats` 按 deployment_scope + 启动期 Worker UUID 唯一；能力摘要包含运行协议、Provider 类型、目录后端及工作区容量，而不包含模型凭证。

心跳以数据库时钟续期，报告可用槽位；实际消费循环成功后才登记消费者。暂时饱和与进程离线分别显示 WAITING_FOR_CAPACITY、WAITING_FOR_WORKER；优雅退出使自己的心跳过期，未退出的进程靠 TTL 判定。保留过期注册信息是为了临时中断时不丢弃已接受队列，不把过期心跳当作活跃消费者或业务授权。

`worker_heartbeats` 是跨租户运维事实，不是员工数据表；普通 app 数据库角色被撤销读写权限，不能从用户连接枚举或伪造消费者。任务读取仍走 tenant MyBatis 和原 FORCE RLS，恢复 API 同时核对 org/user/agent/Run。

部署决策：

- LOCAL + DURABLE 必须启用编排和本地调度器，非法组合启动拒绝。
- EXTERNAL 必须设置明确共享 deployment_scope；Web 可以不轮询，但未出现匹配注册证据时，不把配置字符串解释为已安排后台恢复。首次消费者暂未登记时仍可做有界请求内恢复，耗尽则明确失败。
- 已登记 Worker 临时离线时，恢复入队事务仍保留，界面显示等待资源。`RecoveryConsumerMonitor` 记录消费者健康、等待任务数、最老等待秒数；超过队列 SLA 告警，不把等待的任务错误地标记为完成。
- REQUEST_ONLY 的过期直接执行也由独立巡检终止，不依赖关闭的任务消费器，不留下不能消费的 READY 恢复队列。归档-only 消费器和原 prepared 凭据保护保持不变。

### 27.2 重试预算与异常边界

模型调用账本的准确 org/user/Run/task 失败 REASONING 记录同时供请求和 Worker 使用；用途不同的辅助失败、成功调用及其他员工/任务不混入这份计数。DURABLE 下有可登记消费者时，请求把剩余预算交给 Worker，而不是先耗尽请求预算再从头重试。

受治理调用明确向模型 SDK 传入单次执行配置，禁止默认 SDK 三次隐藏重试绕开持久账本。单路模型实测跨 HTTP 与 Worker 总共最多三次真实模型请求，且对应三条调用收据；不将这一测试扩大解释为所有 failover Provider 子调用的计量已经闭合。

OpenAI 兼容 SDK 的原生 OpenAIException/HttpTransportException 不是 ModelException。只有受治理模型调用边界将其规范化：已输出的断流变为 ModelStreamInterruptedException，无输出连接异常保留原始 typed cause；401 等永久错误仍按状态判断并禁止重试。工具、业务、权限、上下文、撤销和结果交接错误不会仅因有“重试策略”被重新执行。Worker 对不可恢复执行错误使用终态失败，而不是通用的自动重试。退避加入有界抖动，继续受原 Run/Task/调用 deadline 和租约约束。

### 27.3 网页与事件

恢复入队事件在提交后包含原因、次数和 nextRecoveryAt。Controller 再读取权威 Run/queue 状态；租约已丢失、任务已失败/取消或已经被其他消费者接管时，不盲目报告“刚刚安排成功”。用户可通过 GET `/api/agents/{agentId}/runs/{runId}/recovery` 重建状态，事件丢失不丢弃恢复事实。

ChatPanel 和 RunInspector 显示等待资源或恢复状态。恢复事件保留准确 session，页面刷新后从后端重新发现等待；后台完成时自动读取最终问答及产物，保留下一条输入草稿，不需要用户再发“继续”。排队期间禁止重复发送，取消后 Worker 不能再领取。原归档自动交付行为仍保留。

### 27.4 配置

```yaml
saas:
  model:
    stream-recovery:
      enabled: true
      mode: DURABLE
      max-attempts: 3
  orchestration:
    enabled: true
    consumer-mode: EXTERNAL
    deployment-scope: chugou-enterprise
    scheduler-enabled: false # Web；Worker 使用同一部署配置，将此项设为 true
    consumer-heartbeat-ttl-seconds: 90
    scheduler-heartbeat-seconds: 20
    recovery-queue-sla-seconds: 300
```

新增环境变量：`SAAS_ORCHESTRATION_CONSUMER_MODE`、`SAAS_ORCHESTRATION_DEPLOYMENT_SCOPE`、`SAAS_ORCHESTRATION_CONSUMER_HEARTBEAT_TTL_SECONDS`、`SAAS_ORCHESTRATION_RECOVERY_QUEUE_SLA_SECONDS`。文件目录和需要恢复的工作区持久化仍是前置条件，不把 EXTERNAL 开关当成对象存储高可用方案。

### 27.5 验证

恢复、持久心跳、调用账本、Worker、权限及架构 scoped 回归返回 0，日志 `/private/tmp/chugou-consumer-model-normalized-tests.log`。覆盖 LOCAL/EXTERNAL 配置、缺目录、心跳 TTL、未注册/离线/饱和/恢复、真实 MyBatis/H2 数据库时钟、次数跨执行阶段不重置、准确用途/owner 过滤、异常 cause、SDK 单次配置、REQUEST_ONLY 过期清理。

真实双应用 + PG/Redis/MinIO/OpenSandbox 门禁 `worker-consumer-smoke.py` 最终 **8/8**、根退出码 0，日志 `/private/tmp/chugou-consumer-real-browser-complete.log`。模型为本地 HTTP 协议夹具，会真实输出部分 SSE 后截断连接，或返回 401；没有外部模型调用，也没有 paid/Cube/E2B 验证。

1. 未注册消费者时明确失败，没有虚假的恢复入队事件。
2. 独立 Worker 登记后停止，任务仍进入持久等待队列；重启后原任务完成，用户历史可读取完整最终答复。
3. 另一员工无法读取恢复状态；app PG 角色不能 SELECT/INSERT 心跳。
4. 持续断流跨请求和 Worker 只有三次真实模型调用；永久 401 只有一次，排队取消后重启 Worker 不会继续请求模型。
5. 真实浏览器在 Web 应用上登录、发起新任务；Worker 离线时显示等待，移动页面刷新后继续等待。Worker 重启后页面自动加载最终回答，保留草稿，前端只提交过一次任务。截图位于 `/private/tmp/chugou-real-recovery-ui/`，已人工检查桌面/移动布局。

原归档及恢复交互的 API fixture 浏览器门禁也各覆盖 1440×1000 和 390×844，日志 `/private/tmp/chugou-archive-ui-current.log`、`/private/tmp/chugou-recovery-ui-current.log`，与上述真实后端验证分别记录。前端 TypeScript/Vite build 返回 0。

期间失败包括未格式化源文件被启动脚本拒绝、最初未识别 SDK 原生断流异常，以及浏览器探针读取 SPA 导航后已失效的 Chrome SSE 缓存。最后一项改为读取准确的后端恢复状态，保留真实网页提交、等待、刷新和交付断言；完整八项重跑后才计通过。失败日志保留，不将“页面看见部分内容”当作最终完成。

### 27.6 剩余边界

这不是全套生产双副本混沌完成。接受时策略冻结、跨部署策略变更的排队/恢复、兼容 Worker 任务选择、所有 Provider failover 子调用账本、PG 和对象存储同时失效、Source 封存前进程强杀及人工副作用对账仍需继续。结构化个人记忆/企业岗位、依赖冻结、持久父子资源引用、定时任务、MCP 凭据、完整快照、远端进程、纠偏及提前执行等仍按文档 39 推进。JWT、企业 IAM、内部模型部署及 PromptGuard 保持暂缓，不自动提交/推送。

## 28. 第二十五批：临时文件私有化与失效 owner 回收

所有新 spool 使用进程资源池所属的私有 UUID 目录。在支持 POSIX 的系统中目录原子创建为 0700，文件由原安全临时文件接口创建；owner 标记使用 NoFollow 的专用 FileChannel 和进程文件锁。资源池保持 owner 锁，不在尚有传输进行时解锁；不能仅凭旧 PID 或文件年龄删除其他进程的活跃缓存。

新目录首次建立时有限扫描同一临时目录下的专用格式：只处理 UUID 命名、相同 OS owner、正确的有界标记、普通受管 `.part` 文件，且能够取得排他 owner 锁的失效目录。锁仍被持有、标记不匹配、未知文件、链接、其他 owner、访问失败或扫描超限时保留，不跟随链接、不递归猜测、不回收已发布业务对象。仅回收新格式；旧共享临时目录中的无归属 `.part` 不会被盲目删除。

临时盘除了字节预算，还限制同时保留的 spool 数，默认并发两路时最多 32 份（8 × 并发 + 16），防止零字节产物在删除失败时无限消耗 inode/元数据。删除失败同时保留字节与数量预留；等待时间验证上限为五分钟，默认仍为 30 秒，避免超大等待参数的纳秒溢出。

测试验证：失效目录回收、同进程另一活跃 owner 保护、未知文件与链接保护、POSIX 私有目录权限、空 spool 数量限值，以及一个独立 JVM 写入后使用 Runtime.halt(0) 突然退出，后继 owner 确实回收其目录。macOS `/var` 与 `/private/var` 的规范路径差异曾导致探针断言失败，修正为 realPath 后保留实际字节存在和回收断言。源文件系统测试 33/33、spool 目录测试 3/3；这些不是新的“工具执行中强杀后业务结果必定恢复”保证。

完整功能 reactor 已返回 0，日志 `/private/tmp/chugou-stream-consumer-functional-final.log`，显式启用本地 OpenSandbox 与 MinIO 探针：文件发布/归档/恢复 119/119，无跳过；调用账本 14/14；DDD/MyBatis 11/11。保留既有 AgentPerformanceTest 排除；旧 SnapshotRoundTripProbeTest 已无源文件，仅为历史 target 中的临时编译产物，单独排除，不排除实际源码回归。恢复计数的末轮边界修正另有 scoped 日志 `/private/tmp/chugou-stream-consumer-boundaries-final.log`。

最新 64 MiB 单文件策略下真实 33 MiB probe 七项全部重跑通过，根退出码 0，日志 `/private/tmp/chugou-stream-large-seven-final.log`，替代第 26 节旧六项记录作为当前大文件证据。使用真实 PG/Redis/MinIO/OpenSandbox 和 scripted 模型，已排空归档与 Provider retention；新增目录资源池也参与本次实际文件链路。不是 Cube/E2B、企业模型、四个完整任务或 heap 压测证明。

末轮恢复边界还修正：有持久失败收据时，不将成功的协调器 Task attempt 号误当模型失败次数；禁用恢复不额外读取失败计数；即使已经产生部分输出，具有永久 4xx cause 的异常也不进入恢复。受治理的 SDK 单次配置保留调用方更短的正数 timeout，不能把其扩大到平台总 deadline；心跳配置比较避免 Long.MAX_VALUE 乘二溢出。

剩余：接受时策略版本及跨部署变更契约、兼容 Worker 选择、其他 Provider/增量兼容路径流式、实际多任务 heap/磁盘压力、完整故障/灾备与 R05–R17 功能仍按文档 39 继续。不将临时盘回收或这两批链路验收视为全部方案完成；保持用户暂缓范围，无自动提交/推送。

最后一次完整回归包含上述末轮计数、调用方 timeout 和心跳溢出修正，根退出码 0，日志 `/private/tmp/chugou-stream-consumer-functional-accepted.log`；默认 `mvn -o -pl agentscope-saas/agentscope-saas-app -am -Djacoco.skip=true -DskipTests spotless:apply package -q`（含 Javadoc）也返回 0，日志 `/private/tmp/chugou-stream-consumer-package-final.log`。Python/JavaScript/bash 语法及差异空白检查通过。

最终真实 PG 迁移为 V51；prepared/活跃归档、Provider retention、正文降级、tracking/lease 保留位和本轮故障触发器均为 0，app 角色对心跳运维表无读写权限。已关闭拥有的 Web/Worker/Vite 验证进程，18082、18083、5190 无监听；恢复原先停止的四个依赖服务容器，不关闭 Docker Desktop、不删除镜像、卷、历史用户记录或无关文档。当前仍为未提交工作区，不自动推送。

## 29. 第二十六批：任务接受策略冻结与兼容消费者筛选

2026-10-10 用户取消 R08 结构化企业记忆，2026-10-11 继续实施其余范围。保留原对话、记忆来源和 Mem0 投递，不新增企业岗位、记忆卡片或提炼管理 UI。文档 37 的历史设计和文档 39 的当前门禁已分别注明，不删除既有数据。

### 29.1 不可变接受契约

新增 Domain 值对象/Port、DAL MyBatis Adapter 和 App 策略用例。V52 PG/H2 新建 `run_execution_policies`，单独列保存版本、部署范围、非秘密能力摘要、文件数/单文件/工作集限额、恢复开关/模式/次数/退避及接受时间；不是把这些信息塞进对话 JSON。记录与 Run 通过组织组合外键关联；PG 强制 RLS，app 角色只有 SELECT/INSERT，没有更新、删除权限。

接受事务在原 Run、触发消息和不可变附件绑定同一事务内保存策略。重复 requestId 返回原 Run，不重新采集配置。主调用、审批继续和 Worker 经同一 RuntimeContext 绑定策略；输入恢复、投影、目录发布、源归档、清单冻结、检查点恢复读取原 Run 的容量契约。历史已授权版本下载仍不受当前写入限额收紧影响；用户/组织总存储配额和实时权限检查没有被取消。

Harness 使用调用级 `WorkspaceTransferPolicy`，不修改共享 Filesystem 的默认值，避免并发任务互相改变限额。物理 I/O 并发、临时盘和 Provider 运输能力仍由当前 Worker 限制，不因旧任务限额较大而无限分配资源。

V53 增加 `assistant_runs.execution_policy_version`，对已有接受记录进行前向补标记，不改写已应用的 V52。新接受任务的记录和标记一起提交。必须有快照但快照丢失时，抛 `RUN_POLICY_SNAPSHOT_MISSING`，不伪装成旧数据读取今日默认值；领取过滤拒绝该任务，恢复查询显示 `MANUAL_ACTION`。无快照的真正旧任务仅保留 LOCAL 兼容路径，外部部署受控转换仍待实施。

### 29.2 领取、恢复和状态一致

消费者协议摘要包含沙箱类型/集群/镜像或模板、对象存储位置，不包含凭证或可变逻辑限额。凭证轮换不改变摘要；不同集群或桶不会错误接管旧任务。Worker 心跳新增物理归档/单文件能力，恢复状态按原契约寻找能承载该任务的消费者，不只判断“有 Worker 在线”。

领取在 SQL LIMIT 之前按部署、协议和物理容量筛选，不能先抢走不兼容任务再失败；过期 attempt 巡检也按原部署范围筛选。恢复决策沿用接受时开关、次数和退避，共享失败账本不在 Web/Worker 之间重置。旧任务使用旧策略，新任务使用新策略，不改变已有取消 fencing 和发布收据检查。

### 29.3 功能回归与真实依赖边界

最终源码完整功能 reactor 返回 0，日志 `/private/tmp/chugou-accepted-policy-fenced-regression.log`；启用 `SAAS_RUNTIME_BODY_MINIO_TEST=true`，保留原性能测试和无源码历史 probe 排除。任务策略集成 7/7，消费者协议 6/6，物理心跳 3/3，DDD/MyBatis 11/11，检查点恢复 24/24；文件发布报告 118 项、1 项 OpenSandbox 环境用例跳过，零失败，不能称为真实沙箱全量通过。

新增门禁覆盖接受原子性、重复提交不更新、旧/新限额区分、固定附件读取、清单冻结、不兼容任务不占据 LIMIT、旧恢复预算、缺失快照阻断及历史下载。真实 PG 已前向迁移 V53，并验证 FORCE RLS 和 app 无 UPDATE/DELETE 权限。

失败实证也保留：最初受限 JVM attach 导致 Mockito 失败；旧容量测试构造单文件上限大于总量的非法配置，改为合法但不足的策略，保留读取前拒绝断言后重跑。真实 OpenSandbox 首次在拉取 `ubuntu:latest` 时 TLS 超时；固定本地标签和镜像 ID 后仍在服务创建容器阶段返回 `DOCKER::SANDBOX_START_FAILED / No such container`，没有进入模型调用。这些日志分别为 `/private/tmp/chugou-accepted-policy-real-gate.log`、`...-real-offline-gate.log` 和 `...-real-imageid-gate.log`，不计通过，不改为 Cube/E2B。

双进程/浏览器新门禁明确支持 `WORKER_CONSUMER_POLICY=true` 与 `WORKER_CONSUMER_SANDBOX=false`，后者只验证 PG/Redis/MinIO、模型协议和网页恢复，不代替 OpenSandbox 文件初始化/恢复。浏览器首次因本机 Node 未配置 Playwright 路径而未进入页面；使用应用自带 NODE_PATH 重跑，不安装或下载依赖。最终门禁结果在下方追加。

最终真实 PG/Redis/MinIO + 双应用 + 浏览器门禁 **9/9**、根退出码 0，日志 `/private/tmp/chugou-accepted-policy-browser-accepted.log`。明确设置 `WORKER_CONSUMER_SANDBOX=false`，模型仍为本地 HTTP 协议夹具。覆盖无消费者不得假入队、离线持久等待、另一员工 404、独立 Worker 接管、Worker 当前恢复关闭/次数为 1 时旧 Run 仍遵循原三次预算、永久 401 不重试、取消后不领取，以及重启 Web 后新 Run 使用恢复关闭/次数为 1 的新策略。新单文件 1 MiB 限额加 1 字节返回真实 413；原 5 MiB 负向客户端曾因提前拒绝出现 Broken pipe，未把连接错误计为 413 或成功。

真实浏览器完成登录、提交、桌面等待、移动刷新、Worker 重启和最终答复自动交付，保留草稿、只有一次任务提交；1440x1000/390x844 四张截图位于 `/private/tmp/chugou-policy-recovery-ui-20261011/`，已检查布局。真实 PG app 角色不能更新/删除接受策略，设置其他组织后读不到当前 Run 的策略。最终迁移 V53，本轮消费者作用域内活跃 Run/Attempt、全库待归档和 Provider retention 均为 0；Web/Worker/前端验证端口没有监听。

最终打包/Javadoc 返回 0，日志 `/private/tmp/chugou-accepted-policy-package.log`；Python 语法和 `git diff --check` 通过。测试创建的应用和浏览器进程已退出，四个本轮启动的依赖容器恢复为停止状态；未关闭 Docker Desktop，未删除镜像、容器、卷、用户历史或其他线程文档，未自动提交/推送。

### 29.4 仍需完成

新策略收紧后的 OpenSandbox 文件恢复真实复验、其他 Provider/增量兼容路径全流式、容量/heap 和完整故障矩阵仍未完成。R05 有界历史、R06 持久父子资源引用/依赖冻结、R07 重试对账、R09 产物验证、R10 生产交付，以及 R11–R17 仍按文档 39 推进；R08 不实施。没有将本批策略冻结解释为整个方案完成，没有自动提交/推送。

## 30. 第二十七批：执行前意图屏障与 Journal 历史补建

### 30.1 OpenSandbox 验证恢复

2026-10-12 读取既有服务日志及镜像内安装源码，只启动此前停止的本地 OpenSandbox、PG、Redis、MinIO 四个依赖，不删除容器、镜像或数据卷。OpenSandbox 服务重新启动后，以本地固定镜像 `chugou-test/ubuntu:offline-policy-v1` 创建测试沙箱成功，随后删除本轮探针资源。没有证据确认之前 `No such container` 的根因，不能将“重启后不再复现”记为 Provider 源码修复；测试不依赖在线拉取镜像，也不切换 Cube/E2B。

第一次联合门禁已到达真实网页等待/刷新/Worker 重启/最终自动交付，但期间本轮源码修改使第五次 Worker 启动被 Spotless 格式检查拒绝。根退出码 1，日志 `/private/tmp/chugou-policy-sandbox-real-20261012.log`，不计为完整通过。待最终源码格式化后整套重跑；结果在本节后续追加。

### 30.2 可恢复意图与结果身份

- 仅当运行上下文同时装配持久工具 Journal 和 Checkpoint Port 时，在权限评估、有效工具输入和批准状态更新后，执行工具之前提交源消息与检查点。检查点包含仍待执行的 operation ID；提交失败阻断 Journal 准入及工具副作用，不先执行再发现意图未保存。
- 执行前的阻塞提交由 boundedElastic 承载，完成之后才订阅工具执行流；不阻塞模型流事件线程。负向测试从 Reactor 非阻塞调度器发起调用，检查持久化回调没有在该线程上执行。
- 成功结果沿用“Journal 终态提交 -> 源账本提交 -> 后续检查点”的原顺序。普通成功工具消息在持久 Run 下使用基于 Run、Agent 名称、工具调用及完整结构化结果的稳定 ID。相同 Journal 结果重放只有一个来源条目；不同调用、Run 或不同终态不按文本折叠。未启用 Journal 的独立 Core 调用保留原随机 ID 兼容行为。
- 新实例恢复已经提交的 Run 级检查点时，不用合成错误覆盖原 pending intent，也不将 Worker 重投的触发消息当作一次新的模型规划。经正常权限检查和工具准入读取原 Journal，已知成功复用结果，未知副作用仍走既有对账阻断；工具、输入、Run/Task/AgentRun 身份和当前执行租约的验证没有绕过。
- 当前权限撤销即使面对原 ALLOWED 工具也拒绝执行。没有引入“任何错误都自动重试”的通用恢复策略，没有重新执行已经提交成功的外部操作；也不宣称所有外部副作用 exactly-once。
- 源账本 search/list 与 window 一样拒绝零、负数及超大 limit，保留原数据库更小的检索上限和字节预算，不增加全历史读取 API。

### 30.3 验证证据

针对性 reactor 最终根退出码 0，日志 `/private/tmp/chugou-journal-rebuild-tests-final-20261012.log`。Core 提交屏障 15/15，消息构建 4/4，真实 MyBatis/H2 的 Journal 补建 2/2，运行历史 18/18，均无失败/跳过。

补建集成测试明确重建 Harness、内存状态和 Journal 实例，分别注入 Journal 已成功之后的持续源存储故障、源已提交之后的检查点故障。恢复完成时工具只执行一次、终态 Outbox 只有一条、完整结果准确、工具来源记录只有一条，执行权限确实重新检查。执行前检查点失败和恢复后权限撤销均有负向用例。

十万条历史夹具通过既有 MyBatis/H2 写入和真实 Repository 委托执行。先查询末尾 20 条进入工作状态，再实际调用 Harness；模型消息不超过 24 条，归档读取只出现一次 limit=20 的 window，没有 list/search 全历史路径，新输入和最终回复增加两个 seq。接口委托只记录调用，不替换数据库结果；不把该证明扩大成真实 PG 性能或完整 SaaS 自动旧状态迁移。

早期失败保留：`...-tests-20261012.log` 的一次性源故障随后被正式提交屏障重试成功，夹具不满足“源持续不可用”的设定，改为重建前持续失败，未放宽缺失来源断言；`...-tests-retry-20261012.log` 记录 Spring 代理 spy 不能可靠计量的问题，改为接口委托保留真实查询，完整重跑后才计通过。

强化 `worker-consumer-smoke.py`：旧策略的附件恢复除了用户目录下载，还将在 Worker 模型请求前直接流式读取其正在运行的 OpenSandbox 文件，核对完整长度及 SHA-256；实际沙箱读取未完成不得通过。OpenSandbox 恢复集成用例允许沿用部署的镜像环境变量，以本地固定镜像复验，不忽略环境用例。完整功能、打包和最终联合门禁结果在下方追加。

完整功能 reactor 根退出码 0，日志 `/private/tmp/chugou-journal-functional-regression-20261012.log`，显式启用 `SAAS_RUNTIME_BODY_MINIO_TEST=true` 和 `SAAS_CHECKPOINT_OPENSANDBOX_TEST=true`；文件发布/恢复 119/119，正文归档 23/23，DDD/MyBatis 边界 11/11，均无跳过。随后执行前提交的 I/O 调度修正另经受影响 Core/Harness/SaaS、历史、检查点、权限及真实依赖用例重新验证，根退出码 0，日志 `/private/tmp/chugou-journal-io-accepted-regression-20261012.log`。二者分别记录，不将早一轮结果冒充末轮源码的完整回归。

打包/Javadoc 根退出码 0，日志 `/private/tmp/chugou-journal-package-20261012.log`。随后联合门禁在浏览器阶段返回 1，日志 `/private/tmp/chugou-journal-policy-sandbox-accepted-20261012.log`：浏览器 120 秒等待混入父进程的 Maven/Worker 启动，Worker 10:59:56 才启动、11:00:22 进入推理，浏览器 11:00:24 超时，后台 11:00:26 产生答复，不能计为网页已交付。探针增加有界 stdin 就绪握手：JVM 启动仍受原 180 秒上限约束，浏览器等待父进程确认有效消费者后才启动原 120 秒交付等待，保留单次提交、刷新、草稿、实际页面最终答复的全部断言。不调整产品 deadline，不屏蔽失败，不只增加一个模糊的总超时；新门禁需完整重跑。

最终整套门禁 **10/10**、根退出码 0，日志 `/private/tmp/chugou-journal-policy-sandbox-final-20261012.log`。使用真实本地 PG/Redis/MinIO/OpenSandbox、独立 Web/Worker 和真实 Chrome，模型为本地 HTTP/SSE 夹具，不是 API fixture 页面，也不调用企业模型、Cube/E2B。原七项消费者/共享重试预算/永久错误/取消/权限负向全部重新执行；网页真实登录、提交、离线等待、移动刷新、Worker 重启、自动最终交付、草稿保留及只有一次任务提交均通过。

Worker 当前恢复关闭、次数收紧为 1、单文件准入收紧为 1 MiB 时，旧 Run 仍按接受时三次预算恢复；旧 5 MiB 附件在正在运行的 Worker 沙箱内以 1 MiB 缓冲逐块读取，完整长度与 SHA-256 匹配原不可变版本，而不只核对 MinIO/用户下载。新 Web 重启采用新策略，旧大文件仍可下载；新 Run 持久化恢复关闭/次数为 1，实际模型只调用一次；新上传超限 1 字节返回实际 413。app 角色不能修改/删除原策略，另一组织不可读取。

四张 1440x1000/390x844 的等待/完成截图位于 `/private/tmp/chugou-journal-sandbox-ui-final-20261012/`，已人工检查布局、文字换行、草稿和自动交付。最终 PG 仍为 V53，本轮作用域活跃 Run/消费者均为 0，待归档/Provider retention/正文降级队列均为 0；自建 Web/Worker/浏览器已退出，18082/18083 无监听。四个本轮原本停止的依赖容器恢复为停止，不关闭 Docker Desktop，不删除镜像、卷、历史记录或无关文档。Python/Node 语法、差异空白检查通过；当前未提交/推送。

### 30.4 剩余边界

本批未完成旧 JSONL 历史受控转换、完整真实进程强杀/PG 与 Provider 同时故障、父子资源持久使用引用、依赖/Skill 工作集版本冻结、人工重试与对账、生产交付及 R11–R17。已归档工具结果不等于工具产生的外部文件或副作用已封存；源封存前强杀和真实产物完整性仍需要 R02/R06/R09 联合闭合。R08 不实施，保持现有 DDD/MyBatis 分层和暂缓范围，不自动提交/推送。

## 31. R05 旧 SessionTree 历史受控转换（2026-10-12）

### 31.1 实施范围

新增 Domain `LegacyArchiveImportRepository`、租户 MyBatis Adapter、V54 PG/H2 迁移、App 转换用例及 opt-in 管理 API。组织来自管理员 JWT；目标会话和源不可变版本核验同组织/员工/Agent。默认关闭；不改变普通问答路径，不新增数据源/JDBC/消息队列，也不实现 R08 结构化企业记忆。

原始 JSONL 留作只读证据并通过版本外键/GC 条件保留，转换流固定为 `legacy/legacy:<jobId>`。保留源 entry/parent/toolCall ID、行字节位置、逐行 SHA、合成身份标记和归档 seq/hash；没有原 ID 的记录明确标记 legacy，不以相同文字合并。SessionTree 旧内容是渲染文本，工具 ID 只作来源证据，不能伪装为原始结构化调用或恢复执行状态。未知 JSONL 格式拒绝，不猜测解析。

源内容在 SQL 之外通过共享传输许可/私有 spool 流式校验完整 SHA 和大小；批次同时受行数、单行字节和批次字节限制，字节 seek 恢复，无全文件 heap 缓存。`maxBatches` 允许一次校验后连续提交多个短批次，批次间检查 30 秒处理预算；大文件下载时间不计为严格的整个 HTTP 30 秒承诺。

归档源提交后，短事务核对已提交 identity/hash、写来源映射及 CAS 游标；EOF 核对实际映射数量、转换数量和最后字节位置后 COMPLETE。相同稳定源 ID 多次出现时，转换行数不等于唯一消息数。进度提交失败可从已提交源幂等补建，不另建正文写入路径。

迁移固定创建时会话 generation。reset 同事务删除转换进度/映射与 runtime 历史并撤销在途旧 generation；删除会话级联解除证据引用。管理写操作沿用 best-effort AuditService，PG 两表 FORCE RLS，app 不获源绑定字段 UPDATE 权限。

详细方案、限制和 API 操作说明：[40-legacy-history-conversion-runbook.md](40-legacy-history-conversion-runbook.md)。

### 31.2 确定性回归与打包

最终命令日志 `/private/tmp/chugou-legacy-import-final-gate-20261012.log`，Maven reactor **根退出码 0**，9 组共 **71** 个测试，失败/错误/跳过均 0：LegacyArchiveImportIntegration 12、LegacyJsonlReader 4、LegacyArchiveImportController 3、FileObjectGcJob 4、DDD/MyBatis 架构 11、ChatPersistence 2、SessionGeneration 15、PgSessionArchiveStore 18、JournalTranscriptRecovery 2。

覆盖并发同批、源已提交但进度事务失败、新服务重建、重复 ID/文字、无 ID、来源图映射、错误格式/UTF-8/重复字段/行大小、CRLF/BOM/末行字节、对象 SHA 错误、空文件、单次打开多批处理、管理员/owner 负向、默认关闭、会话 reset 在途撤销与重新显式转换、证据防 GC 与解除后正常 GC。十万历史仍为 MyBatis/H2 门禁，不标记为真实 PG 性能验收。

最终打包/Javadoc `/private/tmp/chugou-legacy-import-package-20261012.log`，**根退出码 0**。此前三个编译接线错误（Port 参数顺序、流式 ContentSource checked exception、测试线程池使用超出 Java 17 基线的 API）已修复，失败日志保留；不能将首次失败记为通过。

### 31.3 真实 HTTP/PG/MinIO 强杀门禁

`legacy-archive-import-smoke.py` 最终日志 `/private/tmp/chugou-legacy-import-real-final-20261012.log`，**5/5、根退出码 0**。使用真实本地 PG/Redis/MinIO 与独占应用进程，模型是本地 stub、无外部模型/沙箱调用。本轮没有新增网页交互，不能把 HTTP 门禁表述为新的浏览器验收。

实测管理员创建及重复创建、同员工 MinIO 不可变版本、另一员工 404；在源消息已提交、进度 UPDATE 人工等待时 SIGKILL 自有 JVM，等待 PG 自然检测客户端断开并回滚事务，重新启动真实应用后补建同一范围，源只有 3 条、映射 4 行、无重复；旧游标 409，来源分页精确。在线流消息数未改变、无工具操作重放、原文件字节完全相同、审计存在。以 app 角色和另一组织上下文检查两表不可见，验证 FORCE RLS；删除会话清除转换任务/映射并解除源保留。关闭转换开关重启后 API 404，健康检查仍 200。

失败证据保留：`real-first` 启动脚本在关闭沙箱时仍无条件等待 OpenSandbox，已修复条件启动/等待；`real-retry` 夹具继承 DURABLE 但关闭消费者，被产品能力校验正确阻断，夹具改为 REQUEST_ONLY；`real-configured` 已完成创建和强杀，但清理 DDL 抢在 PG 人工 sleep 结束前导致超时，脚本增加仅该自有 ApplicationName 的自然断开等待，不取消 PG 查询、不放宽产品 deadline。最终完整重跑通过，不能只拼接各次成功片段。

收尾核验：本地 PG 已为 V54，迁移任务 0、故障触发器 0、本轮自有连接/活跃 Run/临时管理员角色均 0，18082 无监听。此前失败门禁留下的准确测试 Agent/会话/迁移通过正常 owner API 清理，日志 `/private/tmp/chugou-legacy-import-cleanup-20261012.log` 根退出码 0。PG/Redis/MinIO 恢复为本轮开始时的停止状态，OpenSandbox 始终未启动；不关闭 Docker Desktop，不删除镜像/数据卷。Python、Shell 语法和差异空白检查通过。

### 31.4 剩余边界

本轮完成 SessionTree 文本型 JSONL 的受控转换基础，不等于所有历史导出格式、在线历史导入向导或可执行检查点转换均完成。一次推进请求仍需完整校验源文件，特别大的长期迁移可继续用经过完整摘要校验的分块清单/对象 Range 优化，但不能牺牲证据完整性。

R05 仍缺真实 PG 十万历史/容量证据、真实工具 Journal/产物联合强杀矩阵；下一优先项仍为 R06 持久父子资源使用引用和工作集依赖/Skill 版本冻结，之后推进 R07/R09 与交付门禁。当前未提交/推送。

## 32. R06 持久沙箱使用引用与回收权（2026-10-12）

### 32.1 实施范围与实际接线

新增框架端口 `SandboxUsageGuard`、Domain 的资源/使用引用与 GC Repository、租户/管理员 MyBatis Adapter、App 准入与回收用例，以及 PG/H2 V55。`AgentConfig` 将用例接入 Harness 生命周期，子 Agent 的 fork 继承该接口，不把 SaaS/PG/Provider 类型放入 Harness。独立 SDK 默认 Noop，兼容原来不带受治理 Run 的调用；部署时选择 Provider，用户不能自行选择沙箱。

受治理调用获得执行槽之后、Provider create/resume 之前，在员工锁下预留物理配额及资源引用。这样既不能超配额后才清理，也不会因为同一执行槽的正常排队过早报错。一个物理资源有独立 Root/Child 引用，子调用借用父句柄而不是 create；借用不登记第二条物理 tracking row，不重复扣物理配额。

资源固定组织/员工/Agent/Run/原会话 generation、Provider 和部署指纹；引用固定 Task/Attempt/lease owner。借用检查父引用与父执行权限，另验子执行权限；文件/命令入口在实际使用前核验引用、当前 Run/generation/Attempt/租约及物理 ID。独立持久心跳不能复活过期/关闭引用，取消/reset 后不能继续使用失效上下文。心跳周期不低于一秒，I/O 不放在 Reactor parallel 线程上。

父结果进入封存之前原子转入 FINALIZING，阻止新借用；仍有活跃子引用时拒绝封存/完成，不宣称输出已完整。父释放若还有使用者转 DRAINING，不做破坏性快照或 shutdown；子释放只关闭自己的引用并清理本地绑定。引用关闭数据库不可用时，不绕过权威记录销毁已暴露的资源。

没有使用者的 Owner 或独立 GC 才能领取 RELEASING token。GC 在资源锁之后重新读取引用，避免等待锁期间心跳已续期却按照旧快照误回收；过期引用可收束为 EXPIRED。后台按原部署指纹领取、有界批次/释放次数及超时令牌，Provider 调用在 SQL 事务之外，只有确认成功才 RELEASED。失败保持 RELEASE_FAILED 和配额占用，不能以日志/void 方法返回冒充释放成功。

旧 `sandboxes`/`sandbox_leases` 回收器在扫描与领取两处都跳过新资源权威尚未释放的物理 ID。管理员 force-evict 对这类资源仅修改旧 tracking 行，不直接关闭共享后端；需要先取消 Run、等待引用关闭/过期后由使用引用 GC 回收。新表证据不依赖 Run/session 外键存在，删除会话后仍可定位后端。

已获得的物理 ID 会在使用权限复验前登记：即使创建返回时任务已被取消，或空预留已回收，释放失败仍留下可重试的后端证据。识别了 Provider 的物理 ID getter 但其返回空时，不以逻辑 sessionId 伪装为已分配后端。Provider POST 未返回 ID 就断连的未知分配窗口仍需 R02 的 Provider 对账处理，不承诺已解决。

Docker 增加精确完整容器 ID 的回收器，不启动/恢复工作区；最终 rm 失败必须抛出，已不存在按幂等成功处理。OpenSandbox/E2B/Cube 沿用各自 Terminator，未把任何 Provider 设为框架唯一依赖。生产 Docker 部署需为不同 daemon 设置不同的 orchestration deployment scope；切换 Provider 前应排空旧部署的占用/回收记录。

### 32.2 权限前向修正

真实 PG 负向检查发现 V10 的默认整表 UPDATE/DELETE 授权仍作用于后续表，仅新增列级 GRANT 不会撤销旧权限。新增 V56，显式撤销 V55 资源/引用表及 V54 旧历史转换表的整表 UPDATE/DELETE，再授予状态/进度列权限和 reset 所需的转换 aggregate DELETE。来源 entry 通过 FK 级联清理，不能直接修改/删除；不改已应用 V54/V55 的校验和。

实测 app 不是 superuser/BYPASSRLS；另一组织上下文读不到资源及引用，app 不能改资源 owner。V56 后整表 UPDATE、owner/source_version_id UPDATE、来源 entry DELETE 均为 false，必要 status/byte_offset 列 UPDATE 为 true。这个修正补上 31.1 对来源不可变字段保护的实际数据库授权，不能把此前仅列级授权写法视作已正确收紧默认权限。

### 32.3 验证与明确未通过的门禁

针对性 reactor `/private/tmp/chugou-sandbox-usage-regression-20261012.log` 根退出码 0，11 组共 111 项，执行 109 项均通过、两项真实环境测试当时未启用。包括生命周期 26、文件代理 34、执行槽准入顺序 2、Docker 释放契约 3、tracking 6、quota 3、物理 ID 1、持久引用 11（跳过 2）、旧回收器 7、管理入口 7、DDD/MyBatis 边界 11。测试 SQL 留在 support 包，没有放宽架构门禁。

真实独立临时 PG17.5 库前向升级到 V56，用 app 主数据源与 admin 回收数据源验证；最终数据库专用门禁 `/private/tmp/chugou-sandbox-usage-postgres-only-20261012.log` 根退出码 0：持久引用 10 项通过，OpenSandbox 一项明确未运行。涵盖新 Guard 实例、物理配额为一仍可借用、父结束后的 DRAINING、最后引用关闭后领取、释放失败与次数上限、部署范围、reset/伪造 scope/lease owner/backend ID、过期不能复活、领取 token、等待续期事务后重查和借用/关闭并发。

真实 OpenSandbox 门禁两次尝试均未通过，保留 `...-postgres-opensandbox-20261012.log` 与 `...-postgres-opensandbox-retry-20261012.log`。首次还准确发现上述默认权限问题；V56 后权限已通过，但 Provider 的创建接口仍返回 HTTP500/DOCKER::SANDBOX_START_FAILED：Docker create 返回完整 ID，紧接着 inspect 返回 404。以唯一诊断容器在 OpenSandbox 使用的 Docker SDK 上独立复现，0/1/2/3 秒等待及 API1.43 均不能查询该 ID，随后针对该 ID 的 remove 成功；探针都在 finally 清理。健康检查 200 不等于能创建沙箱。不修改服务源码、放宽断言或把跳过改算成功，也不改为用户已排除的 Cube/E2B 验证。

因此本轮不能声明“父子共享沙箱真实全链路已验收”，也没有新的浏览器完成证据；已有浏览器门禁属于此前源码版本。扩展功能回归、最终清理结果在下方追加。

扩展功能 reactor `/private/tmp/chugou-sandbox-usage-functional-20261012.log` 根退出码 0，42 组共 489 项，486 项执行通过、失败/错误 0，3 项环境门禁未启用（本地持久引用的两个真实环境入口及文件发布的 OpenSandbox 入口）。涵盖 Core 提交屏障、Harness 子 Agent/沙箱与工具、真实 MyBatis/H2 的十万历史/Journal 补建、旧历史转换、会话 generation、文件发布、Worker 和架构边界；不能称为全部真实依赖门禁通过。

最终 `package`（未关闭 Javadoc）根退出码 0，日志 `/private/tmp/chugou-sandbox-usage-package-20261012.log`。临时 PG 库在自有 JVM 退出、连接数为 0 后删除；现有业务库仍为之前的 V54，没有用临时库替代或删除业务数据。四个本轮启动的依赖容器恢复停止，18082/18083 无监听，唯一诊断容器已清理；不关闭 Docker Desktop，不修改 OpenSandbox 源码/镜像，不删除数据卷。Shell 语法、差异空白检查通过。未提交/推送。

### 32.4 剩余边界与下一步

先恢复本地 Docker/OpenSandbox 创建能力，再启用 `RUN_POSTGRES_USAGE_TEST=true`、`RUN_OPENSANDBOX_USAGE_TEST=true`，重新完成父输入读取、同一物理句柄、父结束后子读写及最后释放的真实门禁，并重跑 Web/Worker 联合链路。当前实现并不支持另一 JVM 直接借用父进程对象；队列中尚未执行的后台任务也不被伪装成当前活跃资源引用。

R06 仍需依赖产物/Skill/既有记忆投影版本冻结、授权按需取回、任务级输出目录与写入冲突控制、后台子任务从同一 Run 清单/检查点重建及故障联合门禁。结构化企业记忆 R08 保持不实施。R05 真实 PG 十万历史/容量与 Journal/产物强杀矩阵、R07/R09、交付及 R11–R17 仍按 39 号矩阵继续，不能把本批持久引用基础标记为整个 R06 或全方案完成。当前未提交/推送。

## 33. R06 附件与依赖文件清单冻结、真实共享门禁（2026-10-12）

### 33.1 持久输入清单与生命周期

扩展 Domain `WorkspaceInputRepository`，由租户 DAL MyBatis 保存 `task_input_manifests` 和逐文件 `task_input_entries`，PG/H2 使用 V57 前向迁移。主键是 Task，绑定组织/员工/Agent/Run/原会话 generation，不绑定易失的 Attempt、进程或 Provider 句柄。同一个 Task 重试或新建执行服务时，读取相同的文件版本、路径、摘要、对象位置和清单版本；不同 Task 不冒充同一份清单。

沿用接受任务时已固定的附件版本；第一次成功准备输入时，在当前执行/session 锁下校验元数据和预算，SQL 之外流式核验大小/SHA，复验 owner/generation/绑定后，短事务原子提交头和全部条目。两个并发准备者只能提交一份一致清单。提交前绑定变化、文件缺失、对象损坏或执行权限撤销均拒绝；提交后新增/修改/移除附件不会偷偷改写该 Task 的既定输入。这不是在创建 Run 时就固定全部系统资源，也不把未成功准备的工作集宣称为已完成初始化。

没有附件/依赖的受治理任务也返回显式空 `WorkspaceInputPlan` 并持久化空清单。框架因此只扫描有界系统资源，不再因为 Optional.empty 回退 `glob("**", "/")` 读取用户全部持久文件。独立 Harness、关闭沙箱等原有兼容入口仍保持原语义。

恢复、元数据选取、对象流打开和关闭都重新检查当前 Attempt/lease owner、Run、会话 generation 和文件 owner/active 状态。用户删除文件后，后续准备/对象读取被拒绝，但原版本证据可保留到所属 Run/Task 清理；这不等于已在运行的沙箱副本被即时擦除，运行中路径权限重验仍需后续收尾。PG 新表 FORCE RLS，显式覆盖 V10 默认权限，app 只获 SELECT/INSERT；不能直接 UPDATE/DELETE 冻结事实。删除 Run/Task 时由外键级联清理清单，不增加第三个数据源、业务 JDBC、领域层 MyBatis 注解或新对象存储事实源。

### 33.2 依赖产物与对象保留

只选择同组织/员工/Run、已成功的依赖 Task 对应产物版本，不取当前文件版本。物化路径为 `inputs/dependencies/<dependency-task-id>/<original-logical-path>`，缺失/无权/非法路径或版本冲突会阻断，不静默退回读取当前版本。Worker 的任务上下文增加该目录约定；仍是可修改的沙箱工作副本，原持久文件版本不回写，不宣称已做 OS 级只读权限隔离。

文件 GC 扫描与实际领取都排除输入清单和 `run_artifacts` 引用，并增加版本/文件索引。后者保护“前置任务已完成、后继任务尚未领取”的窗口，不能等到后继生成清单才开始保留。输入条目的版本外键也阻止并发 GC 绕过引用检查。证据保留期限跟随现有 Run/会话清理，不额外复制用户文件内容、不把 Skills/记忆混入用户产物列表。

### 33.3 回归与真实 PG/OpenSandbox 门禁

Docker SDK 唯一探针的 create/inspect/remove 本次全部成功，先前 create 后 inspect404 的环境故障没有复现。首次重新启用真实门禁后，测试错误地将 `/inputs/...` 当作容器绝对路径，并绕过正式工具的路径归一化，导致文件读取断言失败；测试改用实际 `FilesystemTool` 加 `/workspace` 归一化入口，并检查父写命令确实成功。没有修改产品路径语义来迁就错误夹具。

最终真实独立 PG17.5 库升级到 V57，日志 `/private/tmp/chugou-r06-pg-opensandbox-inputs-20261012.log`，**根退出码 0，25 项执行均通过，无跳过**：输入清单 14 项、持久沙箱使用 11 项。覆盖原版本/重命名、空清单、服务重建、并发冻结、会话删除级联/解除版本 pin、依赖版本与任务目录、app RLS/不可变授权、预算、腐败对象、绑定变化、执行撤销；OpenSandbox 实测同一物理句柄、子 Agent 通过正式工具读取父输入、父结束后子继续读写、最后引用关闭后物理资源回收，Provider create 只有一次。

确定性针对性门禁修复领域记录多构造器导致 MyBatis 自动映射歧义后完整重跑，根退出码 0；没有向 Domain 加 MyBatis 注解。扩展回归及 `install` 构建日志 `/private/tmp/chugou-r06-workset-functional-package-20261012.log`，**根退出码 0，45 组共 504 项，500 项执行通过、4 项环境门禁未启用，失败/错误 0**。四项跳过与上面的实际 PG/OpenSandbox 执行证据分别记录，不算作该回归执行通过。

覆盖沙箱/子 Agent/Core 提交屏障/Worker、文件发布及回收、generation 撤销、十万 MyBatis/H2 历史及 Journal 补建、旧历史转换、架构边界和会话删除。该构建关闭了 Javadoc，不能当作新的 Javadoc 验收。首次格式门禁、构造器映射失败和真实夹具路径失败日志保留，不将首次失败写成通过。

### 33.4 真实双应用和浏览器联合门禁

`worker-consumer-smoke.py` 增加显式空清单、Worker 前后 Task/version/count/bytes 一致、正常删除 Agent 后清单解除的断言。最终日志 `/private/tmp/chugou-r06-workset-worker-browser-20261012.log`，**根退出码 0、10/10**，没有禁用沙箱、跳过浏览器或改用外部模型。

使用真实 PG/Redis/MinIO/OpenSandbox 与独占 Web18082/Worker18083、本地确定性 HTTP/SSE 模型故障夹具。覆盖无消费者不承诺恢复、离线消费者等待、租户/员工隔离、独立 Worker 领取原 Task、HTTP/Worker 共享重试总次数、永久401终止、取消后不可领取。实际 Chrome 登录、任务提交、等待、Worker 重启、自动最终回复、桌面/移动刷新及草稿保留均通过，没有重复提交/重放。

旧 Run 在接受时允许 32 MiB 文件；Worker 改为新 1 MiB 限额后仍恢复原 5 MiB 附件，直接从真实 Worker 沙箱下载并核验完整长度/SHA-256，清单 Task/version/count/bytes 恢复前后完全一致。新 Web 的新 Run 使用新文件/重试策略，超出新限额的上传返回413，历史原版本下载仍正确。新清单确实参与实际 runtime，没有仅用仓储单测替代联合链路。

本轮截图 `/private/tmp/chugou-real-recovery-ui/completed-desktop.png`、`completed-mobile.png` 时间为2026-10-12 17:30，已查看，无文字/控件相互覆盖；不将该目录中此前留下的 `failure.png` 当作本轮证据。浏览器测试是恢复交付流程，不代表完整业务功能/生产模型/容量/灾备验收。

独立临时 PG 库在连接数为0后删除；本地验证业务库通过正常 Flyway 升级到 V57。联合夹具结束后精确查询本轮唯一账号：自有连接、Run 和输入清单均0，15个资源全部 RELEASED、15条引用全部 CLOSED，18082/18083 无监听。正常 API 删除自有测试 Agent，不直接删除其他用户数据。

最终未关闭 Javadoc 的 `package` 根退出码0，日志 `/private/tmp/chugou-r06-workset-javadoc-package-20261012.log`；与33.3中关闭 Javadoc 的回归构建分别记录。Python/Shell 语法与差异空白检查通过。四个本轮启动的 PG/Redis/MinIO/OpenSandbox 依赖容器恢复为停止状态，不关闭 Docker Desktop、不删除镜像/数据卷，未留下临时沙箱容器。保留原有工作区修改及用户文档，未自动提交/推送。

### 33.5 剩余边界

R06 仍未整体完成。当前冻结的是 Task 的附件和依赖文件基础清单；Skill 和现有记忆投影仍需受控、可重建的不可变版本，不新增 R08 结构化企业记忆。还需授权按需取回/追加清单及预算、运行中路径权限重验、子 Task 输出子目录与共享路径写冲突、后台子任务完整继承同一 Run 的系统清单/检查点，以及非持久卷以外部署的 Run 子目录/清单兼容。显式输入工作集仍拒绝共享持久工作卷，不能宣称 Volume 适配已完成；普通非 Volume 的 Provider 选择保持部署配置化。

另一 JVM 不会直接借用父进程对象；本批给它的是可重建文件清单基础，不是完成了所有跨 JVM 子任务资源共享。资源分配 POST 在返回 ID 前断连的未知窗口、真实 PG 十万历史/容量、工具 Journal/产物强杀矩阵、R07/R09 及 R10–R17 继续按文档39执行。当前未提交/推送。
