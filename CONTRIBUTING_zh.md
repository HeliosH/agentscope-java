[English Contributing Guide](CONTRIBUTING.md)

# 参与刍狗项目贡献

感谢参与刍狗项目。刍狗是基于 AgentScope Java 构建的企业私有化智能助手平台。

本仓库同时包含 AgentScope Java 基础框架和刍狗企业平台。所有变更都应保持通用框架能力与产品特定逻辑之间的边界。

## 开始之前

1. 在 [agentscope-saas-harness Issues](https://github.com/HeliosH/agentscope-saas-harness/issues) 中检查是否已有相关任务。
2. 大型功能、数据库结构重构、公共 API 不兼容变更或运行时架构调整，应先创建 Issue 讨论。
3. 每次变更只解决一个明确问题，不要混入无关重构、生成文件或大范围格式调整。
4. 禁止提交密码、访问令牌、企业内部模型地址、客户数据和本地环境配置文件。

## 开发环境

基础要求：

- JDK 17 或更高版本
- Maven 3.9 或更高版本
- 前端开发使用 Node.js 20 和 npm 10
- 中间件及沙箱集成验证使用 Docker Desktop 或 Docker Engine

推荐通过 OpenSandbox 本地脚本启动：

```bash
./agentscope-saas/agentscope-saas-app/scripts/start-opensandbox-local.sh
```

停止应用及本地依赖：

```bash
./agentscope-saas/agentscope-saas-app/scripts/stop-opensandbox-local.sh
```

架构、部署和配置说明见 [README.md](README.md)。

## 模块边界

代码应放入其职责所属的模块：

| 模块 | 职责 |
| --- | --- |
| `agentscope-core`、`agentscope-extensions` | 可复用的 AgentScope 框架及集成能力 |
| `agentscope-harness` | 通用 Agent 规划与执行运行时 |
| `agentscope-saas-domain` | 不依赖基础设施的领域模型和仓储接口 |
| `agentscope-saas-core` | 企业应用服务与用例 |
| `agentscope-saas-orchestration` | 任务规划、恢复、上下文和多 Agent 编排 |
| `agentscope-saas-dal` | MyBatis 持久化实现和数据库映射 |
| `agentscope-saas-storage` | 对象存储与文件持久化 |
| `agentscope-saas-sandbox` | 与供应商无关的沙箱生命周期及适配器 |
| `agentscope-saas-app` | Spring Boot 装配、HTTP API、配置和 React 前端 |

必须遵守以下架构约束：

- Controller 只负责协议转换和权限入口，业务逻辑放在应用服务或领域服务中。
- 领域模块不得依赖 Spring、MyBatis、对象存储 SDK 或具体沙箱供应商。
- 领域边界使用仓储接口，DAL 统一使用 MyBatis，不得在应用服务中新增直接 JDBC 操作。
- 沙箱运行逻辑必须与供应商无关。沙箱类型由部署环境配置，不向终端用户开放切换。
- 所有查询、缓存键、对象键、任务和沙箱工作区必须保持租户及用户隔离。
- 同一任务中的父 Agent 和子 Agent 默认共享任务沙箱；如需额外隔离，必须明确记录原因。
- PostgreSQL 是业务事实来源，Redis 保存临时运行状态，MinIO/S3 保存文件内容，Mem0 仅作为可选记忆投影。

## 变更要求

### 后端

- 为变更行为添加测试；涉及运行时的变更还应覆盖失败和恢复路径。
- 保持响应式调用链非阻塞，不得在请求线程或事件循环中执行阻塞操作。
- 任务执行、重试、事件投递和产物发布必须保持幂等。
- 新增环境变量或功能开关时，同步更新配置示例和说明。

### 数据库

- 通过新增 Flyway 迁移演进数据库，禁止修改可能已在其他环境执行的迁移文件。
- 在同时支持 PostgreSQL 与 H2 的范围内保持两套开发结构行为一致。
- 租户数据查询必须包含租户条件，并为主要访问模式配置合理索引。
- 不得把文件内容、完整长会话或无限增长的事件历史存入单个数据库字段。

### 前端

- 遵循现有刍狗视觉规范和组件模式。
- 完整处理加载、空数据、错误、无权限和长内容状态。
- 不向终端用户暴露具体沙箱供应商的配置控件。
- API 契约变化时同步更新前端类型和用户文档。

### 安全与外部集成

- LlamaFirewall 等外部安全服务必须保持可选，并遵循已有本地降级和熔断恢复策略。
- 在信任边界校验文件路径、工具参数、外部访问目标和租户所有权。
- 日志、审计详情和测试数据中必须脱敏凭证及敏感请求数据。
- 单元测试应模拟模型、MCP、存储和沙箱服务；连接真实服务的测试必须显式启用。

## 验证要求

开发过程中先运行与改动直接相关的测试，提交前执行该变更对应的完整门禁。

检查 Java 格式：

```bash
mvn spotless:check
```

运行企业平台测试：

```bash
mvn -pl agentscope-saas/agentscope-saas-app -am test
```

修改 AgentScope 公共模块或 Harness 时运行全仓测试：

```bash
mvn test
```

验证前端：

```bash
cd agentscope-saas/agentscope-saas-app/frontend
npm ci
npm run lint
npm run build
```

修改运行时、沙箱、文件或产物链路时执行 OpenSandbox 端到端门禁：

```bash
./agentscope-saas/agentscope-saas-app/scripts/start-opensandbox-local.sh --smoke
```

如果外部依赖导致必要测试无法执行，应在 PR 中准确说明未执行的测试及原因。

## 文档要求

功能、配置、架构或运维方式发生变化时，文档必须与代码在同一变更中更新：

- 用户可感知的能力或启动方式变化时更新 [README.md](README.md)。
- 架构和运行决策更新到 `docs/enterprise-platform-java/`。
- 示例不得包含真实凭证或仅适用于某个内部环境的地址。
- 新集成需要说明默认值、降级行为、发布方式和回滚方式。

## 提交信息

使用 [Conventional Commits](https://www.conventionalcommits.org/)：

```text
<type>(<scope>): <subject>
```

常用类型包括 `feat`、`fix`、`docs`、`refactor`、`test`、`perf`、`ci` 和 `chore`。

示例：

```text
feat(models): add tenant model availability policy
fix(sandbox): preserve workspace ownership for subagents
docs(readme): document private deployment topology
refactor(dal): move task persistence to MyBatis mapper
```

## Pull Request 要求

PR 应说明：

- 问题背景和预期结果
- 架构影响及关键取舍
- 数据库、配置、兼容性和安全影响
- 已执行测试及结果
- 可见 UI 变化的截图
- 对生产运行有影响时的发布及回滚说明

发起评审前，应确认差异中只包含预期文件、没有误提交生成产物、文档已经同步，并且适用的检查全部通过。

## AgentScope 上游边界

对 AgentScope 通用模块的修改应具备普适性并保持向后兼容。租户策略、产品 UI、企业持久化和部署特定行为应保留在 `agentscope-saas` 中，除非该能力确实属于通用框架。

AgentScope 框架本身的使用和上游贡献规范请参考 [AgentScope Java 项目](https://github.com/agentscope-ai/agentscope-java)。
