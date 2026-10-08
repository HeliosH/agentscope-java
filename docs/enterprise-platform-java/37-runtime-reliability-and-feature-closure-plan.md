# 37. 运行时可靠性修复与能力收尾落地方案

> 状态：方案设计完成，首批实施已开始，进度见[文档 38](./38-runtime-reliability-implementation-progress.md)。本文不代表全部功能已经交付。
>
> 设计日期：2026-09-30
>
> 代码基线：`enterprise_platform`，`65b364b8`。
>
> 范围：修复本轮审查发现的非 JWT 问题，并明确既有方案中未完成能力的实施路径。
>
> 用户约束：JWT 密钥问题暂不处理；不得破坏 DDD/MyBatis 架构；沙箱由部署配置选择；当前真实沙箱验收只要求 OpenSandbox。

## 1. 目标、范围与完成定义

### 1.1 本轮目标

1. 用户得到的成功状态必须对应已持久化、可访问并通过必要验证的结果。
2. 同一文件在上传、运行、归档、恢复和下载阶段采用一致的容量规则。
3. 模型中断后恢复路径可执行，不出现“已经安排后台恢复，实际没有消费者”。
4. 长会话和历史文件增长，不导致每次任务重新读取整个历史或整个用户工作区。
5. 所有辅助模型调用保留租户、模型选择、预算、计量和策略边界。
6. 岗位主数据与个人记忆分离；个人记忆可解释、可确认、可修正、可删除。
7. 失败任务、安全重试、未知副作用处理和最终结果验证形成用户可操作的闭环。

### 1.2 明确不做的工作

- 不修改 JWT 默认密钥、签发、轮换或密钥托管实现，不把 JWT 修复作为本文验收项。
- 不新增企业 SSO、组织权限体系或完整 ABAC；复用当前用户、管理员、租户隔离与 RLS。
- 不新增内部模型服务部署；继续复用多模型目录和现有模型网关适配。
- 不接入 PromptGuard，不改变 LlamaFirewall 独立服务、可选启用和本地安全降级原则。
- 不将 CubeSandbox Volume、E2B 或 CubeSandbox 真实服务验证设为本轮门禁。
- 不引入第二套 Agent 框架、消息队列或新的业务数据库。
- 不实现 Office 在线编辑；现有预览继续保留，补充必要的异常与容量验收。

演示账号与生产初始化隔离属于部署治理，而非 JWT 密钥治理，列入后续生产交付批次。企业身份功能暂缓不影响先用现有用户 ID 和管理员角色维护岗位档案。

**风险保留声明：** JWT 问题是明确暂缓，不是已修复。完成本文不能据此宣称所有生产安全风险已经消除。

### 1.3 优先级约定

| 等级 | 定义 | 本方案范围 |
|---|---|---|
| P1-A | 先修正确性和可恢复性，不依赖新增产品功能 | 辅助模型路由、归档完整性、统一容量、恢复消费约束、增量会话归档 |
| P1-B | 降低规模化成本并闭合用户任务流程 | 任务工作区、人工重试与对账、结构化记忆、结果验证 |
| P1-C | 生产交付和发布门禁 | 镜像、配置、故障注入、恢复演练、演示数据隔离 |
| P2 | 已有方案中的后续增强，独立批次交付 | 定时任务、连接凭据治理、H03/H05/H06 剩余及 H09-H13 |

不再把每项新增能力都标为 P0。P1-A 必须先于 P1-B/P2 的广泛上线。

## 2. 审查事实与工作项映射

### 2.1 已确认的问题

| 编号 | 审查事实 | 根因与落点 | 工作项 |
|---|---|---|---|
| F01 | 选择模型 B，记忆提炼调用部署默认模型 A；离线复现 `defaultCalls=1, chosenCalls=0` | `MemoryFlushManager`、`MemoryConsolidator` 重建消息后直接调用 `Model.stream`，未保留组织/模型绑定 | R01 |
| F02 | 33 MiB 产物被跳过，`projected=0`，检查点仍为 READY | `SandboxBackedFilesystem` 只返回成功投影数量；`WorkspaceCheckpointContext` 不知道跳过/截断 | R02 |
| F03 | 5 MiB 文件可进入普通文件链路，检查点恢复却拒绝 | 上传/投影 32 MiB 与恢复 4 MiB/64 MiB 是不同硬编码限制 | R03 |
| F04 | 默认调度器关闭，错误路径仍可能返回后台恢复提示 | Controller、Worker、LeaseRecoveryJob 对能力开关的理解不一致 | R04 |
| F05 | 相同消息 ID 归档两次产生两条记录 | `offloadMessages` 每次遍历整个上下文，并为 SessionEntry 生成新 UUID | R05 |
| F06 | 非持久卷初始化扫描全工作区；用户配额 5 GiB、初始化总量上限 256 MiB | 用户持久文件域与任务执行工作集尚未分离 | R06 |
| F07 | CompletionGate 验证结构和引用，不证明产物满足业务验收 | 缺少针对真实产物的校验及独立只读验证流程 | R09 |

四项离线复现分别为 F01、F02、F03、F05。F04/F06/F07 为源码确认的路径和能力限制，不宣称已经在生产环境复现。

关键源码入口：

- [MemoryFlushManager](../../agentscope-harness/src/main/java/io/agentscope/harness/agent/memory/MemoryFlushManager.java)、[MemoryConsolidator](../../agentscope-harness/src/main/java/io/agentscope/harness/agent/memory/MemoryConsolidator.java)：F01、F05。
- [SandboxBackedFilesystem](../../agentscope-harness/src/main/java/io/agentscope/harness/agent/filesystem/sandbox/SandboxBackedFilesystem.java)、[SandboxLifecycleMiddleware](../../agentscope-harness/src/main/java/io/agentscope/harness/agent/middleware/SandboxLifecycleMiddleware.java)：F02、F03、F06。
- [SaasChatController](../../agentscope-saas/agentscope-saas-app/src/main/java/io/agentscope/saas/app/chat/SaasChatController.java)、[DurableTaskWorker](../../agentscope-saas/agentscope-saas-app/src/main/java/io/agentscope/saas/app/orchestration/DurableTaskWorker.java)：F04。
- [CompletionGate](../../agentscope-saas/agentscope-saas-orchestration/src/main/java/io/agentscope/saas/orchestration/CompletionGate.java)：F07。

### 2.2 未完成能力

| 编号 | 能力 | 工作项 | 既有文档 |
|---|---|---|---|
| U01 | 岗位档案、个人记忆卡片、确认/冲突/删除、规划前检索 | R08 | 18 |
| U02 | 失败 Run 人工重试、未知工具副作用对账 | R07 | 19、35、36 |
| U03 | 产物实质校验、独立只读 Verifier | R09 | 19、21 |
| U04 | 应用部署基线、多实例/强杀/真实断流、灾备演练 | R10 | 09、19、21、36 |
| U05 | 用户级持久化定时任务 | R11 | 21 |
| U06 | MCP 逐连接授权、凭据引用、刷新吊销 | R12 | 21、23 |
| U07 | 统一预算绑定、完整步骤快照、能力投射、H09-H13 | R13-R17 | 35、36 |

旧文档中的“框架具有等价能力”不作为交付证据。本文区分：已有实现、待新增实现、待真实环境验收。

## 3. 保留的架构与约束

### 3.1 数据和运行职责

