# SaaS DDD 与 MyBatis 数据访问架构

## 1. 目标

SaaS 服务采用领域驱动的依赖方向，并将 MyBatis 作为唯一的关系数据库访问中间件：

```text
HTTP / 定时任务 / Agent 运行时
              |
              v
       应用服务（用例编排）
              |
              v
    领域模型 + Repository 端口
              ^
              |
      DAL MyBatis Repository 适配器
              |
              v
   Tenant / Admin MyBatis Mapper
              |
              v
           PostgreSQL
```

领域层不依赖 Spring、MyBatis、JPA 或数据库类型。应用层只依赖领域端口，不感知 SQL 和数据访问实现。DAL 层负责实现领域仓储端口、SQL 映射及数据库行对象转换。

## 2. 模块职责

| 模块 | 职责 | 依赖约束 |
| --- | --- | --- |
| `agentscope-saas-domain` | 领域实体、值对象、Repository 端口 | 不依赖框架和基础设施 |
| `agentscope-saas-core` | 租户上下文、配额等通用领域能力 | 不包含持久化实体和仓储实现 |
| `agentscope-saas-orchestration` | Run、Task 等应用编排 | 通过领域端口访问数据 |
| `agentscope-saas-dal` | MyBatis Mapper 与 Repository 适配器 | 唯一关系数据访问实现 |
| `agentscope-saas-storage` | MinIO 或关系库二进制存储适配 | 关系库回退实现委托 DAL Mapper |
| `agentscope-saas-sandbox` | 沙箱资源生命周期和计量 | 通过领域端口访问资源记录 |
| `agentscope-saas-app` | Web 接口、应用服务和依赖装配 | 不直接编写关系数据库访问代码 |

## 3. 数据源边界

系统保留两个逻辑数据访问通道，但不是两套业务数据库：

- `Tenant MyBatis` 使用普通 `app` 账号。连接获取时写入 `app.current_org`，由 PostgreSQL RLS 强制执行组织隔离。用户请求、工作区、会话、消息、文件、记忆、市场配置、使用量和沙箱生命周期都走该通道。
- `Admin MyBatis` 使用具备跨租户能力的管理账号，仅用于登录注册引导、系统调度、租约回收、全局对账和运营统计。管理 Mapper 与租户 Mapper 分包扫描，不能混用 SqlSession。

这两个通道共享同一套 Flyway 管理的 PostgreSQL Schema。区分数据源的目的是权限边界，而不是拆分数据或维护两个真相来源。

## 4. 数据访问规则

1. 应用服务依赖 `domain.repository` 中的接口。
2. DAL Repository 适配器实现领域接口，并调用 MyBatis Mapper。
3. 所有关系 SQL 位于 `agentscope-saas-dal` 的 Mapper 中。
4. 事务由租户或管理 `DataSourceTransactionManager` 管理；应用用例负责声明事务边界。
5. JPA、Spring Data Repository、`JdbcTemplate` 和业务原生 JDBC 均不允许使用。
6. `java.sql` 仅允许出现在 MyBatis TypeHandler 和设置租户 RLS 上下文的连接基础设施中。
7. Flyway 是唯一 Schema 演进机制，运行配置不包含 Hibernate/JPA 建表或校验参数。
8. 可配置 SQL 表名必须先通过标识符白名单校验，再交给 MyBatis 进行标识符替换。

## 5. 持久化覆盖范围

MyBatis 已覆盖以下关系数据：

- 企业、用户、等级策略和租户治理；
- Agent、会话、顺序消息和上下文摘要；
- Run、Task、事件、租约、预算、权限快照和 Outbox；
- 文件目录、不可变版本、附件、回收队列和 PostgreSQL BYTEA 回退存储；
- 记忆事件、记忆投影和整合审计；
- Skills/MCP 市场配置；
- 审计日志和使用量；
- 沙箱资源生命周期、跨租户回收对账和 PostgreSQL 快照回退存储。

大文件和沙箱快照在线上仍优先使用 MinIO。PostgreSQL BYTEA 是本地、测试或降级回退方案，两种实现共享同一应用接口。

## 6. 防回退约束

`DataAccessArchitectureTest` 持续检查：

- 领域层不引用 Spring、MyBatis 或 JPA；
- MyBatis Mapper 只能位于 DAL 模块；
- 生产代码不引用旧持久化包或 Spring Data JPA；
- 生产和测试代码均不使用 `JdbcTemplate`、`JdbcClient` 等 Spring JDBC 查询 API；
- 测试代码不使用原生 `java.sql`，测试 Mapper 只能位于测试支持包；
- POM 和运行配置不包含 JPA/Hibernate 基础设施；
- 原生 `java.sql` 只能用于 MyBatis TypeHandler 和租户连接设置；
- 租户 Task Mapper 必须绑定租户 SqlSession，不能使用管理通道。

集成测试通过测试作用域的 `TestDatabaseMapper` 准备和断言数据库状态。该 Mapper 不参与
生产扫描，也不进入应用业务代码；其目的在于让数据库契约测试本身遵循 MyBatis 数据访问
边界，避免测试代码重新形成第二套 JDBC 实现。

数据库契约测试分别在 H2 PostgreSQL 模式和真实 PostgreSQL 上验证仓储 CRUD、生成主键、JSON/BYTEA 映射、事务、RLS 隔离及端到端聊天编排。

## 7. 完成标准

满足以下条件即视为迁移完成：

- 生产代码无 JPA/Spring Data 持久化实现；
- 所有关系数据库读写均通过 MyBatis；
- 领域模型和 Repository 端口位于领域模块；
- 应用服务不直接依赖 Mapper、SQL 或数据库实现；
- 租户数据与管理任务使用明确隔离的 MyBatis Session；
- H2 全量测试与真实 PostgreSQL/RLS 集成测试通过；
- 架构测试能够阻止上述约束被后续变更破坏。
- 编排沙箱租约遵循同一边界：`SandboxLeaseRepository` 位于领域层，租约状态转换由
  sandbox 应用服务负责，SQL 与 Data Object 仅存在于 DAL 的 tenant MyBatis 适配器中。
