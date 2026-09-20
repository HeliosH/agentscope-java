# Codex Harness 升级实施记录

本记录对应 [源码对比与升级方案](35-codex-harness-source-comparison-and-upgrade-plan.md)。截至 2026-09-20，整体升级仍在进行；不能将本地测试通过视为 SaaS 生产 Provider 和多租户端到端验收完成。

## 已实施范围

| 工作项 | 当前实现 | 尚待完成 |
|---|---|---|
| H01 | 本地 shell 同时排空 stdout/stderr，固定容量首尾缓冲；完成型命令关闭 stdin；轮询超时、取消并清理已发现的子进程 | 生产 Provider 的进程生命周期及终止确认归 H09；本地子进程追踪不保证捕获任意瞬间退出/脱离父进程的进程 |
| H02 | ShellExecutionRequest；本地 ProcessBuilder 原生 cwd；规范化相对路径并拒绝根外符号链接；Overlay 转发；旧 Provider 安全引用 cwd | 旧 Provider 回退不等于原生路径约束，远端符号链接与能力投射待 H06 |
| H03（部分） | WorkspaceContext 完整节预算；必需内容超限拒绝；压缩异常边界修正；模型级估算 SPI；媒体递归计数及未知媒体拒绝；官方 GPT-4.1-mini 路由图片上界适配；故障转移候选最大估算；ModelCatalog 最终准入检查 | 其他生产媒体路由及音视频适配、压缩与最终请求共用一次路由绑定、Provider 序列化开销校准、大文件读取上限 |
| H04（第一批） | 显式 ToolRetrySafety；可重试工具异常保留到重试层；同步调用延迟至订阅；反射工具支持；永久错误不重试；H07 已将业务 operationId 与未知结果对账接入持久化运行路径 | 更完整的副作用/资源/cancel traits |
| H05（部分） | ReAct 每步复制工具注册元数据及本地权限；最终 Schema 校验；StepSnapshot 与 SaaS 身份关联；最终模型路由及目录版本；沙箱环境身份/工作区版本；并发租户与热更新针对性验证；H07 已持久化工具结果后的上下文检查点 | 完整 StepSnapshot 持久化、跨重启模型/工具/环境版本重建及完整压力验证 |
| H06（部分） | 已确认工具恢复时重新执行安全、外部策略、本地 deny 与工具自身拒绝；模型请求在途发生权限版本变化时整步工具调用失效 | 结构化 ToolInvocationPlan、网络/stdin/资源约束和 Provider 能力投射仍待接入 |
| H07 | 已交付 ToolExecutionJournal 与 ContextCheckpointStore 核心 Port、PostgreSQL/H2 持久化适配、租约 fencing、重复提交幂等、未知结果对账、安全工具跨 attempt 接管，以及 ReAct 工具结果后的检查点提交 | H08 已使用这些记录重建通用 Chat/Worker 流；检查点与工具终态之间采用可恢复的顺序提交，不宣称分布式原子事务 |
| H08 | Chat 与 Worker 共用模型故障分类/退避策略；直接流持有心跳租约；超限或进程失联后转入持久化 Worker；恢复时装载最新 ContextCheckpoint、原始提示、附件路径和模型选择；前端呈现后台恢复状态；V33 保存恢复阶段、次数、时间和检查点 | 已完成代码与本地 H2 验收；生产模型中断、PostgreSQL 多实例抢占及进程强杀恢复仍需发布前故障演练 |
| H09–H13 | 尚未交付 | 按原方案依赖推进，不以 DTO 或单测代替运行路径接入 |
| H14 | 可选试点，未开始 | 主干稳定后评估 |

H01–H04 第一批针对性验证记录：**14 个测试类、124 项测试，失败/错误/跳过均为 0**（核心 81 项、Harness 43 项；组合回归与预算修复后的针对性复验）。未进行全仓库测试、生产模型调用和 SaaS 多租户故障恢复验收。

## 自动重试迁移