| 数据/资源 | 权威存储或管理面 | 沙箱中的作用 |
|---|---|---|
| 用户会话和最终问答 | PG `chat_messages`，现有 seq/游标 | 按需读取，不全量复制 |
| 运行消息、工具事实、上下文检查点 | PG，工具大结果引用 MinIO | 只恢复检查点所需窗口和文件 |
| 上传/生成文件 | PG 元数据与不可变版本，MinIO 字节 | 挂载/下载当前任务所需版本 |
| 企业岗位档案 | PG 企业主数据 | 只读、限预算任务上下文 |
| 个人记忆 | PG 结构化卡片；Mem0 可选语义投影 | `MEMORY.md` 为可重建只读投影 |
| 租约、并发与事件热状态 | PG 持久化租约事实，Redis 加速与协调 | 不以容器存活判断任务完成 |
| 沙箱计算资源 | 当前部署唯一 Provider | 一个 Run 的父子 Agent 共用；按租约释放 |

继续使用 PG、Redis、MinIO，不增加 Kafka、独立工作流引擎或新的事实源。Mem0 不可用时，任务以 PG 检索和已验证投影继续运行。

### 3.2 DDD/MyBatis 边界

1. `agentscope-saas-domain`：聚合、值对象、状态机、Repository Port；不引入 Spring/MyBatis/HTTP 客户端。
2. `agentscope-saas-dal`：Mapper、Data Object、TypeHandler、Repository Adapter；新增关系数据读写全部在此实现。
3. `agentscope-saas-app`：应用用例、事务、Controller/Worker 装配；业务代码不直接操作 Mapper。
4. `agentscope-saas-orchestration`：复用 Run/Task 调度、预算、计划和完成门禁，不创建平行调度体系。
5. `agentscope-saas-storage`：流式对象存储和不可变对象接口，不承载文件授权决策。
6. `agentscope-saas-sandbox`：部署 Provider、资源配额、租约、能力检测与释放对账。
7. `agentscope-core`/`agentscope-harness`：定义通用扩展 Port 和运行协议，不依赖 SaaS、PG 或 MinIO。

Tenant/Admin 双访问通道继续连接同一 PG 数据域。普通用户读写使用 Tenant 通道；跨租户领取、回收使用受控 Admin 通道，领取后显式绑定租户执行。不得通过 Admin Mapper 绕过普通请求 RLS。

### 3.3 不变量

- `SUCCEEDED` 必须建立在完整产物提交和必要验证通过之后。
- 不确认工具外部结果时，禁止重放非幂等副作用。
- 恢复绑定原文件版本，不读取同名文件的最新版本替代历史输入。
- 租约失效后旧 Worker 不得提交新结果；数据库写入、文件提交和消息发布都需相应 fencing。
- 外部对象存储与 PG 采用可恢复的顺序提交，不宣称跨系统原子事务或通用 exactly-once。
- 用户输入不能修改岗位主数据，也不能通过 `MEMORY.md` 绕过记忆审批。
- 用户看不到 Provider 切换入口；Provider 变化属于部署变更。

## 4. R01：统一主调用和辅助模型调用

### 4.1 方案决策

新增一个应用层 `ModelInvocationService`，集中处理模型路由绑定、预算、准入、超时与计量。主推理、摘要、记忆提炼、记忆整合、Verifier 使用相同服务契约，但按 `purpose` 应用不同上限。

Core/Harness 只接收通用调用 Port/绑定后的 `Model`，不认识 SaaS 租户类。直接使用 Harness 的非 SaaS 调用方可继续使用原构造器；SaaS 装配必须采用受治理适配，不能静默落入无身份默认模型。

建议值对象：

```text
ModelInvocationScope
  tenantId / userId / runId / taskId / agentRunId
  purpose = REASONING | COMPACTION | MEMORY_EXTRACT | MEMORY_CONSOLIDATE | VERIFY
  requestedModelId / routeSelectionPolicy / invocationId
  inputBudget / outputBudget / deadline / policyVersion

BoundModelInvocation
  modelId / modelRouteVersion / contextProfile / inputEstimator
  immutableModelHandle / invocationScope
```

### 4.2 路由和预算规则

1. 默认 `INHERIT_SELECTED_MODEL`：从本次可信 RuntimeContext 解析组织与所选模型，在构建辅助提示词前绑定路由。
2. 可以由组织管理员配置辅助用途模型；只能选本组织可用模型，不能默默退回部署全局模型。
3. 路由信息放可信调用属性或消息 metadata，不作为提示词正文拼接，也不接受用户提供的组织 ID。
4. 同一调用的压缩预估、最终消息构建、输出预留、最终发送共用同一绑定句柄；热更新影响下一调用。
5. 绑定的模型被撤销时重新校验策略，取消本次调用或返回明确失败，不能用过期能力执行。
6. 辅助调用输入预算取 `min(用途上限, 所选模型可用输入预算)`，扣除系统、工具、输出和安全预留。
7. 记忆提炼初始输入上限建议 8,000 token、输出上限 1,024 token；它们是部署策略上限，不替代模型窗口计算。
8. 超长输入按未处理消息分页/分块，禁止把全上下文或当日全部日志一次送给模型。
9. 辅助调用拥有明确 timeout 和总尝试数；失败写持久化待处理状态，不阻止已有最终结果交付。
10. 调用次数/token 计入组织与用户配额，并关联源 Run。同步执行计入任务预算；异步记忆任务使用独立辅助预算，不能在源 Run 已终态后修改其调度状态。

### 4.3 记忆收尾生命周期调整

不能直接把 `concatWith` 改回不受控 `.subscribe()`。正确的收尾分两层：

- **关键提交**：完成本轮增量运行消息、归档清单和记忆提炼任务的 PG/Outbox 提交，再允许沙箱释放。
- **辅助计算**：后台从已提交内容提炼和整合记忆，不依赖已经释放的沙箱，不延迟用户回答。

在 R05/R08 完成前保留当前等待机制，先补有界时间和正确路由。后续逐步移除 `MemoryMaintenanceMiddleware` 的同步 `.block()`，改为可恢复后台任务；维护必须发生在增量提交之后，不依赖中间件注册顺序猜测。

### 4.4 修改位置与验收

- `ModelCatalog`、`ModelRouteFactory`：增加显式可信 Scope 绑定入口，复用现有 Route/Profile/Estimator。
- `AgentConfig`：注入统一调用适配，不构建第二个绕过治理的模型客户端。
- `MemoryFlushManager`、`MemoryConsolidator`、`CompactionMiddleware`：使用绑定句柄与用途预算。
- 治理/用量服务：以 `invocationId` 幂等结算，模型失败不能重复收费或重复扣减配额。
- 测试：两个组织各两个模型；选择 B 的所有辅助调用都走 B；辅助模型独立配置生效；组织间不可串路由；模型热更新、撤销、限流和超时均有确定行为。

## 5. R02：产物完整提交，不静默遗漏

### 5.1 方案决策

把投影返回值从单个 `int` 升级为结构化 `WorkspacePublicationReport`。它必须描述发现的文件，而不只是成功上传的文件。

```text
WorkspacePublicationReport
  scanComplete / scannedFiles / candidateFiles / publishedFiles
  unchangedVerifiedFiles / publishedBytes / manifestVersion
  rejectedFiles[] / failedFiles[] / omittedFiles[]
  entries[] = path + size + sha256 + immutableVersionId + disposition
```

