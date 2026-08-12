# QwenPaw 与企业智能助手后端能力复核及补齐方案

## 1. 复核结论

《QwenPaw 与 Comac-agent 后端能力对比报告》使用的是较早的 Comac-agent 基线。按当前仓库重新核对后，报告中的差异不能直接等同于当前缺陷：任务规划与拆解、持久化任务、子 Agent、长会话压缩、长期记忆、Skills、MCP、沙箱、审批、审计、用量统计等能力已经存在。

本次不按单机个人助手的目录和类名照搬 QwenPaw，而是提取对企业平台有普适价值的机制，并适配到现有 AgentScope Runtime、DDD 应用分层、MyBatis DAL、多租户和部署期配置体系中。

## 2. 逐项能力矩阵

| 报告差异 | 当前能力复核 | 结论与处理 |
| --- | --- | --- |
| 防死循环与自动完成门禁 | 已有最大迭代数、Run/Task 预算、超时、`CompletionGate` 和结果校验事件 | 本次增加调用级重复工具循环门禁；独立 LLM Verifier 保留为高风险任务的可选增强，不替代确定性门禁 |
| 工作模式 | 已有 Plan Mode，以及 DEFAULT、EXPLORE、ACCEPT_EDITS、DONT_ASK、BYPASS 权限模式 | 已有企业等价能力，不复制个人助手 UI 模式 |
| 多 Agent 协作 | 已有子 Agent、持久化 DAG、依赖、并发、lease、heartbeat、retry、replan | 已覆盖，能力强于报告基线 |
| 外部 Agent ACP | 当前以 MCP、Skills、子 Agent 和内部网关为扩展边界 | 暂不引入第二套外部协议；出现明确 ACP 互操作对象时以独立 Adapter 接入 |
| 长对话 Scrollback | PG 消息按 seq 持久化、游标分页、上下文窗口、参数截断和摘要压缩 | 已覆盖 |
| 长期记忆与主动交互 | PG 事实账本、Mem0 语义记忆、岗位基线、MEMORY.md/沙箱快照、主动消息通道均已存在 | 记忆已覆盖；用户级 Cron 触发器仍需单独产品化，列入 P1 |
| 内置工具、浏览器、Web、代码执行 | 文件工具、Shell、沙箱执行、MCP 动态工具已存在 | 企业内网能力应由内部 MCP/Skill 集市供给，不内置依赖公网的搜索和浏览器服务 |
| 工具超时、取消和后台任务 | ToolExecutor 超时/并发/重试、流取消、持久化后台 Task 已存在 | 已覆盖 |
| Skills 生命周期与市场 | 动态 Skill、企业市场、草稿、扫描、审批、审计、版本和自演进已存在 | 已覆盖 |
| Driver 与 MCP 抽象 | 沙箱 Provider/Deployment Adapter 隔离 Docker、OpenSandbox、E2B、CubeSandbox；外部工具统一 MCP | 已有企业等价抽象 |
| 多模型、重试、限流、切换 | 原有模型级超时和指数退避，但只有单路模型配置 | 本次补齐并发、QPM、429 协同冷却、熔断和部署期有序主备切换 |
| OAuth、Secrets、审批 | 有 OIDC/JWT、MCP 组织配置、工具 HITL 审批 | 外部工具逐连接 OAuth 和 Vault/KMS 凭据托管仍为 P1；不能把明文密钥放入用户工作区 |
| 安全扫描与审计 | 已有 Skill 静态扫描、权限规则、上传安全、租户审计 | 本次增加所有 ToolBase/MCP/反射工具统一参数风险扫描 |
| Cron、Heartbeat、主动触发 | 后台任务和沙箱已有 heartbeat，Gateway 支持主动投递 | 用户级持久化 Cron CRUD、错过执行策略和执行历史仍为 P1 |
| 插件系统 | Skills + MCP + Provider Adapter 已构成三层扩展机制 | 不再增加概念重叠的通用插件系统；部署扩展使用 Spring Bean/Adapter |
| 备份、用量、运维 | 用量、配额、指标、审计、文件版本、沙箱快照均已存在 | 全租户灾备编排、恢复演练和导出包仍为 P1 运维能力 |
| Session 命令 | Web API 已提供会话、消息、任务、文件和管理操作 | CLI 斜杠命令属于客户端交互形态，不作为后端缺口 |

## 3. 本次实现

### 3.1 模型运行韧性

新增通用 `ResilientModel`，每个部署配置的模型路由独立维护：

