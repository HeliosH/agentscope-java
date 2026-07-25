# 20. DDD 分层与 MyBatis 数据访问重构方案

> 适用范围：`agentscope-saas`
>
> 目标：服务接口层、应用/领域层、数据访问层物理分离；业务数据库访问统一收敛到 MyBatis
>
> 状态：基础分层已实施，认证、任务查询、任务租约、Outbox、治理和记忆投影已完成垂直迁移

## 1. 核心决策

本项目采用原生 MyBatis，不采用 MyBatis-Plus。

原因是编排、租约、Outbox、资源治理和垃圾回收大量使用条件更新、CTE、行锁、
`SKIP LOCKED` 和批量领取。此类 SQL 的正确性依赖明确的数据库语义，通用 CRUD
封装不能降低复杂度，反而容易把数据库模型和 ORM API 暴露到领域层。

重构不是把 `JdbcTemplate` 替换成 Mapper 后保留原目录结构，而是同时建立以下依赖方向：

```text
server/app  ───────> application/orchestration ───────> domain
     │                                                   ▲
     └──────────────── composition root                  │
                                                         │
dal/mybatis ───────── implements repository ports ───────┘
```

## 2. 模块职责

| 模块 | 职责 | 允许依赖 |
|---|---|---|
| `agentscope-saas-domain` | 聚合、值对象、领域规则、Repository Port | JDK |
| `agentscope-saas-orchestration` | 用例编排、事务边界、状态机 | domain、必要的运行框架 |
| `agentscope-saas-dal` | MyBatis Mapper、Data Object、TypeHandler、Repository Adapter | domain、MyBatis |
| `agentscope-saas-app` | REST/SSE、认证入口、任务 Worker、Spring 装配 | application、domain、dal |
| `agentscope-saas-storage` | MinIO/对象存储基础设施适配 | 存储接口与客户端 |

`agentscope-saas-core` 当前仍包含 JPA 实体和 Spring Data Repository，是迁移期模块。
所有新业务不得继续向该目录增加持久化实体或 Repository。

## 3. 强制规则

1. 领域模块不得依赖 Spring、JPA、MyBatis、Web 或数据库驱动。
2. Controller、Worker 和 Job 不得直接注入 Mapper、`DataSource`、`JdbcTemplate` 或
   `EntityManager`。
3. MyBatis Mapper 只存在于 DAL 模块，并返回 DAL Data Object。
4. Repository Adapter 负责 Data Object 与领域对象互转。
5. 事务边界位于应用服务；Mapper 不开启和提交事务。
6. 复杂并发 SQL 必须保留条件更新、锁和幂等条件，不允许降级为“先查再写”。
7. 租户 Mapper 与管理 Mapper 使用不同 `SqlSessionFactory` 和不同扫描包。
8. 管理 Mapper 只允许用于登录引导、跨租户运维和调度，不得进入普通请求用例。
9. PostgreSQL UUID 通过统一 `UuidTypeHandler` 读写；禁止各 Mapper 自行转字符串。
10. `TenantAwareDataSourceConfig` 中设置 PostgreSQL RLS GUC 的 JDBC 调用属于连接基础设施，
    不属于业务 DAL，不迁移为 Mapper。

## 4. 当前落地

当前已建立：

- 纯领域模块 `agentscope-saas-domain`；
- 独立数据访问模块 `agentscope-saas-dal`；
- 租户数据源与管理数据源各自独立的 MyBatis `SqlSessionFactory`、`SqlSessionTemplate`
  和 Mapper 扫描边界；