`disposition` 至少包括 `PUBLISHED`、`UNCHANGED_VERIFIED`、`OPTIONAL_EXCLUDED`、`REJECTED`、`FAILED`。只有明确策略排除的运行临时文件可以是 `OPTIONAL_EXCLUDED`；不能因为文件太大就把用户产物当临时文件。

### 5.2 完成门禁

- 缺失必需产物、扫描被截断、超过文件/总量配额、对象上传失败、版本登记失败、摘要不一致，都不能进入成功终态。
- 持久卷上的 unchanged 文件必须引用已经存在且摘要匹配的不可变版本；不应仅比较上传数量与新增目录数量。
- 不稳定的对象存储故障进入归档待重试；确定的容量违规返回具体容量错误和受影响文件，不无限重试。
- 前端可显示“执行完成，结果归档中”，但不能发出最终 `runFinished/succeeded`。
- 返回给前端的错误不含内部对象密钥、认证信息或敏感路径；详情保留在授权审计中。

### 5.3 外部存储提交协议

新增/扩展 `artifact_publications`，保存 `runId/attemptId/leaseOwner/manifestVersion/status` 和每个产物的提交状态：

```text
DISCOVERED -> TRANSFERRING -> OBJECT_STORED -> CATALOG_COMMITTED -> READY
                           -> RETRY_PENDING
                           -> REJECTED / FAILED
```

1. 先持久化期望清单和短期容量预留，租约有效时才能认领提交。
2. 在数据库事务外流式写入不可变对象，校验长度与 SHA-256。
3. 短 PG 事务登记 FileVersion、RunArtifact 和 Outbox，并释放/结算预留。
4. 全部必需清单项完成后写 READY，之后验证并完成 Run。
5. 对象成功、PG 失败：从提交记录重试登记；无有效引用的对象按现有 GC 队列回收。
6. PG 提交、响应丢失：按出版记录与版本唯一键返回既有结果，不再创建第二版本。

任何数据库锁都不跨整个 MinIO 网络传输。归档失败时由独立有界 retention lease 保留沙箱，业务 attempt 不无限占用并发槽位；到期仍未取回产物必须标记不可恢复并告警，不能谎报成功。

生命周期中间件必须先取得发布收据，再决定是否销毁资源。待补登但对象已完整保存时可以释放沙箱；仍缺对象字节时必须持久化 retention lease/恢复快照引用，并让后台接管清理，不能仅在 RuntimeContext 放一个内存标志。如果 Provider 无法保留资源且没有完整快照，应明确失败并标注产物不可恢复，不继续宣传“自动归档重试”。后续用户重试是否允许重新生成，仍受工具副作用与输入版本约束。

### 5.4 修改位置与验收

- Harness：`SandboxBackedFilesystem`、`SandboxLifecycleObserver`、`SandboxLifecycleMiddleware` 的报告契约。
- 应用：`WorkspaceCheckpointContext`、`WorkspaceArtifactService`、`WorkspaceProjectionCatalogSink`。
- Domain/DAL：发布记录、期望清单、容量预留、幂等登记与对账查询。
- Controller/Worker：统一成功门禁，归档错误与模型错误分开分类。
- 验收：33 MiB/数量超限/总量超限不再静默成功；任意上传点和登记点故障可恢复；已验证 unchanged 文件不被误判缺失；浏览器刷新后能看到归档状态和最终产物。

## 6. R03：统一容量与流式文件链路

### 6.1 统一策略

新增通用 `WorkspaceTransferPolicy` 值对象，由 SaaS 部署配置装配并传入 Harness。删除投影和恢复各自的硬编码限值。

| 配置 | 第一批建议值 | 说明 |
|---|---:|---|
| `max-file-bytes` | 32 MiB | 上传、生成产物登记、投影、恢复共同上限 |
| `max-run-workspace-bytes` | 256 MiB | 本 Run 的输入、依赖与输出工作集，不是用户总存储量 |
| `max-run-files` | 5,000 | 任务工作集文件数量 |
| `transfer-buffer-bytes` | 1 MiB | 实现缓冲目标，不允许全包常驻内存 |
| `max-concurrent-transfers-per-worker` | 2 | 防止并发任务耗尽 I/O/内存 |
| `max-spool-bytes-per-worker` | 1 GiB | 临时磁盘独立配额，受磁盘容量校验 |

这些值是初始建议，不是已生效配置。用户长期存储配额仍是独立规则；不把 5 GiB 用户配额误当单任务初始化预算。

启动时验证关系：单文件上限不得大于单 Run 上限；恢复能力不得小于可提交文件上限；PG BYTEA 测试后端若能力更小，明确限制该部署能力。每个 Run 保存接受时的政策版本，恢复沿用该版本；政策收紧不能静默裁掉历史产物，应明确拒绝并提供处理方式。

### 6.2 流式实现

1. `FileObjectStore` 增加 `openRead` 与 `putStream`，保留有界 byte[] 兼容入口供小文件和现有测试使用。
2. 文件目录负责授权和版本；流式对象接口不能凭 objectKey 绕过授权。
3. tar 输入逐项处理，以受控临时文件/流式 sink 计算摘要和长度；禁止整包 `ByteArrayOutputStream.toByteArray()`。
4. 恢复端从文件版本逐项读取，流式生成归档/逐文件传输；不同 Provider 通过能力适配，不迫使核心退回全量 byte[]。
5. 防御绝对路径、`..`、符号/硬链接、重复条目、异常声明大小、压缩炸弹和解压后总量超限。
6. 前端预览上限是独立资源规则，可低于可下载上限；提示“可下载但不支持当前预览大小”，不能影响任务成功。
7. 下载增加 Range 能力用于大文件/媒体，鉴权仍由应用端完成；默认不暴露需用户公网访问的对象存储地址。

### 6.3 验收

- 5 MiB、31 MiB 文件保存和重建均成功，摘要一致；默认 33 MiB 明确拒绝而非成功。
- 将统一单文件上限配置为 64 MiB 后，33 MiB 保存、归档、恢复、下载全部成功。
- 达到文件数/总量上限和刚超限各有用例；拒绝发生在无界分配前。
- 多路并发转移的 heap 增长由缓冲和元数据限制，不能随整个归档大小线性常驻。
- 原小文件上传、文本预览、版本下载不回归；大文件断开下载及时关闭流并清理 spool。

## 7. R04：后台恢复能力与部署配置一致

### 7.1 配置决策

新增显式恢复模式：`REQUEST_ONLY`、`DURABLE`。废除 Controller 自行推断“有 PG 所以能后台恢复”。

| 模式 | 必需条件 | 请求内尝试耗尽后 |
|---|---|---|
| `REQUEST_ONLY` | 可不启动 Worker | 明确失败，保留已提交检查点，不发送后台已安排事件 |
| `DURABLE` | 持久化编排、可恢复模型策略、可消费 Worker/恢复巡检、必要工作区持久化 | 在事务成功入队后进入恢复等待并由 Worker 接管 |

单进程部署时 `DURABLE + scheduler=false` 启动失败。分离 Web/Worker 部署允许 Web 不轮询，但必须显式声明外部 Worker 模式，并通过持久化 Worker 心跳证明消费者存在；不能仅凭配置字符串声称可恢复。

### 7.2 恢复契约

