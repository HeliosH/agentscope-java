# 01 · 总体方案与目标（Java）

## 1. 背景

QwenPaw 是一个能力完备的**个人 AI 助手**（Python 实现）：多 Agent、技能系统、自演进记忆、多渠道接入、工具安全沙箱、Coding 模式、定时任务。但它是**单用户、单机**架构：工具在宿主机用 `subprocess`/本地 FS/本地 Chromium 执行，配置/记忆落本地磁盘，单口令认证。

本方案目标：基于 **agentscope-java** 框架，把 QwenPaw 重建为**企业私有化、多用户**的个人助手平台 —— 员工使用企业内网提供的云端资源（算力、模型、存储），平台负责统一管理、隔离与治理。

> **边界**：面向**企业私有化部署**，**不含计费/订阅**。用量计量只服务于**配额、公平调度与审计**。全栈**可自部署、可离线，数据不出内网**。

## 2. 核心判断：重建于成熟框架积木之上

agentscope-java 已内置 SaaS 化所需的全部基础设施（已逐项核实于代码）。因此 Java 方案不是"从零造平台"，而是**用框架积木重建 QwenPaw 的产品能力**：

- **执行隔离**：框架 `Sandbox`/`SandboxManager` 体系 + `IsolationScope.USER`，**无需自建 ExecutionBackend**。
- **文件持久化**：框架 `RemoteFilesystem` + `BaseStore`（CAS 写）两层读，**无需 JuiceFS/FUSE**。
- **多租户**：`RuntimeContext` + `Middleware` + `NamespaceFactory` 全链路传播。
- **多副本分布式**：`RedisSandboxExecutionGuard`/`RedisStore`/`RedisSession` 现成。

**与 Python 方案的根本差异**：Python 方案保留 QwenPaw 代码、微创插缝；Java 方案在框架上**重写** QwenPaw 行为。前者快、保功能、风险小；后者地基干净、长期可维护、但等于完整重写。

## 3. 平台目标

### 3.1 产品目标
- **企业内开箱即用**：员工 SSO 登录即用，无需本地装 Java/浏览器/模型。
- **统一管控**：管理员管理成员、配额、内网模型接入、审计、安全策略。
- **对齐 QwenPaw 体验**：多渠道、技能、演进记忆、Coding 模式、定时任务能力对齐（Java 重建）。

### 3.2 技术目标
| 维度 | 目标 |
|------|------|
| 执行隔离 | 每用户独立沙箱（KVM microVM / 容器），硬件级隔离 |
| 数据隔离 | 按组织/用户分区，RLS 行级 + 命名空间隔离 |
| 弹性 | 沙箱按需创建、空闲 pause、长期回收（框架 sweeper） |
| 离线 | 全栈无外网可运行 |
| 安全合规 | 密钥加密托管、最小权限、全审计、数据不出内网 |
| 资源可控 | token/沙箱/存储计量用于**配额与公平**（非计费） |
| 可观测 | OTel 全链路 Trace + Micrometer 指标 |

### 3.3 非目标
- **不做计费/订阅/支付**。
- 不修改 agentscope-java core/harness（仅新增 `agentscope-saas` 模块树）。
- 不自研模型推理（对接内网 vLLM/Ollama/企业模型）。

## 4. 用户画像与场景
与 Python 方案一致（管理员/部门负责人/员工/开发者）。典型：员工在飞书让助手汇总邮件；研发在 Coding 模式让助手在内网沙箱改代码跑测试；管理员看各部门 token/沙箱用量并调配额。详见 [../enterprise-platform/01-overview.md](../enterprise-platform/01-overview.md) §4。

## 5. QwenPaw → agentscope-java 能力映射

| QwenPaw (Python) | agentscope-java 等价物 | 性质 |
|------------------|------------------------|------|
| `QwenPawAgent`(ReActAgent 子类) | `HarnessAgent` + 自定义 Middleware | **Java 重写**（行为移植） |
| `execute_shell_command` / `read_file` / `grep_search` | harness `ShellExecuteTool` / `FilesystemTool`（沙箱内） | **框架自带**（省自建） |
| `browser_use` | 新建 `BrowserTool`（Java Playwright，沙箱内） | **Java 重写** |
| `ToolGuardMixin` | core `PermissionEngine` + `PermissionRule` | 框架自带（规则重配） |
| `MultiAgentManager` | Spring Bean + AgentRegistry | 部分复用 + 重写 |
| `MemoryManager` / 自演进记忆 | core `LongTermMemory` + `Memory` | 底座复用，**演进逻辑 Java 重写** |
| `CronManager`(apscheduler) | scheduler 扩展（Quartz/XXL-Job） | 框架自带 |
| `ExecutionBackend` / `SandboxBroker`（Python 自建） | harness `Sandbox`/`SandboxManager`/`SandboxStateStore` | **框架自带（最大省力）** |
| Workspace 文件 | `RemoteFilesystem`+`BaseStore`+`NamespaceFactory`（无 FUSE） | 框架自带 |
| 17 个 channel | Channel SPI（新建）+ 逐个适配器 | **Java 全部重写** |
| 技能市场/插件 | Skill 仓库（Git/MySQL/Nacos…）部分支持 + 自建审核 | **大部分 Java 重写** |
| Coding 模式（Web IDE/LSP） | 无等价物 | **Java 重写或后置** |
| FastAPI / fastapi-users | Spring Boot WebFlux / Spring Security+OAuth2 | 框架替换 |
| Config(~1000 行 Pydantic) | `@ConfigurationProperties` + DB | 部分复用 |

> **净省力**：沙箱执行、文件持久化、权限、中间件、AG-UI、调度、多副本锁、可观测。
> **净重写**：QwenPaw 的全部"产品功能"（渠道/技能/记忆演进/Coding/浏览器）。

## 6. 产品形态
- **Web 控制台（员工）**：基于 AG-UI（框架 starter 现成 SSE），重建 QwenPaw 前端体验，SSO 登录。
- **管理后台（管理员）**：扩展 `admin-starter`，新增租户/沙箱/用量/渠道/审计管理。
- **渠道入口**：钉钉/飞书/企微等（Channel SPI 重建）。
- **开放能力**：内网 OpenAPI + A2A 协议（Agent 间协作）+ MCP 工具。

> 系统架构详见 [02-architecture.md](./02-architecture.md)。