`AgentTool.getRetrySafety()`、`ToolBase.Builder.retrySafety(...)`、`@Tool(retrySafety=...)` 新增默认值 `NEVER`，保持现有工具源码兼容。**已有 `maxAttempts > 1` 配置不再单独授权工具重试。** 确认工具契约后显式声明：

- `READ_ONLY`：重复执行无可观察副作用。
- `IDEMPOTENT`：实现保证同一工具调用 ID 的重复尝试幂等；外部服务需要幂等键时，必须由适配实现透传/持久化。
- `NEVER`：未知、非幂等、有无法确认的副作用，或不希望自动重试。

`readOnly` 权限提示、`concurrencySafe` 并发声明与重试契约分别管理，远端 MCP 的只读提示不会自动授权重试。重试保留原 `ToolUseBlock.id`；相同参数的新调用保持独立，不进行参数去重。这还不是跨 Worker/跨重启的持久化幂等保证。

允许重试的工具默认仅对现有 `ExecutionConfig.RETRYABLE_ERRORS` 所识别的瞬时错误重试。安全/参数异常、取消/中断、工具挂起、HTTP 4xx（429 除外）不能由宽泛自定义谓词覆盖。返回普通错误结果不会触发重试；只有异常信号触发。

对显式声明可重试的反射工具，`callAsync` 的异常会保留为响应式错误信号，最终由 Toolkit 转为工具错误结果。默认工具保留原有反射错误结果行为；直接调用可重试工具 `callAsync` 的使用方需要处理异常信号。

## 验证记录

2026-09-09：核心工具相关 6 个测试类共 81 项通过（0 失败、0 错误）：ToolRetrySafetyTest 10、ToolExecutorTest 9、ToolBaseTest 14、ReflectiveFunctionToolTest 10、ToolMethodInvokerTest 37、ToolkitToolBaseIntegrationTest 1。

新增验证覆盖副作用执行后异常不重放、瞬时异常重新调用、同步抛错、永久错误拒绝、调用 ID 稳定与独立调用区分、MCP 不隐式信任、反射工具真正经过重试执行。

本地执行测试包括真实进程大量输出、超时、取消、线程中断、stdin EOF、UTF-8；cwd 测试包括空格/引号/分号、旧 Provider 引用、越界路径与符号链接。完整 Harness 脚本模型集成已通过：只为指定命令配置允许规则，经过实际 Agent 推理/权限/反射工具/本地进程，再从下一次模型请求中抽取 ToolResultBlock，核验 stdout/stderr 末尾、特殊 cwd、截断标记和无意外文件。模型为脚本替身，shell/文件系统为真实执行；不代表远端模型或 SaaS HTTP 端到端已验收。

测试使用独立 InMemoryAgentStateStore，防止默认文件会话恢复历史测试中的拒绝记录。当前命令安全正则会将 shell 算术展开归入命令替换，集成夹具使用普通 awk 循环生成大量输出；该策略精度问题留待 H06，不通过关闭安全规则绕过。

本轮本地审查进一步发现并修复：合并双路输出后统一首尾截断会丢失 stdout 末尾，现在按两路分配预算；压缩中间件的通用异常处理原本包围下游模型执行，会导致模型异常后二次调用，并吞掉预算超限，现在兜底仅覆盖压缩本身，回退路径也必须验算预算。