- Controller、Coordinator、LeaseTracker、Worker、LeaseRecoveryJob 使用同一个 `RecoveryCapability`。
- 只有恢复事务提交后发 `run_recovery_scheduled`；事件包含恢复状态、次数、下一执行时间和 runId。
- 认领失败/租约已丢失时重新读取权威 Run 状态，返回实际状态，不固定显示“已安排”。
- Worker 临时不可用但能力合法：队列保留，前端显示“等待执行资源”；超过队列等待 SLA 告警。
- `REQUEST_ONLY` 中直接进程丢失的遗留 attempt 必须由清理巡检收敛为失败/人工处理，不依赖关闭的任务消费器。
- 总尝试数涵盖请求内与后台阶段，不在切换到 Worker 时重新归零；存在退避、抖动和整体 deadline。
- 永久 4xx、权限拒绝、上下文不支持、用户取消和容量违规不进入模型恢复。

### 7.3 验收

覆盖开关组合表、外部 Worker 心跳缺失、入队失败、事件丢失、Worker 暂停恢复、重启和模型持续故障。前端文案与 PG 状态一致；取消排队中的恢复任务不会被 Worker 再领取执行。

## 8. R05：运行消息增量归档与有界读取

### 8.1 存储决策

继续保留 `chat_messages` 作为用户可见问答，不把完整工具轨迹混进一个问答字段。

新增 `runtime_messages` 作为运行历史的顺序账本，承载模型上下文中需要恢复/检索的规范化消息；大工具正文存 MinIO 引用。复用 `tool_operations` 作为工具副作用事实，不再存第二套工具终态。JSONL 是兼容导出/冷归档，不再是 SaaS 运行的唯一权威日志。

建议字段：`org_id,user_id,session_id,agent_run_id,message_id,seq,parent_message_id,role,content_ref,small_content,content_hash,created_at`。源消息 ID 必须稳定，seq 由数据库账本分配，不能用生成 UUID 或 token 流式片段数量代替。

约束：

- 唯一 `(org_id, session_id, agent_run_id, message_id)`，相同 ID/摘要重复提交返回既有记录。
- 相同 ID/不同内容拒绝；流式草稿与最终消息分开，不能覆盖已提交终态。
- 顺序域为 `(org_id, session_id, agent_run_id)`；用短事务锁定游标行后分配 seq。
- 索引 `(org_id, session_id, agent_run_id, seq)`；按 `afterSeq/beforeSeq + limit` 读取，不使用深 OFFSET。
- Checkpoint 保存 archive cursor、摘要、保留窗口、事实和文件清单版本，不重复携带整个历史。

### 8.2 Core/Harness 接线

增加通用 `SessionArchiveStore` Port，支持 `appendIfAbsent/readWindow/exportRange`。SaaS 使用 MyBatis Adapter，独立 Harness 可用文件 Adapter。文件 Adapter 同样保留源消息 ID、跳过已经提交条目，并使用索引/分段文件而非每次加载全文件。

工具终态先提交 Journal，再把结果对应消息提交账本并创建 Checkpoint。中间崩溃时从 Journal 重放结果补齐消息；未提交工具结果不能被 Checkpoint 引用。保持现有顺序可恢复语义。

每轮仅提交新增消息；摘要和记忆处理使用待处理 seq 范围。消息归档必须在沙箱释放前成为持久化事实，冷导出可以异步，但异步作业只读 PG/MinIO，不能再访问已经释放的沙箱。

### 8.3 历史迁移与验收

- 已有 JSONL 内容相同不代表是重复消息，禁止简单按文本去重。
- 保留旧文件为只读证据；有稳定 msg/toolCall ID 的条目可建立映射，没有稳定 ID 的条目标记 legacy 来源，不能假装精确还原原 seq。
- 采用新写入单路径、旧读取兼容；转换任务具备游标、幂等、数量和摘要校验。
- 对同一稳定消息提交 1/2/100 次都只有一条；并发提交和重启不重复。
- 10 万条历史下，单次运行只读取检查点尾部和受限检索窗口；测试禁止出现全历史读取调用。
- 会话搜索、网页前后分页、重复输入去重、审批恢复和工具配对保持正确。

## 9. R06：按任务清单初始化工作区

### 9.1 工作区边界

用户持久文件域不等于沙箱工作集。新增 `RunWorkspaceManifest`，固定当前 Run 必需的版本和路径：

```text
inputs/                  当前任务明确引用的附件，只读或写入独立工作副本
outputs/                 本 Run 生成的可交付文件
scratch/                 临时文件，默认不对外发布
runtime/                 检查点和有限记忆投影，不与产物混合
skills/                  部署/任务锁定版本的只读技能资源
```

清单项保存 `fileId/versionId/path/digest/size/source/readOnly`。来源限于：本轮附件、用户明确引用的历史版本、依赖任务产物、当前重试的工作区检查点、受控的技能和记忆投影。

### 9.2 初始化和运行规则

1. 创建 Run 时固定附件版本，生成受预算约束的清单，不执行用户根目录 `glob("**")`。
2. 开始任务时只恢复清单文件；不需要的历史文件通过授权文件检索/取回工具按需加入。
3. 按需取回也检查文件数/容量/权限，追加清单版本和事件，不能绕过工作区预算。
4. 同一 Run 的父子 Agent 共用沙箱和逻辑工作区；子 Agent 只能使用授权路径，不能读取其他 Run。
5. Root/Child 持有资源使用引用，父调用结束但子任务未结束时不能销毁仍在使用的沙箱。引用必须可随失联 lease 回收，不能仅靠内存计数。
6. 多写子任务采用任务级输出子目录与写入冲突控制；对共享路径有写冲突的任务串行。只读并行还需 Provider 和工具能力允许。
7. 后台子任务启动时父沙箱已释放，则从同一 Run 的清单/检查点恢复；不依赖父进程对象，不回退加载用户所有文件。
8. 长期记忆归 PG，不以共享用户工作卷或可编辑 `MEMORY.md` 作为事实源。

### 9.3 Provider 与持久卷

- Sandbox Provider 的选择仍由 `SAAS_SANDBOX_TYPE` 决定，不出现在用户模型选择或任务参数里。
- 当前非持久卷部署优先使用 Run 级隔离键，用户长期域通过目录和对象存储访问。
- 已有 CubeSandbox 用户 Volume 可以作为缓存/兼容部署，不因本文删除；先收敛 Run 子目录与清单范围，不强制每个 Run 新建 Volume。
- Volume 命中时按清单版本差异同步，所有必需文件依然必须有持久化对象版本；不能把“卷上存在”当已归档。
- Provider 更换时从清单重建新资源，不把旧 Provider sessionId 当新 Provider 的恢复句柄。
- 本方案真实验收仅要求 OpenSandbox；其他 Provider 通过接口契约/能力测试证明没有核心类型耦合，真实环境验证另行授权。

### 9.4 验收

准备一个历史文件超过 5 GiB 的用户，仅引用一个 5 MiB 附件，任务初始化必须只转移该附件及有限系统资源；不得因用户总历史超过 256 MiB 失败。核验父子 Agent 可读同一输入、输出路径分离、子任务结束前资源不释放、无跨用户/跨 Run 文件访问。

## 10. R07：失败 Run 重试与未知副作用对账

### 10.1 重试语义

自动模型恢复继续复用当前 Run，并创建新 Attempt。用户对已终态失败 Run 的人工重试创建带 `retry_of_run_id` 的后继 Run，原 Run/Attempt/产物保持审计可追溯，不把历史失败改写成从未发生。

人工重试不是简单重新发送原 prompt：