- PostgreSQL/H2 通用 UUID TypeHandler；
- `AuthIdentityRepository` 领域端口及 MyBatis 实现；
- `DurableTaskRepository` 领域端口及 MyBatis 实现；
- `DurableTaskLeaseRepository` 领域端口及 MyBatis 状态机实现；
- `OrchestrationOutboxRepository` 领域端口及 MyBatis 租约实现；
- `OrchestrationGovernanceRepository` 领域端口及 MyBatis 悲观锁实现；
- `MemoryProjectionRepository` 领域端口及 MyBatis 条件领取实现；
- `SandboxReconciliationRepository` 领域端口及 MyBatis 条件领取实现；
- `FileObjectGcRepository` 领域端口及 MyBatis 删除队列实现；
- 登录、注册、持久化子任务查询、交付状态和租约领取不再直接使用 JDBC；
- Outbox 发布、预算治理、权限快照和记忆重放不再直接使用 JDBC；
- 记忆投影领取会重新校验状态、重试次数和 stale 时间，防止多 worker 重复投影；
- 沙箱释放和文件 GC 在调用外部资源前执行条件领取，防止多 worker 重复删除；
- 文件元数据清理与对象删除队列写入处于同一管理事务，物理对象删除在事务外重试；
- 租户 Mapper 使用非超级用户数据源并经过 RLS GUC 包装；管理 Mapper 只使用显式
  管理数据源；
- PostgreSQL 集成测试覆盖租户切换后的拒绝与恢复，防止 MyBatis 解包数据源代理后绕过
  RLS；
- 迁移期保留默认 JPA 事务管理器，避免改变未迁移用例的事务语义。

当前应用层直接使用 `JdbcTemplate` 的业务类数量为 0。

`agentscope-saas-core` 中仍有 20 个 Spring Data JPA Repository。基础设施配置中为连接池、
RLS GUC 和旧存储 adapter 装配而使用的 `DataSource` 不计入上述业务类数量；其中仍依赖
JPA Repository 的存储 adapter 须在后续批次迁入 DAL。

## 5. 后续迁移批次

### P0：编排控制面

迁移以下能力到 `domain + orchestration + dal`：

- `DurableTaskLeaseService`（已完成）
- `OrchestrationGovernanceService`（已完成）
- `OrchestrationOutboxPublisher`（已完成）
- `RunOrchestrationService` 使用的 6 个 JPA Repository

验收要求：

- lease 领取仍使用数据库锁和 `SKIP LOCKED`；
- 任务状态转换全部为条件更新；
- Outbox 领取、续租、发布、失败重试语义不变；
- H2 功能测试和 PostgreSQL 并发集成测试通过。

### P1：会话、文件与记忆

迁移会话窗口、消息序列、文件目录、附件、记忆账本和重放作业。

验收要求：

- 长会话仍按 `session_id + seq` 游标分页；
- 文件二进制仍在 MinIO/对象存储，PG 只保存元数据和引用；
- 记忆事实与 Mem0 投影职责不变；
- 文件 GC 的领取、重试和幂等删除保持事务一致性（Job 与删除队列已完成，文件业务
  Repository 待迁移）。

### P2：租户管理与运行资源

迁移用户、组织、Agent、市场、审计、用量、Tier 和 Sandbox Repository，以及沙箱对账作业。

验收要求：

- 租户查询同时具备显式 `org_id` 条件与 PostgreSQL RLS；
- 管理查询只能通过管理 Mapper；
- 配额锁定点和并发创建限制不退化；
- 沙箱对账作业已完成，租户沙箱业务 Repository 待迁移。

### P3：清理旧持久化层

- 删除 `agentscope-saas-core.persistence.entity`；
- 删除 `agentscope-saas-core.persistence.repo`；
- 删除 `spring-boot-starter-data-jpa` 和 Hibernate；
- 删除生产代码中的 `JdbcTemplate`、`EntityManager` 和业务 `java.sql` 调用；
- 将 `agentscope-saas-core` 收敛为共享领域能力，或按 bounded context 拆除。

## 6. 完成定义

只有同时满足以下条件，重构才算完成：

- 生产业务代码中 JPA Repository 数量为 0；
- 生产业务代码中直接 JDBC 数据访问数量为 0；
- 所有数据库访问从应用服务经过领域 Repository Port 进入 DAL；
- server/app 不依赖 DAL Data Object 或 Mapper；
- 管理和租户数据源的 Mapper 扫描边界有自动化测试；
- H2 回归、PostgreSQL 锁并发测试、认证端到端和任务端到端全部通过；
- 数据库 SQL、索引、RLS 和 Flyway 仍是唯一数据库结构事实源。