CodeRabbit CLI 未安装，未运行远端 CodeRabbit 审查；本轮执行了本地源码审查。若后续需要该服务，可从 [CodeRabbit 官方入口](https://www.coderabbit.ai/cli) 安装并认证。

复现核心回归：

```bash
mvn -o -q -pl agentscope-core -am spotless:apply test \
  -Dtest=ToolRetrySafetyTest,ToolExecutorTest,ToolBaseTest,ReflectiveFunctionToolTest,ToolMethodInvokerTest,ToolkitToolBaseIntegrationTest \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false \
  -Djacoco.destFile=target/codex-harness-upgrade.exec \
  -Djacoco.dataFile=target/codex-harness-upgrade.exec
```

原工作区历史 `target/jacoco.exec` 无法读取，因此本次使用独立覆盖率文件；没有删除历史产物。受限沙箱可能阻止 ProcessHandle 子进程检查和 Mockito JVM attach，必须在允许这些操作的测试环境运行集成测试。

## 后续实施顺序

当前发布门槛：H03 默认拒绝未配置估算器的媒体输入，属于兼容性变化；生产媒体路由适配和回归完成前，不能将当前工作树作为通用多模态升级发布。已有局部测试证明指定路径的行为，不构成 H01–H13 整体完成的证据。

1. 完成 H03 媒体和最终请求预算边界，补齐 H04 traits。
2. H05 将工具、策略、模型和环境版本固定到每步快照，确保重试与审批引用一致执行句柄。
3. H06 继续收敛统一策略；H08–H09 在 H07 的 Journal/Checkpoint 上接入恢复协调和生产 Provider 进程会话。
4. H10–H13 上下文保留、工具发现、纠偏事件和安全的提前执行；按原方案开展故障注入与多租户端到端验收。

最终 Harness 组合回归使用 `-pl agentscope-harness -am`，测试类为：

```text
HarnessShellExecutionIntegrationTest,ToolRetrySafetyTest,ToolExecutorTest,ToolBaseTest,
ReflectiveFunctionToolTest,ToolMethodInvokerTest,ToolkitToolBaseIntegrationTest,
LocalProcessRunnerTest,ShellExecuteToolTest,WorkspaceContextBudgetTest,
WorkspaceContextMiddlewarePathBoundsTest,CompactionMiddlewareContextWindowTest,
ProjectAwareOverlayTest,LocalFilesystemUserIsolationExampleTest
```

预算兜底调整后再次运行 CompactionMiddlewareContextWindowTest（3 项）、WorkspaceContextBudgetTest（3 项）、HarnessShellExecutionIntegrationTest（1 项），全部通过。源码格式由 Spotless 校验，`git diff --check` 通过。


## H05 每步执行边界（2026-09-09，进行中）

- `ReActAgent` 在构建每轮 Schema 前固定本步 Toolkit，随后权限判断和工具执行使用同一份注册句柄；源 Toolkit 上的替换进入下一轮。
- `onModelCall` 之后再核对最终可见 Schema。未绑定或参数 Schema 被替换时拒绝请求；被隐藏的工具从本步执行集合移除。扩展中间件需要在 reasoning 前通过 RuntimeToolScope 安装可执行工具，不能只在最后阶段添加展示 Schema。
- `StepSnapshot` 包含独立 stepId、调用内 sequence、可选 run/agentRun/task/attempt、模型名称/选择 ID/输入预算、工具注册版本、Schema/消息/选项/本地策略哈希、扩展集哈希与工具名。
- 注册版本不只取决于 Schema：替换实现或更新预置参数会产生新版本。注册表复制保留原版本，并复制预置参数元数据，源更新不会替换本步参数映射。工具对象本身仍由应用实现管理，不承诺深克隆任意对象内部状态。
- 本地策略快照包含审批后记住的规则。外部安全策略仍需逐调用检查，不能把本地 policyHash 宣称为外部系统策略版本。
- 正常 reasoning 和 max-iteration summary 的模型开始事件携带快照；旧构造器和无快照旧事件可继续读取。实际模型调用延迟到开始事件通过下游治理之后。
- SaaS Governance 在进入 Agent 前填充编排身份。现有运行级首个 capability snapshot 仍保留；这批改动没有建立持久化每步日志，也没有提供跨重启工具版本恢复。

**H05 尚未整体验收。** 模型路由本体和环境版本已固定到本步，审批挂起后的安全撤销及 H07 Journal/Checkpoint 提交边界已经接入；仍需跨重启重建具体模型、工具和环境执行句柄，以及完成多租户并发/热更新压力验证。H03 的其余媒体路由估算仍未实施，不因本批进展改写原方案依赖和验收要求。


### H05 本批验证结果

核心、Harness、SaaS app 组合回归及并发复验汇总：**188 项，180 通过、0 失败、0 错误、8 跳过**。统计包含 JUnit 嵌套测试类；与上面的 124 项存在重叠，不相加充当独立测试数。

新增证据覆盖：

- 模型响应期间替换同名工具，本轮执行旧句柄，下一轮执行新句柄；Schema 相同但注册版本不同。
- 最终模型中间件隐藏的工具不能在本轮执行，源 Toolkit 保持可供下一轮选择。
- 同一个 Agent 的 Alice/Bob 请求用屏障同时进入模型阶段，同名工具及相同模型 callId 下仍使用独立 StepSnapshot 和扩展集哈希。
- 源注册表更新预置租户参数后，已复制的注册元数据保持原参数和版本。
- 新模型开始事件的快照 JSON 往返，旧事件无快照时仍可读取；规范化哈希不受 Map 插入顺序影响。
- 在 `onModelCall` 的开始事件处拒绝后，模型调用计数为零；SaaS preflight 拒绝时不进入模型执行函数。
- SaaS 编排身份在 Agent 执行前可见；完整 Harness shell 和预算/压缩回归继续通过。

这次故障注入还定位到外层 `onAgent` 的转发事件不构成模型执行的同步审批边界。因此 SaaS 逐次 preflight 和 ModelCallEnd 用量结算已移入 `onModelCall` 链，整体 deadline 仍由 `onAgent` 管理。

8 项跳过来自已有测试：AgentEventStreamTest.StreamOrdering 的 7 个禁用空占位方法，以及未开启 `agentscope.runStructuredOutputRaceTest` 的 25,000 次重复压力用例。它们不是通过证据；未修改占位测试来增加通过数。

复现本批组合回归：

```bash
mvn -o -q -pl agentscope-saas/agentscope-saas-app -am spotless:apply test \
  -Dtest=StepSnapshotTest,ReActAgentNewLoopE2ETest,ToolRegistryTest,PermissionEngineTest,ToolkitTest,ToolGroupManagerTest,ReActAgentStructuredOutputTest,AgentEventStreamTest,OrchestrationGovernanceMiddlewareTest,HarnessShellExecutionIntegrationTest,HarnessAgentTest,ToolRetrySafetyTest,CompactionMiddlewareContextWindowTest \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false \
  -Djacoco.destFile=target/codex-harness-upgrade.exec \
  -Djacoco.dataFile=target/codex-harness-upgrade.exec
```

并发用例加入后复验核心的 ReActAgentNewLoopE2ETest、StepSnapshotTest、ToolRegistryTest，均通过。所有测试为本地脚本模型/Mock 或真实本地 shell；没有声称真实远端模型、SaaS HTTP、多租户数据库崩溃恢复已经完成。


## H05 最终模型路由绑定（2026-09-09）

新增 `StepBindableModel` 可选 SPI，普通 Model 不需要修改。ReAct 在最终 `onModelCall` 参数确定后绑定路由，再捕获 StepSnapshot 和调用 Provider；正常推理与 max-iteration summary 均使用该边界。

ModelCatalog 的绑定句柄保存当时的不可变 Route 与目录版本。后续目录 refresh、默认模型调整或再次选择消息不会替换这个句柄里的 Provider、上下文窗口和输出上限。下一步重新绑定时才读取新目录。Snapshot 增加可选 `modelRouteVersion`，避免仅凭相同 modelId/modelName 判断运行配置未变化；版本不包含凭据。

仍需区分：这个绑定发生在**最终请求边界**。压缩/工作区预算预估早于该边界，若期间刷新为更小窗口，最终绑定后的 ModelCatalog 预算检查会明确拒绝超限输入；把压缩估算也固定到同一路由的完整预算链，仍属于 H03 后续工作。路由版本目前是进程内目录版本，尚未建立可供跨重启重建的持久化配置版本。

路由绑定批次验证：ModelCatalogTest、ModelManagementServiceTest、ReActAgentNewLoopE2ETest、StepSnapshotTest、OrchestrationGovernanceMiddlewareTest、HarnessShellExecutionIntegrationTest 共 **19 项通过，0 失败/错误/跳过**。测试覆盖绑定后组织目录刷新、下一绑定使用新 Provider、旧绑定保留原窗口和输出限额，以及 ReAct 最终绑定与快照一致。


## H03 模型级输入估算契约（2026-09-09，继续实施中）

新增核心 `InputTokenEstimator` / `InputTokenAwareModel`。ModelCatalog.Route 可通过新增第四个参数携带完整输入估算器；旧三参构造器保留，默认使用通用文本估算。目录规范化、热刷新后的绑定句柄保留对应估算器，压缩阶段和最终请求准入都可调用同一接口。

TokenCounterUtil 增加受信媒体估算回调，递归计入工具结果中的媒体；ThinkingBlock 文本也计数。Schema 使用真实 JSON 序列化估算；工具参数的 raw/input 取较大估算，避免重复计数，同时避免不完整 raw 参数掩盖更大的已解析参数。累计加法饱和到 Integer.MAX_VALUE，避免溢出为负数绕过预算。

CompactionMiddleware 为自己的压缩过程绑定一次模型路由，固定该次压缩所用的窗口和估算器；ConversationCompactor 的触发、保留窗口和参数截断均使用模型估算接口。最终模型请求仍单独绑定并复核预算。

**兼容性与未完成工作：** 未配置媒体成本时不再返回 5 token，而是明确抛出 ContextWindowExceededException；这是实际行为变化。生产模型的具体媒体估算适配及配置装配尚未齐备，H03 不能标为完成。已有媒体业务迁移前必须为对应 Route 提供可信估算器，或待后续生产适配完成。当前仅支持在 Route 编程配置中注入；不能把测试的 1500/600/5000 token 夹具值当成真实 Provider 的计费公式或上界。

本批主要验证：未知媒体明确失败、嵌套媒体被计入、Thinking 文本计数、累计溢出防护、不同参数表示不漏计、绑定路由保留估算器、媒体准入拒绝发生在 Provider 调用前。仍需补齐真实 Provider、多媒体大小/时长信息及最终完整预算链，继续保留原计划验收范围。

H03 本批组合测试与针对性复验合计 **27 项通过，0 失败/错误/跳过**（与其他批次重叠，不累计为独立总数）。测试类：TokenCounterUtilTest、ModelCatalogTest、ReActAgentNewLoopE2ETest、StepSnapshotTest、CompactionMiddlewareContextWindowTest、WorkspaceContextBudgetTest、HarnessShellExecutionIntegrationTest。最后一次复验针对参数表示及压缩媒体边界，命令采用 `-pl agentscope-harness -am`，独立 JaCoCo 路径保持前述配置。

## H03 生产路由估算装配与 HTTP 集成验证（2026-09-09）

新增 `EstimatedInputModel`，由 `ModelRouteFactory` 装配到实际模型对象。`ModelCatalog.Route` 的兼容三参构造器现在优先使用模型提供的估算能力，不再丢失 Provider 估算器。治理开启时，对实际构建的所有故障转移候选取最大估算；任何候选的媒体成本未知，则在模型调用前拒绝，防止切换到另一个计数规则后超限。

首个自动适配范围严格限定为官方 HTTPS API 端点的 `gpt-4.1-mini` / `gpt-4.1-mini-2025-04-14`。依据本次实际读取的 [OpenAI 图片输入规则](https://developers.openai.com/api/docs/guides/images-vision)，使用 6,144 patch 预算、1.62 倍率，向上取整并增加 1 token 舍入余量，即每图 9,955 token 的保守准入预留。这是容量预留，不是小图的实际计费值；文本及 Schema 仍使用现有启发式估算。没有下载用户图片，也没有信任 URL 中声明的尺寸。

自定义网关、未知模型别名、音视频不自动继承这一公式。调用选项覆盖模型或切换到其他端点时，既有图片估算失效，在发送前拒绝该媒体请求；纯文本覆盖保持原行为。未来若增加可改变图片 detail 的格式化配置，必须同步复核估算契约。其他 Provider 和媒体仍需可信适配器，H03 整体继续保持未完成。

本批 **9 个测试类、35 项测试通过，0 失败/错误/跳过**，与历史批次有重叠，不累计为独立总数。新增 EstimatedInputModelTest 6 项覆盖：生产工厂治理开关下的估算传递；网关与名称边界；运行参数覆盖；故障转移最大成本与真实切换；未知后备成本；真实格式化器和 HTTP 客户端接入本地 MockWebServer。

HTTP 集成用例注入本地传输端点并显式套用生产估算器：单图收到模拟完成响应，核验请求路径、模型名与 image_url；双图超限后服务端请求计数仍为 1。该证据覆盖模型适配传输链，不代表已经调用官方远端模型或完成 SaaS HTTP/数据库/Worker 崩溃恢复端到端验收。

复现命令：

```bash
mvn -o -q -pl agentscope-saas/agentscope-saas-app -am spotless:apply test \
  -Dtest=EstimatedInputModelTest,ModelCatalogTest,ModelManagementServiceTest,ReActAgentNewLoopE2ETest,StepSnapshotTest,OrchestrationGovernanceMiddlewareTest,HarnessShellExecutionIntegrationTest,TokenCounterUtilTest,CompactionMiddlewareContextWindowTest \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false \
  -Djacoco.destFile=target/codex-harness-upgrade.exec \
  -Djacoco.dataFile=target/codex-harness-upgrade.exec
```

首次构建因新增测试使用通配符 import 被 Spotless 拒绝；改为显式 import 后完整重跑上述命令成功。最终 Surefire XML 逐类核验得到上述计数，`git diff --check` 通过。没有执行真实 API 付费调用。

## H05/H06 环境版本与审批撤销边界（2026-09-10）

`ExecutionEnvironmentSnapshot` 由 Sandbox 生命周期在成功启动和工作区恢复后写入调用级 RuntimeContext，包含 sandbox session 标识、Provider 类型、恢复工作区版本和规范化版本哈希；借用父 Sandbox 时同样绑定。`StepSnapshot` 现保存 `environmentId` / `environmentVersion`，并在 Sandbox 释放时清除调用级绑定。旧的 StepSnapshot Java 构造方式和缺少新字段的 JSON 仍可读取。

审批恢复增加两道拒绝边界。已经进入 `ALLOWED` 状态的工具仍重新检查输入安全、外部安全策略、本地 deny 规则及工具自身的 DENY，确认结果只满足 ASK，不能覆盖明确拒绝。模型请求等待输出期间若 PermissionContext 发生变化，该旧 Step 的全部工具调用以 DENIED 结束；下一模型 Step 才按新权限重新解析，防止热更新期间保留已撤销能力。

本批组合回归共 **41 项通过，0 失败/错误/跳过**。其中 ReActAgentHitlTest 9 项覆盖审批挂起后增加 deny 规则和工具自身撤销；ReActAgentNewLoopE2ETest 8 项包含模型流被屏障挂起、并发更新权限、释放模型流后工具零调用并产生 DENIED 结果；SandboxLifecycleMiddlewareTest 7 项包含恢复版本绑定及释放清理；同时复验 PermissionEngine 14 项、StepSnapshot 2 项和真实 Harness shell 1 项。

复现命令：

```bash
mvn -o -q -pl agentscope-harness -am spotless:apply test \
  -Dtest=ReActAgentHitlTest,PermissionEngineTest,ReActAgentNewLoopE2ETest,StepSnapshotTest,SandboxLifecycleMiddlewareTest,HarnessShellExecutionIntegrationTest \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false \
  -Djacoco.destFile=target/codex-harness-upgrade.exec \
  -Djacoco.dataFile=target/codex-harness-upgrade.exec
```

这仍不是完整 H06：Provider 网络策略、stdin、资源范围、审批引用和统一 ToolInvocationPlan 尚未收敛为单一执行入口。环境版本是当前调用的可审计标识，尚不能据此跨重启重建已退役的容器或模型配置。

## H07 工具日志与上下文检查点（2026-09-10，已交付）

核心层新增 `ToolExecutionJournal`、`ContextCheckpointStore` 和 `ExecutionLeaseSnapshot` Port。`ToolExecutor` 在实际调用前认领业务 operation，在收到终态后提交结果；相同 operation 的已提交结果直接重放，不再次执行工具。`ReActAgent` 只在持久化工具终态已写入上下文后保存检查点，记录规范化 history hash、最近 20 条消息、未决 operation、事实版本和工作区版本。兼容的 RuntimeContext 合并现在保留 session/user/agent state、自定义属性、步骤身份和租约，避免旧 `ToolExecutionContext` 分支静默丢失治理数据。

SaaS 新增 PostgreSQL/H2 V31 `tool_operations` 与 V32 `context_checkpoints`，并补齐 Domain Repository、MyBatis 适配、事务服务、Outbox 事件和 Agent 装配。PostgreSQL 表启用并强制 RLS，外键有索引，检查点以 `(org_id, run_id, agent_run_id, history_revision)` 唯一约束保证单历史单调 revision。写入前锁定并校验 `run_attempts` 的 owner、状态和过期时间；直接流通过已有 `direct:<runId>` attempt 幂等键进入相同校验路径。

故障语义如下：

- 同一 invocation 重复提交相同终态返回 `ALREADY_COMMITTED`，不产生第二条 Outbox；不同终态仍拒绝。
- lease 过期后的旧 worker 不能提交成功结果。服务先把 operation 固化为 `OUTCOME_UNKNOWN` 并写 Outbox，再在事务外抛出 `ToolLeaseLostException`，避免异常回滚对账状态。
- `READ_ONLY` / `IDEMPOTENT` operation 可由有效 replacement attempt 接管 `CLAIMED`、可安全失败或未知结果；`NEVER` 的未知结果明确失败，禁止盲目重放。
- operation 的业务身份由 task、agent run、tool call、工具名和输入摘要构成，不含 attempt，因此跨 attempt 恢复仍指向同一副作用边界。
- 工具终态和 ContextCheckpoint 是有序的两个本地事务：先提交工具终态，再保存引用它的历史。两者之间崩溃时，新进程从 Journal 重放终态并补建检查点；不会生成引用未提交工具结果的检查点。这里不宣称跨表分布式原子提交。

验收覆盖计划要求的四类故障：已提交结果重放、重复终态提交、失效 lease fencing、未知结果对账；另覆盖并发认领、安全失败重试、replacement attempt 接管、检查点 revision 单调与过期 lease 拒绝。ReAct 端到端用例经过真实工具循环证明，工具结果进入合并历史后才创建检查点，且旧上下文合并路径不会丢失 Journal/Checkpoint 绑定。

H2 跨模块回归共 **74 项：73 通过、0 失败、0 错误、1 跳过**。跳过项是 `PgTaskRepositoryIntegrationTest` 中需要外部 PostgreSQL 开关的既有用例；另行连接本机 PostgreSQL 17.5 执行 ReAct、Durable Journal/Checkpoint 和 Governance **20 项全部通过**。数据库侧确认 Flyway 已到 V32，`tool_operations` 与 `context_checkpoints` 均为强制 RLS，`app` 角色具备所需 DML 权限；故障注入后检查点仅有 revision 1、2，失效 lease 没有写入 revision 3，工具状态同时存在 `SUCCEEDED` 与 `OUTCOME_UNKNOWN`。

H2 跨模块回归复现命令：

```bash
mvn -o -q -pl agentscope-saas/agentscope-saas-app -am spotless:apply test \
  -Dtest=ToolExecutionJournalTest,ToolExecutorTest,ToolRetrySafetyTest,ReActAgentNewLoopE2ETest,ReActAgentHitlTest,DurableToolExecutionJournalIntegrationTest,OrchestrationGovernanceMiddlewareTest,HarnessDurableTaskExecutorTest,PgTaskRepositoryIntegrationTest,WorkspaceCheckpointRestoreServiceTest,WorkspaceArtifactServiceTest \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false \
  -Djacoco.destFile=target/codex-harness-upgrade.exec \
  -Djacoco.dataFile=target/codex-harness-upgrade.exec
```

H07 的边界到持久化执行事实为止。自动定位中断流、装载最新检查点、重建 Agent 调用并统一 Chat/Worker 重启退避由 H08 接续；生产 Provider 的远端进程会话属于 H09。

## H08 通用流恢复（2026-09-20，代码与本地验收完成）

新增 `RunRecoveryCoordinator`，Controller 请求内恢复和 `DurableTaskWorker` 后台恢复使用同一模型异常分类、最大尝试次数与指数退避配置。普通工具、权限、校验和业务异常不进入模型恢复路径。请求内恢复继续使用同一用户消息 ID；`ReActAgent` 先读取最新 `ContextCheckpointStore`，按消息 ID 去重并恢复保留尾部、摘要和历史 revision，防止重新追加用户输入或盲目重放已提交工具结果。

直接聊天创建 Run 时把实际 Agent 提示（含上传文件路径）及模型 ID 写入根任务输入。HTTP 执行期间 `DirectRunLeaseTracker` 激活并刷新根 attempt 租约；连续模型失败时结束当前 attempt 并把根任务标记为 `READY/SCHEDULED`。进程消失则由既有租约恢复任务发现过期 attempt，采用相同路径转入 Worker。Worker 以稳定的 `task-<taskId>` 消息 ID 重建调用，恢复成功后按 run 幂等保存最终 assistant 消息。

PostgreSQL/H2 V33 为 `task_nodes` 增加恢复阶段、原因、次数、下次恢复时间和检查点引用。PostgreSQL 只为待恢复队列和非空外键建立部分索引。前端识别 `run_recovery_scheduled`，停止当前流的等待动画、保留 run 检查入口，并明确提示任务已转入后台恢复。

本批定向单元/数据库测试共 **24 项通过，0 失败/错误/跳过**：恢复分类 4、Worker 3、租约状态机 8、后台执行器 6、Run 创建 3。另执行前端生产构建成功；`SaasAppContextLoadsTest` 从空 H2 数据库完整应用 32 个迁移并到达 V33，应用上下文成功启动。初次测试受到工作区历史 `target/jacoco.exec` 损坏影响，改用独立 JaCoCo 文件后通过，未删除历史产物。

当前验收仍有明确边界：本地测试覆盖租约过期后的状态机恢复，但尚未在生产模型 Provider、PostgreSQL 多实例和真实服务进程强杀条件下完成故障注入；这些属于上线门槛，不影响继续实施 H09。

复现命令：

```bash
mvn -o -q -pl agentscope-saas/agentscope-saas-app -am test \
  -Dtest=RunRecoveryCoordinatorTest,DurableTaskWorkerTest,DurableTaskLeaseServiceTest,HarnessDurableTaskExecutorTest,RunOrchestrationServiceTest \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false \
  -Djacoco.destFile=target/h08-recovery.exec -Djacoco.dataFile=target/h08-recovery.exec

mvn -o -q -pl agentscope-saas/agentscope-saas-app -am test \
  -Dtest=SaasAppContextLoadsTest -DfailIfNoTests=false \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Djacoco.destFile=target/h08-context.exec -Djacoco.dataFile=target/h08-context.exec

cd agentscope-saas/agentscope-saas-app/frontend && npm run build
```