1. 读取原检查点、文件版本、计划与工具 Journal。
2. 验证当前权限、模型、技能、Provider 和预算；不恢复已撤销能力。
3. 复用规范哈希一致、产物真实存在且通过验证的成功节点。
4. 重做失败/受影响节点；存在未决非幂等 operation 时暂停，先对账。
5. 重新执行的步骤与新的业务意图分开。历史已提交副作用结果通过显式 `recovery_bindings` 继承；不能因为创建了新 Run 就给它一个新 operationId 然后重复执行。
6. 来源 operation 引用必须校验组织、用户授权、工具版本、调用 ID、输入摘要和已确认终态；源事实不被复制覆盖。变更了输入或工具契约时不能默认继承副作用结果。

### 10.2 拟新增接口

| 方法 | 路径 | 行为 |
|---|---|---|
| GET | `/api/agents/{agentId}/runs/{runId}/retry-preview` | 返回可复用节点、重做节点、未决副作用和阻断原因 |
| POST | `/api/agents/{agentId}/runs/{runId}/retry` | 必须 `Idempotency-Key` 和 expectedRunVersion；返回 202/既有后继 Run |
| GET | `/api/agents/{agentId}/runs/{runId}/operations` | 分页查看允许当前用户看到的工具执行事实 |
| POST | `/api/admin/runs/{runId}/operations/{operationId}/reconcile` | 管理员提交外部核实证据和预期版本 |

这些是拟新增接口，不是现有 API。404/403/409 语义需要与当前 Controller 风格一致，不能向无权用户泄露运行存在性。

### 10.3 对账状态与限制

- `OUTCOME_UNKNOWN` 保持阻断；可先由 Provider/业务 Adapter 的幂等查询核实。
- 人工证据确认成功：写追加式 resolution 和结果引用，继承成功结果，不重放工具。
- 人工证据确认未发生：按显式审批创建新的受治理执行许可，仍检查工具重试安全。
- 无法确认：保持待处理，允许取消后继任务，不提供通用“忽略风险并重试”按钮。
- 对账证据与决定具备 actor、时间、版本、理由和外部记录引用，敏感正文受访问控制。
- 所有重试都受总恢复次数、Run/组织预算和截止时间约束；并发点击只创建一个后继 Run。

### 10.4 前端与验收

RunInspector 增加重试预览、失败原因、后继 Run 链接和待人工处理提示。普通用户不能自行认定外部副作用没有发生。覆盖重复点击、跨用户访问、权限撤销、已提交 shell/业务工具不能重放、未知结果阻断、取消与重试并发冲突。

## 11. R08：岗位主数据与结构化个人记忆

详细字段和业务规则沿用 [18 号方案](18-enterprise-memory-profile-implementation.md)，本节补齐与本轮可靠性工作、DDD 和运行时的接线；不创建与 18 冲突的第二套记忆体系。

### 11.1 三种数据不能混用

- **原始对话**：PG 顺序账本，作为证据，默认不投递 Mem0。
- **岗位档案**：`employee_profiles`，由现有 org admin 或企业同步维护；用户与 Agent 只读。
- **个人记忆**：`memory_cards`，偏好、用户明确确认的个人能力、项目事实和已验证任务经验；具有版本、来源与状态。

`users.role` 仍是平台权限角色，不用来保存岗位；岗位档案不参与模型自动更新。

### 11.2 写入与检索流程

```text
增量消息/已验证任务结果
  -> 持久化记忆提炼作业
  -> 有界模型提炼
  -> MemoryPolicyEngine
  -> candidate / approved / rejected
  -> PG 卡片与版本 + projection Outbox
  -> Mem0 可选投影

新任务
  -> PG 岗位档案
  -> 当前有效个人记忆候选检索
  -> Mem0 返回卡片 ID/版本作为召回提示
  -> PG 重新验证状态、权限、版本
  -> 限预算 TaskMemorySnapshot
  -> 任务规划与执行
```

任务完成、长会话压缩前和用户明确保存偏好是提炼触发点；均按 source message ID/seq 游标幂等处理。失败/取消任务只能提炼明确用户偏好或经独立验证的事实，不能把未完成结果写成成功经验。

### 11.3 治理规则

1. 密钥、认证信息、岗位推断、系统提示、完整工具输出、任意文件正文禁止进入个人记忆。
2. 明确偏好按组织策略批准；推断偏好必须候选确认，企业可配置所有自动记忆需确认。
3. 状态至少 `CANDIDATE/ACTIVE/SUPERSEDED/REJECTED/DELETED`，冲突通过版本 CAS 和用户确认处理。
4. 用户删除先在 PG 生效，检索即时排除，再异步删除 Mem0；旧索引命中不能重新激活卡片。
5. Mem0 写入已批准卡片的规范化文本，关闭原始对话 `infer=true` 的新写入；投影保存卡片 ID/版本/组织/所有者。
6. 现有 `MemoryProjectionRepository` 是投递账本 Port，不等于已经有卡片索引映射表。扩展版本化 UPSERT/DELETE 作业，不把成功 conversation 投递状态冒充卡片状态。
7. Mem0 不可用时使用 PG 关键词/全文检索和明确偏好；跨语言中文质量需专门验收，不宣称通用全文配置天然支持中文分词。
8. `MEMORY.md` 由卡片渲染，内容包含投影版本和只读标记；Agent 写入尝试走候选工具，不能直接覆盖权威记忆。
9. 企业岗位固定置顶，其他记忆按相关性、可信度、时效排序；总预算取组织上限与模型预算比例较小值。
10. TaskMemorySnapshot 保存用到的 profile/card/version 引用，不包含模型凭证；撤销/删除在后续模型调用前重验，不能无限沿用旧快照。

### 11.4 API、UI 与迁移

- 复用 18 中的 `/api/me/employment-profile`、管理员岗位维护、个人记忆 CRUD/确认/删除接口设计。
- 用户页增加岗位只读页、个人记忆及候选确认；管理员维护岗位，不代替用户随意修改偏好。
- 历史 `memory_events` 与 JSONL 保留审计；不自动把所有旧 Mem0 文本升级为 ACTIVE 卡片。
- 旧 Mem0 数据通过受控迁移生成候选或停用；新 namespace/version 启用前明确旧 namespace 的检索截止点。
- 为旧行为提供只读兼容和临时开关；不能回滚成再次把删除记忆召回或原始对话无审查投递。

### 11.5 验收

岗位只能管理员维护；“我现在是部门负责人”的用户陈述不能改岗位。偏好候选确认后跨任务生效、冲突可更正、删除后旧 Mem0 命中被过滤；Mem0 故障不阻断任务；两个组织相同用户标识不串记忆；每次规划都有有界记忆快照。

## 12. R09：完成门禁、产物校验与只读 Verifier

### 12.1 分层验证

| 层次 | 必做检查 | 是否调用模型 |
|---|---|---|
| L0 发布完整性 | R02 清单完整、对象可读、版本/摘要/权限一致 | 否 |
| L1 格式和确定性验收 | 必需文件路径、非空、格式可解析、页/表/行等结构规则 | 否 |
| L2 任务语义与来源 | 对照结构化 rubric 验证结论、来源和关键要求 | 高风险/复杂任务可启用独立 Verifier |

不把所有普通聊天都送给第二个模型。简单问答保持低成本；文件任务至少执行 L0/L1；L2 由任务类型、组织策略和风险决定。

### 12.2 完成语义修正