- 最大并发请求数；
- 60 秒滑动窗口 QPM；
- 获取容量超时；
- HTTP 429 协同冷却；
- 连续瞬时故障熔断；
- 主模型在首个流式片段前失败时按顺序切换备用模型；
- 已产生部分流式输出后禁止切换，避免重复和响应污染。

底层模型原有的单请求超时与指数退避继续生效。两层职责不同：底层处理一次模型调用的瞬时重试，`ResilientModel` 处理跨请求流量治理和跨模型路由。

### 3.2 工具参数安全

新增统一参数守卫，并接入 `PermissionEngine` 和 ReAct 轻量权限路径，覆盖所有 `ToolBase` 工具，包括动态 MCP 工具和反射工具：

- Shell 命令替换、管道下载执行、反向连接等规避模式直接 DENY；
- 破坏性命令、敏感路径、疑似凭据外发进入 ASK/HITL；
- 企业显式 DENY 规则优先于风险 ASK；
- 参数值不写入异常信息，审计仅保留风险类型、工具名和输入指纹。

### 3.3 重复调用循环门禁

新增 call-scoped `ToolLoopGuardMiddleware`：

- 对工具名和规范化参数计算 SHA-256 指纹；
- 在短滑动窗口内统计等价调用；
- 默认第 4 次重复调用终止当前 Agent Run；
- 不记录原始参数，避免凭据或业务数据进入日志；
- 状态只在本次调用内存在，不会在不同用户或不同请求之间串扰。

该门禁与已有 `maxIters`、Run/Task token/调用次数/时长预算和 `CompletionGate` 共同构成停止判定链。

## 4. 部署配置

模型流量治理默认开启，测试使用 `stub`/`scripted` 时不包装。生产环境的备用模型在部署配置中声明，用户侧无感知：

```yaml
saas:
  model:
    type: gateway
    base-url: http://model-gateway-primary/v1
    api-key: ${MODEL_PRIMARY_API_KEY}
    name: qwen-enterprise
    fallbacks:
      - type: gateway
        base-url: http://model-gateway-secondary/v1
        api-key: ${MODEL_SECONDARY_API_KEY}
        name: qwen-enterprise-backup
    traffic:
      enabled: true
      max-concurrent: 8
      max-queries-per-minute: 120
      acquire-timeout-seconds: 30
      rate-limit-cooldown-seconds: 5
      circuit-failure-threshold: 3
      circuit-open-seconds: 30
  agent:
    loop-guard:
      enabled: true
      repeat-threshold: 4
      window-size: 8
```

模型 API Key 应由部署环境 Secret 注入。备用模型的切换是环境级配置，不提供给终端用户选择。

## 5. 后续优先级

### P1：企业持久化主动任务

在现有 DDD/MyBatis 体系内增加 Schedule 聚合、Repository Port、MyBatis Adapter、租户 API 和调度执行历史。生产使用企业统一调度平台或数据库 lease，必须具备幂等键、misfire 策略、并发策略、配额、停用、审计和主动消息投递。不能使用单 JVM 内存定时器承载生产任务。

### P1：外部连接凭据治理

定义 `CredentialProvider` Port，对接企业 Vault/KMS；数据库仅保存凭据引用和授权元数据。MCP 连接增加组织管理员授权、用户委托授权、scope、过期刷新、吊销和审计。密钥不得进入对话、工具参数、工作区文件或普通日志。

### P1：灾备和恢复演练

对 PG、MinIO、Redis 恢复边界、Mem0 可重建数据和沙箱快照建立统一清单；实现按租户导出、恢复校验、RPO/RTO 指标和定期恢复演练。Redis 只保存可重建热状态，不作为唯一事实源。

### P2：可选独立验证 Agent

仅对高风险或高价值任务启用异模型/异 Prompt 验证，输出结构化 rubric 和证据引用。Verifier 只能阻止完成或触发 replan，不能直接修改执行产物；普通任务继续使用低成本确定性门禁。

## 6. 验收标准

- 主模型 429、5xx、连接或容量超时时，在未输出内容的前提下切换备用模型；
- 主模型已输出部分内容后失败，不得拼接备用模型响应；
- 不同模型的并发、QPM、冷却和熔断状态互不影响；
- Shell 规避模式无法经 BYPASS 或已确认状态绕过；
- 破坏性但可能合理的命令进入现有确认流程；
- 重复工具调用达到阈值后停止，异常和日志不包含原始参数；
- `stub`、`scripted` 和未配置备用模型的现有本地流程保持兼容；
- 所有新增能力由部署配置控制，终端用户无须理解模型路由和运行时 Provider。
