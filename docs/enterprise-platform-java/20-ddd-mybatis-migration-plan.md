# 20. DDD 分层与 MyBatis 数据访问重构方案

> 适用范围：`agentscope-saas`
>
> 目标：服务接口层、应用/领域层、数据访问层物理分离；关系数据库访问统一使用 MyBatis
>
> 状态：迁移和收尾已完成，后续由自动化架构测试持续防回退

## 1. 核心决策

本项目采用原生 MyBatis，不采用 MyBatis-Plus。

编排、租约、Outbox、资源治理和垃圾回收大量使用条件更新、CTE、行锁、
`SKIP LOCKED` 和批量领取。这些 SQL 的正确性依赖明确的数据库语义。原生 MyBatis
能够保留 SQL 的可审查性和并发语义，同时避免通用 CRUD API 渗入领域层。

最终依赖方向如下：

```text
Web / Worker / Job
        |
        v
应用服务 / 编排服务  ------------> 领域模型
        |                              ^
        |                              |
        +------ Repository Port -------+
                                       |
                           DAL Repository Adapter
                                       |
                           Tenant / Admin Mapper
                                       |
                                  PostgreSQL
```

应用层定义用例和事务边界，领域层定义业务模型与 Repository Port，DAL 使用 MyBatis
实现端口。Controller、Worker 和 Job 不感知 SQL、Mapper 或 Data Object。

## 2. 模块职责

| 模块 | 职责 | 依赖约束 |
|---|---|---|
| `agentscope-saas-domain` | 聚合、值对象、领域规则、Repository Port | 不依赖 Spring、MyBatis 和数据库 |
| `agentscope-saas-core` | 租户上下文、配额等共享领域能力 | 不包含持久化实体和仓储实现 |
| `agentscope-saas-orchestration` | Run、Task 等应用编排和状态机 | 通过领域端口访问数据 |
| `agentscope-saas-dal` | MyBatis Mapper、Data Object、TypeHandler、Repository Adapter | 唯一关系数据访问实现 |
| `agentscope-saas-storage` | MinIO 和关系库二进制存储适配 | 关系库回退实现委托 DAL Mapper |
| `agentscope-saas-sandbox` | 沙箱资源生命周期和计量 | 通过领域端口访问资源记录 |
| `agentscope-saas-app` | REST/SSE、认证入口、Worker、Job 和 Spring 装配 | 不直接执行关系数据库查询 |

## 3. 数据源与权限边界

系统保留 Tenant 和 Admin 两个 MyBatis 数据访问通道，但它们连接同一套 PostgreSQL
Schema，不是两个业务数据库，也不存在两份数据。

- Tenant 通道使用普通应用账号。连接获取时设置 `app.current_org`，由 PostgreSQL RLS
  强制执行组织隔离。普通用户请求和租户业务数据访问走该通道。
- Admin 通道使用受控管理账号，仅用于登录注册引导、跨租户调度、租约回收、全局对账
  和运营统计。管理 Mapper 与租户 Mapper 分包扫描，不能混用 SqlSession。

双通道的目的，是把租户隔离和跨租户运维的权限差异固化到连接与 Mapper 边界，而不是
依靠业务开发者在每条 SQL 中自行记忆权限规则。

## 4. 已完成的持久化覆盖

MyBatis 已覆盖全部 SaaS 关系数据和后台任务：

- 企业、用户、等级策略、租户治理和认证身份；
- Agent、会话、顺序消息、上下文摘要和交付状态；
- Run、Task、AgentRun、Attempt、Event、租约、预算、权限快照和 Outbox；
- 文件目录、不可变版本、附件、删除队列和 PostgreSQL BYTEA 回退存储；
- 记忆事件、记忆投影、重放和整合审计；
- Skills/MCP 市场配置、审计日志和使用量；
- 沙箱资源、运行租约、跨租户回收对账和 PostgreSQL 快照回退存储。

大文件和沙箱快照在线上优先使用 MinIO。PostgreSQL BYTEA 用于本地、测试或降级回退；
两种实现共享同一应用接口，不改变领域和应用层。

迁移完成后：

- JPA 实体、Spring Data Repository 和 Hibernate 依赖数量为 0；
- 生产业务代码中的直接 JDBC 数据访问数量为 0；
- 生产和测试代码均不使用 `JdbcTemplate`、`JdbcClient` 等 Spring JDBC 查询 API；
- 应用业务代码不直接依赖 DAL Mapper 或 Data Object；
- 集成测试使用测试作用域 `TestDatabaseMapper` 准备和校验数据，同样遵循 MyBatis 边界。

## 5. 强制架构规则

1. 领域模块不得依赖 Spring、JPA、MyBatis、Web 或数据库驱动。
2. Controller、Worker 和 Job 只能通过应用服务或领域 Repository Port 访问数据。
3. 生产 MyBatis Mapper 只存在于 DAL 模块，并返回 DAL Data Object。
4. Repository Adapter 负责 Data Object 与领域对象互转。
5. 事务边界位于应用服务；Mapper 不自行开启或提交事务。
6. 复杂并发 SQL 必须保留条件更新、锁、租约和幂等条件，不能改为“先查再写”。
7. Tenant Mapper 与 Admin Mapper 使用不同 `SqlSessionFactory`、`SqlSessionTemplate`
   和扫描包。
8. Admin Mapper 只允许用于明确的跨租户系统用例，不得进入普通租户请求。
9. PostgreSQL UUID 和 JSON 通过统一 TypeHandler 读写，不在 Mapper 中各自转换。
10. `TenantAwareDataSourceConfig` 设置 PostgreSQL RLS GUC 的原生 JDBC 调用属于连接
    基础设施；MyBatis TypeHandler 的 `java.sql` 使用属于驱动适配。除此之外禁止
    生产代码直接使用 `java.sql`。
11. Flyway 是数据库结构、索引和 RLS 策略的唯一事实源。

## 6. 自动化验收

`DataAccessArchitectureTest` 在每次构建中检查：

- 领域层保持框架无关；
- 生产 Mapper 只能位于 DAL 模块；
- 应用业务代码不能直接依赖 DAL；
- 所有源码不能使用 Spring JDBC 查询 API；
- 测试源码不能使用原生 `java.sql`，测试 Mapper 只能位于测试支持包；
- 生产代码不能引用 JPA、旧持久化包或未授权的原生 JDBC；
- POM 和运行配置不能重新引入 JPA/Hibernate；
- Tenant Task Mapper 必须绑定 Tenant SqlSession，不能绕过 RLS 使用 Admin 通道。

功能回归分两层：

- H2 PostgreSQL 模式执行全量测试，覆盖仓储、事务、任务状态机、Outbox、治理、文件、
  记忆、沙箱租约和端到端聊天编排；
- 真实 PostgreSQL 执行数据库契约与 RLS 集成测试，覆盖 UUID、JSONB、BYTEA、锁、
  条件领取、事务回滚、租户隔离和连接复用后的上下文恢复。

## 7. 完成定义

DDD 与 MyBatis 迁移完成必须同时满足：

- 所有关系数据库读写都通过 MyBatis；
- 所有业务持久化能力都有领域 Repository Port 和 DAL Adapter；
- 应用服务不直接依赖 Mapper、SQL 或数据库实现；
- Tenant 与 Admin 权限通道边界明确且可自动验证；
- JPA、Spring Data、Hibernate 和 Spring JDBC 查询 API 不再出现；
- H2 全量回归和真实 PostgreSQL/RLS 验收通过；
- 架构守卫能阻止上述约束被后续提交破坏。

上述条件已完成实现；本文件后续作为架构基线维护，不再作为迁移待办清单。