- CompletionGate 仍检查结构，但验证 artifactRef 的实际存在和当前授权，不接受任意非空字符串作为证据。
- `acceptanceCriteria` 由文本列表逐步升级为带类型/参数的规则，保留旧字段读取兼容。
- `agent_result` 的自述摘要/hash 不是独立验收证据；验证记录注明 evidenceType、来源、验证器和版本。
- 普通回答“无法处理”“需要更多信息”可成功交付这条回答，但业务 taskOutcome 应为 `NEEDS_INPUT/REFUSED/INCOMPLETE`，不能等价为“产物任务已完成”。
- 建议增加 `executionOutcome` 与 `deliveryStatus`，区分执行目标是否满足和回答是否交付；不直接破坏现有 Run.status 客户端兼容。
- 文件声明必须绑定本 Run 实际产物或明确批准的复用产物，不能拿上传输入文件充当生成结果。

### 12.3 Verifier 权限与修复循环

Verifier 使用独立上下文、不同提示词，可配置不同组织模型，走 R01 治理。只读目标产物版本、规范化任务和证据，不得读取用户全部历史、写文件、执行 shell、修改记忆或自行提权。

输出结构化 `PASS/FAIL/NEEDS_INPUT`、规则结果和引用。失败最多触发配置上限的修复节点，重新执行 L0/L1/L2；默认建议最多 2 轮。独立提示并不代表模型结论可靠，必须保留可审计规则和真实证据。

### 12.4 验收

不存在的 artifactRef、空文件、损坏 Office 包、错误工作表/行数、输入文件冒充产物、自述“已完成”但无产物均不通过。Verifier 无写权限；断流/超时不能默认 PASS；受限修复不会无限循环；普通聊天不额外创建沙箱或 Verifier。

## 13. R10：生产交付和故障验证

### 13.1 交付物

- 应用多阶段 Dockerfile：构建前端与后端，运行非 root、固定 JDK、健康检查，不包含开发凭证。
- 本地完整 Compose：PG、Redis、MinIO、OpenSandbox 和应用；安全服务/Mem0 使用可选 profile，未配置时不阻断。
- 生产配置样例：Web/Worker 同进程和分离部署、企业域名同源 `/api`、流式代理超时、模型/对象存储/沙箱地址、资源配额。
- 首批使用 Compose/企业已有部署方式；确有 K8s 交付需求再提供 Helm/清单，不为了形式引入第二套运维平台。
- 离线镜像/依赖清单、版本锁定和 SBOM；运行启动不依赖公网拉包。
- 文档区分已有开关和本文拟新增开关；禁止把文档样例误当已支持参数。

### 13.2 配置守卫

校验恢复消费者、对象存储、容量能力、Provider、隔离模式和辅助预算关系。正式部署默认不创建/启用 demo 管理员和开放 demo 自注册；开发启动保留现有方便试用方式。历史 Flyway 不改写，新增前向迁移/配置驱动引导任务处理演示数据。

**JWT 相关检查、替换或轮换继续排除。** 生产门禁报告单列暂缓项，不能被“镜像启动成功”掩盖。

### 13.3 故障矩阵

| 故障点 | 要证明的结果 |
|---|---|
| 模型首片前失败、半截后断流、正常关闭但结果不完整 | 正确分类、无重复前缀、受限恢复、不错误成功 |
| 工具执行前/执行后、Journal 提交前/后杀进程 | 已提交结果重放、未知副作用阻断、不盲目重做 |
| Checkpoint 提交前/后杀进程 | 恢复最晚有效历史，不引用未提交工具事实 |
| MinIO 上传成功/PG 登记失败、PG 成功/响应丢失 | 幂等补登、可回收孤儿、不产生假产物 |
| Redis 不可用/清空、PG 短暂断开 | 不跨租户、不绕过租约；PG 不可用时明确停止领取/提交 |
| 双 Worker 抢占、旧 owner 在 lease 过期后回归 | 单有效 owner，旧 owner 不能写终态/文件清单 |
| OpenSandbox 创建/初始化/执行/释放失败 | 状态可见、资源最终对账、无长期泄漏 |
| 浏览器刷新/断网/重复提交/取消 | 幂等、事件游标可回放、文件和任务状态一致 |

### 13.4 备份恢复

PG 与 MinIO 用一致性清单/备份水位关联，不分别恢复后就宣布成功。恢复先 PG，再校验所有可见对象版本与摘要，缺失对象显式隔离；Redis 从持久事实重建；Mem0 从 ACTIVE 卡片重建；沙箱重新创建，不要求备份运行容器。

初始目标建议 RPO <= 15 分钟、RTO <= 60 分钟，需在明确数据量和硬件的演练中确认，不作为已经达到的指标。包括删除与保留策略，不能因历史备份恢复把用户已经删除的记忆重新生效。

## 14. R11-R17：后续功能的独立落地批次

### 14.1 R11：用户持久化定时任务

- Domain 新增 Schedule 聚合，保存 owner、时区、cron 表达式、下一触发时间、版本、启停、并发和 misfire 策略。
- DAL 新增 `schedules/schedule_occurrences`；按 dueAt 和状态部分索引领取，锁定后短事务创建 Run/Outbox。
- `occurrenceKey = scheduleId + scheduledInstant` 唯一，Worker 双实例也不创建重复 Run。
- 支持 `SKIP`、`FIRE_ONCE` 和有上限补跑；不允许故障恢复后无界补任务。
- 执行前重新检查用户状态、权限、模型、记忆和配额；只触发标准 Run，不直接调用 Harness。
- UI/API 提供创建、修改、暂停、删除、下次执行及历史；主动结果沿现有投递通道。
- 验收时区/DST、暂停并发、漏执行、进程重启、重复领取和用户禁用。

### 14.2 R12：MCP 授权与凭据治理

- Domain 定义 `CredentialProvider` Port、ConnectionGrant 聚合；Vault/KMS 客户端仅位于基础设施 Adapter。
- PG 只存 credentialRef、授权主体、scope、过期和版本，不存明文 token 到工作区/对话/普通日志。
- 支持管理员组织授权与用户委托授权；OAuth 实际支持程度由内部 MCP 服务协议决定，不伪造统一兼容。
- 连接状态 `PENDING_AUTH/ACTIVE/EXPIRED/REVOKED/ERROR`，刷新用 CAS/短租约防并发覆盖。
- 调用前最小权限检查，撤销后使新调用和新 Step 无效；在途无法撤销的外部副作用进入对账。
- 首批保留部署 Secret 兼容适配，显式标注能力，不在没有 Vault 的本地环境增加强依赖。
- 验收刷新、吊销、服务故障、跨租户隔离和凭据不出现在提示、文件、指标及日志。

### 14.3 R13：H03/H05/H06 剩余边界

- 复用 R01 的单次预算/路由绑定，补 Provider 序列化开销校准和大文件工具读取上限。
- 媒体按可信 Provider 估算器计量；未知图片/音视频路线继续明确拒绝，不退回低固定 token。
- 持久化完整 StepSnapshot 的模型、工具、技能、策略、环境和工作区版本引用；快照不存密钥。
- 跨重启能重建实际执行句柄，缺少旧版本时明确阻断/受控重新规划，不能只恢复版本字符串。
- 统一 ToolInvocationPlan 的路径、网络、stdin、资源、取消和能力要求，执行 Adapter 在副作用前校验。

### 14.4 R14：H09 远端进程会话

- Sandbox SPI 增加 start/poll/stdin/terminate/outputCursor，现有同步 exec 保持兼容但必须报告能力限制。
- 进程句柄关联 Run/Task/Attempt 与租约；输出有界、分段存储并支持轮询。
- 子任务取消优先终止该进程，不销毁父子共用沙箱；不支持精准终止时不得宣称子任务已完全停止。
- 全 Run 取消可终止所有进程并释放资源；未知终止结果由后台对账确认。
- 所有 Provider 经过契约测试；真实资源验证在 OpenSandbox 完成，其他 Provider 实测另行安排。

### 14.5 R15：H10/H11 上下文事实与工具发现

- 以版本化事实片段保留用户约束、任务计划、关键证据和未决工具配对，摘要不是唯一状态。
- 事实提取和压缩复用 R01/R05，删除与撤销在后续调用重验。
- 大规模 MCP/Skill 目录分页搜索并延迟加载，最终可见 schema 和可执行句柄来自同一 StepSnapshot。
- 检索工具名称/描述也应用租户 scope，不能在“搜索工具”阶段泄露无权能力。
- 验收连续多次压缩、超大 schema、工具撤销、目录更新和跨租户搜索。

### 14.6 R16：H12 用户纠偏事件

- 新增带 `expectedTurnId/expectedRunVersion/idempotencyKey` 的纠偏命令；旧轮次消息返回冲突而非串入新任务。
- 区分仅添加信息、修改计划、取消并重规划，权限和不可撤销副作用不能由纠偏覆盖。
- 事件持久化再投递，刷新与断线后恢复；终态后纠偏需明确创建新 Run。
- 前端显示纠偏已接收/等待安全边界/已生效，不直接把 HTTP 200 当执行完成。

### 14.7 R17：H13 完整工具调用提前执行

- 仅在 Provider 明确输出完整调用项、参数 schema 校验通过、权限批准、Journal 认领成功之后执行。
- 不执行半截 JSON、增量参数或仅名称已出现的调用。
- 断流后已提交副作用只重放结果，不重复执行；多调用依赖和审批仍受限。
- 必须在 R01/R04/R07/R13/R14 的边界完成后单独灰度，不与正确性修复一起开启。
- H14 Code Mode 仍为可选试点，不纳入本方案强制交付。

## 15. 数据模型、迁移与兼容策略

### 15.1 拟新增/扩展关系数据

| 数据 | 职责 | 核心约束/查询 |
|---|---|---|
| `model_invocations` 或现有计量账本扩展 | 辅助用途、routeVersion、调用幂等和用量 | `(org_id, invocation_id)` 唯一；不存请求密钥 |
| `artifact_publications/publication_entries` | 期望产物、对象/目录分阶段提交 | attempt + manifest 幂等；必需项全部提交才能 READY |
| `storage_reservations` | 上传前容量预留、到期回收 | org/user 配额短事务锁；有效 reservation 唯一 |
| `run_workspace_manifests/manifest_entries` | 输入/输出不可变版本和恢复范围 | run + manifestRevision 唯一；按 run/path 读取 |
| `runtime_messages/archive_cursors` | 运行消息增量提交和分页 | 稳定消息 ID 唯一、seq 单调、不读取全历史 |
| Run 字段与 `run_retry_requests/recovery_bindings` | retry lineage、命令幂等、历史工具结果继承 | 原 Run 不覆盖；预期版本与来源 operation 校验 |
| `tool_operation_resolutions` | 未知外部结果的追加式核实 | operation + expectedVersion CAS；完整审计 |
| `employee_profiles` | 企业岗位主数据 | org/user 唯一；管理员写、用户只读 |
| `memory_cards/memory_card_versions/memory_projections` | 个人记忆状态、版本与索引映射 | 有效卡片、source 去重、版本 CAS、删除墓碑 |
| `verification_results` | 独立校验规则与证据 | attempt + verifierVersion + manifestVersion |
| `worker_heartbeats` | 分离部署恢复消费者能力 | 部署 scope + Worker；过期只影响健康，不授予业务权限 |
| `schedules/schedule_occurrences` | 定时触发与执行历史 | schedule + scheduledInstant 唯一 |
| `connection_grants` | MCP 授权与 credentialRef | org/主体/连接 scope；吊销版本 CAS |

表名为逻辑设计候选。实施前先检查现有 Repository/表能否自然扩展，避免同义事实重复存储；实体身份、关联和高频查询字段独立列存储，不把所有数据放一个 JSON 字段。复杂消息内容/清单扩展可以 JSONB，但不能承担整个会话无限追加。

### 15.2 数据库强制要求

- 所有租户表包含 `org_id`；启用并 FORCE RLS，普通用户所有权再由应用用例校验。
- 复合外键包含组织边界，避免仅用裸 UUID 关联其他组织；外键列有适合查询的索引。
- pending/due/failed 队列建立与实际 WHERE 匹配的部分索引，后台领取使用 `SKIP LOCKED` 和版本/租约条件更新。
- 不在持有配额/游标/队列锁的事务中执行模型、MinIO 或沙箱网络调用。
- 时间统一 `timestamptz`，容量/seq/token 使用可承载增长的整数类型；输入严格校验和溢出保护。
- PostgreSQL 与 H2 同步新增迁移与契约测试；H2 通过不替代 PG 锁/RLS 验收。
- 当前最高迁移为 V33；实施时重新读取分支最高版本，按批次递增，不在设计阶段锁死迁移号。
- 不改已执行 Flyway 文件；大表回填有批次游标、暂停点和核验，索引创建按实际 PG 版本与迁移工具事务能力制定。

### 15.3 扩展、切换和回滚

采用 expand -> backfill -> verify -> switch -> retire。功能灰度按部署/组织控制，业务事实始终保留。

允许回滚到安全的同步/限制模式，不允许回滚到静默遗漏产物、无消费者假恢复、未知副作用盲目重放或已删除记忆继续召回。新状态尚不被旧应用识别时，应停用新写入并完成队列排空后回滚，不直接上线旧二进制解释新状态。

## 16. 实施批次与任务拆分

以下为建议顺序和工程估算，按一名熟悉项目的后端工程师计算；前端、运维可并行，真实企业服务可用性不计入人日。时间是计划预算，不是交付承诺。

| 批次 | 范围 | 依赖 | 预计工程量 | 独立交付门槛 |
|---|---|---|---:|---|
| B0 | 固化四个复现、配置组合、文件边界和误判成功测试 | 无 | 1-2 人日 | 测试先证明现状问题，原有回归保持 |
| B1 | R01 路由补丁；R02 完整报告/失败门禁；R03 一致限值；R04 能力开关；R05 稳定 ID 增量止血 | B0 | 5-8 人日 | 默认模型误路由、假归档成功、5 MiB 恢复和重复写入全部消除 |
| B2 | R02 发布账本；R03 流式对象/归档；R05 PG 运行账本；R06 工作区清单和资源引用 | B1 | 10-15 人日 | 大文件、有界内存、历史用户任务、父子共享与崩溃恢复通过 |
| B3 | R07 重试/对账 API、UI 与 Journal 继承 | B2 | 5-8 人日 | 无副作用盲重放，用户可完成安全重试闭环 |
| B4 | R08 岗位/卡片/治理/检索/前端；记忆后台化 | B1、R05；部分可与 B3 并行 | 10-15 人日 | 企业岗位只读，偏好确认/删除闭环，Mem0 可故障运行 |
| B5 | R09 L0/L1 规则与 L2 可选只读 Verifier | B2、B3；记忆可选 | 6-10 人日 | 真实产物校验、受限修复和无写权限 |
| B6 | R10 应用镜像、配置、双实例故障与备份恢复；完整前端门禁 | B2-B5 | 6-10 人日 | 测试报告、资源对账、恢复演练；暂缓风险单列 |
| B7 | R11 定时任务、R12 凭据治理 | B3、B6 | 各 6-10 人日 | 分别验收后独立启用 |
| B8 | R13-R17 运行时增强 | 按 14 节依赖 | 每项先做能力拆分再估算 | 单项报告，不用 DTO/单测代替接入和真实运行 |

### 16.1 B1 的具体执行顺序

1. `R01.1` 增加可信组织/模型绑定入口；辅助调用回归；不先做完整新计量表。
2. `R03.1` 创建通用传输策略和兼容构造器；消除恢复 4 MiB 硬编码。
3. `R02.1` 报告扫描完整性和所有拒绝项；Controller/Worker 共用成功门禁。
4. `R04.1` 配置组合与实际事件状态；无 Worker 时不发假后台恢复提示。
5. `R05.1` 保留原 Msg.id、只追加未提交消息；PG 完整账本放 B2。
6. `R01.2` 辅助用途预算、超时和计量；大记忆输入分块。
7. 完成既有全部 SaaS 回归、前端构建和 OpenSandbox 闭环，再进入 B2。

每个步骤独立提交代码和测试，合并前通过架构守卫。不能一次性重写文件、记忆、编排三个子系统后再补测试。

## 17. 验收矩阵与发布门禁

### 17.1 测试层次

| 层次 | 场景 | 依赖 |
|---|---|---|
| 单元/属性测试 | 不变量、容量边界、路由绑定、状态机、版本 CAS、规则校验 | 脚本模型/Mock |
| DAL 契约 | 幂等、事务回滚、索引访问、并发认领、RLS | H2 + 真实 PG |
| Adapter 契约 | 流式传输、取消能力、报告完整性、错误分类 | Provider 替身；OpenSandbox 真服务 |
| 故障集成 | 双实例、lease fencing、kill -9、MinIO/PG 断开、模型断流 | PG/Redis/MinIO/OpenSandbox Docker |
| 浏览器 E2E | 登录、模型切换、附件、审批、子任务、预览/下载、刷新、恢复/重试、记忆管理 | 本地/企业测试应用 |
| 生产演练 | 实际模型网关断流、容量、备份恢复、离线启动 | 企业测试资源，不自动宣称生产通过 |

### 17.2 必须覆盖的业务场景

1. 选择两个不同窗口模型，主调用与记忆/压缩/验证路由一致，大小窗口都不超限。
2. 上传 5 MiB/31 MiB 文件，父子 Agent 访问一致；重启后版本/摘要不变。
3. 默认 33 MiB 产物返回明确容量失败，改统一限值后端到端可交付。
4. 文件归档中 MinIO 故障、PG 登记失败、前端断线，不能错误成功或重复创建版本。
5. 长 Session 重复归档、十万历史、用户大文件域，不读全历史/全工作区。
6. 请求内断流转 Worker；Worker 未配置/暂不可用/入队失败，界面显示准确。
7. 非幂等工具结果未知阻断；人工核实后续跑不重复副作用；重复重试命令幂等。
8. 岗位由企业维护，用户只能读；个人偏好确认、更正、删除及 Mem0 不可用回退。
9. 文件未生成、文件损坏、验收规则不满足、Verifier 超时均不能宣称任务目标完成。
10. 双 Worker/进程强杀/全 Run 取消，任务和沙箱资源最终收敛，旧 owner 无法回写。
11. 网页预览常用文本、图片、PDF、DOCX、PPTX、XLSX；大文件/损坏/不支持格式有明确降级，至少覆盖桌面与移动视口。

### 17.3 规模与观测目标

以下为首轮实验目标，固定硬件、并发与数据分布后记录实测，不能写成已经满足的 SLA：

- 十万历史消息的窗口读取服务端 P95 <= 300 ms，默认每页不超过 100 条；无全历史请求。
- 任务仅引用 5 MiB 文件时，不因用户历史增长增加转移字节；初始化时间分解为资源创建/取文件/解包。
- 4 个并发文件任务时内存与 spool 不超配置，所有路径有 backpressure 和清理。
- 恢复领取时间 <= 有效 lease 到期 + 两个巡检周期，最终完成仍受模型服务时延影响。
- 故障后一个对账窗口内清除无有效引用的运行资源，对象 GC 遵循保留期。

指标至少包括：模型 purpose 调用/预算/失败；归档完整率/拒绝/补登；恢复队列等待/次数/成功率；初始化字节与文件数；消息去重与读取窗口；记忆候选/确认/删除投影延迟；验证失败/修复；资源引用/释放异常。

标签不得使用原始提示、文件名、token、用户 ID 或 runId 形成高基数指标；个案通过授权事件/Trace 关联。

### 17.4 复用的验证入口

```bash
mvn -o -pl agentscope-saas/agentscope-saas-app -am -Djacoco.skip=true test
mvn -pl agentscope-saas/agentscope-saas-app -am package -Pfrontend
./agentscope-saas/agentscope-saas-app/scripts/opensandbox-enterprise-gate.sh
```

离线 Maven 命令只适用于依赖已缓存的环境。真实 PG、多实例和故障用例需明确启用测试 profile/脚本，不能因默认跳过而计入通过。新增长历史/容量/故障测试入口随对应批次提交。

本轮审查已有证据为 9 个测试类、53 项定向测试通过和四项离线缺陷复现；不是上述未来验收完成的证据。

## 18. 文档和实施记录维护

1. 本文作为新一轮统一执行入口；18、19、24、35、36 保留各自专业细节和历史记录。
2. 修正 21 等能力矩阵中把“岗位基线存在”视为已交付的表述；18 当前明确待实施，应以代码和验收为准。
3. 34 中早期“重试空输入”“一次请求不重建沙箱”等描述与当前 H08 路径不同，实施 R04 时同步更新，以实际检查点和租约恢复为准。
4. 每个 R 工作项记录 `未开始/实施中/代码完成/本地验收/真实依赖验收/发布`，不把“代码完成”直接写成“全部完成”。
5. 每批记录 commit、迁移版本、配置变化、测试依赖、通过/失败/跳过数、故障证据和回滚边界。
6. 未要求提交/推送时只保存文档；后续按用户授权执行代码实施与版本发布。

## 19. 总体完成定义

本轮可靠性与企业任务闭环完成需同时满足：

- F01-F06 有回归测试，实际运行路径修复，不仅修改 DTO 或提示文案。
- 文件容量一致，归档完整、可恢复、用户可读；失败不会误标成功。
- 长历史和大用户文件域不导致全量初始化；父子 Agent 共用受租约管理的任务工作区。
- 辅助模型调用正确路由、受预算限制并可计量；记忆后台化不依赖活动沙箱。
- 岗位主数据只读，个人记忆确认/更正/删除和语义投影闭环真实可用。
- 用户安全重试、未知副作用对账与结果校验通过网页和真实依赖验收。
- 多实例/强杀/断流/对象存储故障和恢复演练有证据；DDD/MyBatis 架构守卫通过。
- JWT、企业身份体系、PromptGuard、CubeSandbox/E2B 实测等暂缓范围单独标注，不混入完成比例。

P2 各批次具有独立完成定义，不阻塞已通过门禁的 P1 功能交付；未实施的 H09-H13 仍如实保留待办，不以本方案设计完成替代代码交付。
