# 13 · 存储升级设计（对齐 SAAS_ARCHITECTURE + 吸收 MountPoint/newCOS 实践）

> 起因：核对 [12-implementation-plan.md](./12-implementation-plan.md) 落地情况后发现，**存储层是当前与 SAAS_ARCHITECTURE 方案差距最大的子系统**。本文档：① 摆清差距；② 给出保留"无 FUSE 两层读"主架构前提下的升级方案 S1–S6；③ 吸收腾讯云《当 AI Agent 需要"记住"文件：MountPoint + newCOS 在沙箱场景的应用实践》一文的工程教训，明确**为什么我们不走 FUSE 路线**。

## 1. 当前存储实现盘点

| 数据 | 后端 | 机制 | 文件 |
|------|------|------|------|
| 结构化（org/user/agent/session/messages/usage/audit/sandbox 追踪） | PostgreSQL | Flyway V1–V5 | `db/migration/` |
| 会话状态 / 限流 / 分布式锁 / agentState | Valkey（默认关） | `RedisConfig` | `config/RedisConfig.java` |
| 沙箱**关**时的工作区（MEMORY.md/skills/memory/…） | Valkey `RedisStore`（BaseStore，CAS 写） | `RemoteFilesystemSpec` | `config/AgentConfig.java` |
| 沙箱**开**时的工作区持久化 | **PostgreSQL BYTEA**（整包 tar） | `PgRemoteSnapshotClient` | `storage/PgRemoteSnapshotClient.java` |
| 对象存储（MinIO/S3/COS） | **无** | — | — |

迁移止于 V5；方案要求的 V6（RLS）未建。`PgRemoteSnapshotClient` Javadoc 自述为 dev 兜底。

## 2. 与 SAAS_ARCHITECTURE 方案的差距

方案明确三层后端（§3.1 / [06](./06-runtime-and-data.md) §3 / [05](./05-resource-management.md) §5）：**PostgreSQL(+pgvector)** 主存储、**Valkey** 会话/小文件/状态/锁、**MinIO** 快照/上传/导出。文件持久化核心是框架**两层读架构（§8.2，明确"无 FUSE"）**。

| # | 差距 | 严重度 | 说明 |
|---|------|--------|------|
| G1 | **MinIO/S3 后端完全缺失** | 高 | 方案要求 MinIO 存快照/上传/导出，当前全塞进 Postgres BYTEA。`MinioSnapshotSpec`（§App B 列出）未实现。框架已有 `OssRemoteSnapshotClient`/`OssBaseStore`（阿里 OSS）可作参考。 |
| G2 | **沙箱开启时无实时文件持久化** | 高 | 沙箱内文件只在 `stop()` 时整包 tar 上传。崩溃/OOM/驱逐发生在两次 stop 之间 → 工作丢失。方案两层读要求"沙箱活时也走持久化"，当前沙箱活时是纯容器临时盘。 |
| G3 | **快照是整包 tar，非按文件随机访问** | 中 | 恢复需下载+解压整个包才能读一个文件；无增量/懒加载。 |
| G4 | **`PgRemoteSnapshotClient` 是 dev 兜底，非生产级** | 高 | 生产应换 MinIO（D5 已写但未实现）。BYTEA 不适合大工作区/高基数。 |
| G5 | **RemoteFilesystemSpec namespace 未对齐方案** | 中 | 当前 namespace=`[agents, agentId, users, userId]`，方案 §8.4 要求 `List.of("org", orgId, "user", userId)`——缺 org 维度，多租户隔离弱化。 |
| G6 | **方案文档自身矛盾（JuiceFS）** | 低 | §3.2/§8/[03](./03-execution-and-sandbox.md) §7 说"无 FUSE/JuiceFS"，但 §3.1 图、§15.2 部署、§18 风险表又列了 JuiceFS。需先消歧。 |

## 3. 参考：MountPoint + newCOS 文章的教训与启示

腾讯云文章讲的是**元宝 SKILL 沙箱**的高并发存储实践。核心结论：

1. **对象存储 ≠ 文件系统，强行混用会爆。** 把对象存储经 FUSE/MountPoint 挂成本地目录后，POSIX 的每次 `stat/ls/open/路径遍历`都会触发多次 LIST/HEAD。单桶 LIST 默认仅 1000 QPS，沙箱成百上千实例共桶即频控雪崩（503 SlowDown → 指数退避 → 卡顿 → I/O error）。
2. **"不存在路径的探测"是雪崩主因。** 沙箱里训练找 checkpoint、业务做存在性检查，大量命中"不存在的路径"，每次必须 `ListObjects(prefix, max-keys=1)` 打穿后端（无目录概念，无法靠 dentry 缓存拦截）。
3. **解法分两条腿：** 后端换 newCOS（YottaIndex 索引：索引列瘦身、原生目录、有序存储、小分片弹性、复杂场景智能优化；LIST QPS 1k→100k、延迟降 75%）；客户端 MountPoint 调参（`disable-parallel-lookup-unknown-type: false` 并行 lookup；`lookup-explicit-dirs-first: false` 沙箱无显式目录省多余 HEAD）。每次路径查询从"3 次串行"变"2 次并行"，HEAD 减半。

### 对本方案的意义

当前方案选的是**"无 FUSE、框架两层读"**路线——这恰好**规避了文章描述的整个问题类别**：

- 不挂对象存储为文件系统 → 不会有 POSIX→LIST 翻译放大；
- `RemoteFilesystem` 走 `BaseStore.get(namespace, key)` 是**显式 KV 查询**，一次 `get` 一次 KV 操作，没有逐级目录探测；
- "不存在路径"在 KV 模型里就是一次 `get` 返回空，**天然无穿透放大**。

因此本文档的升级方向是**保留无-FUSE 两层读主架构，补齐 MinIO 后端与热路径持久化**，**不**改走 FUSE。文章的 FUSE 方案是"换更强后端 + 客户端调参"去**弥补** FUSE 引入的放大；当前方案根本不引入 FUSE，问题不存在。文章真正值得借鉴的是其**容量与索引设计哲学**（见 S5）。

## 4. 升级方案 S1–S6

### S1 · 落地 MinIO/S3 快照后端（补 G1/G4，对应 D5）

- 新建 `agentscope-saas-storage` 下的 `MinioRemoteSnapshotClient implements RemoteSnapshotClient`（`upload/download/exists`），布局对齐方案 §8.3：
  ```
  minio://<bucket>/
  |-- snapshots/{org_id}/{user_id}/{agent_id}/{timestamp}.tar.gz
  |-- uploads/{org_id}/{user_id}/
  |-- exports/{org_id}/{user_id}/
  |-- templates/skills/
  ```
- `SandboxConfig.buildSnapshotSpec()` 改为按 `saas.storage.snapshot.backend = pg | minio | oss` 选择；Pg 保留为 dev/H2 兜底。复用框架 `OssRemoteSnapshotClient`/`OssBaseStore` 作参考实现。
- 新增 `MinioConfig` + `minio` 依赖（`io.minio:minio`），由 `saas.storage.minio.enabled` 门控。

### S2 · 沙箱开启时热路径持久化（补 G2，关键）

不走 FUSE。给沙箱路径接 BaseStore-backed 文件投影，使"沙箱开"与"沙箱关"持久化路径统一：

- **热路径（小文件、KV 实时持久化）**：高频文件（`MEMORY.md`、`AGENTS.md`、`tools.json`、`memory/`、`skills/`、`subagents/`、`plans/`、`sessions/`、`tasks/`）通过 `RemoteFilesystem` 路由写穿到 BaseStore（沙箱开时落 MinIO `OssBaseStore`/`MinioBaseStore`，沙箱关时落 Redis `RedisStore`）。agent 工具读写这些路径 → 透明命中 BaseStore，崩溃也不丢。
- **冷路径（整包快照）**：大文件与全量工作区仍走 stop-time tar 快照到 MinIO，作为崩溃兜底与全量恢复源。
- 效果：**热路径 KV 实时持久化 + 冷路径 tar 快照**双层，兼顾实时性与全量恢复；两次 stop 之间的小文件工作不再丢。

> 实现要点：沙箱开启时构造 `CompositeFilesystem`，将热路径路由到 `RemoteFilesystemSpec`(BaseStore)，其余仍走 `SandboxBackedFilesystem`。与沙箱关闭分支共用同一 `RemoteFilesystemSpec` 构造，仅 `isolationScope` 与 BaseStore 来源不同。

### S3 · namespace 补 org 维度（补 G5，多租户加固）

`RemoteFilesystemSpec.namespaceFactory` 由 `[agents, agentId, users, userId]` 改为方案 §8.4 的：
```java
.namespaceFactory(rc -> {
    TenantContext tc = rc.get(TenantContext.class);
    return tc == null ? List.of("_anonymous")
        : List.of("org", tc.orgId(), "user", tc.userId());
})
```
让存储 key 自带 org 隔离，与 service 层过滤、RLS 形成纵深。

### S4 · 消除方案文档矛盾（补 G6）

删除/订正 SAAS_ARCHITECTURE.md 中残留的 JuiceFS 引用，统一为"无 FUSE / 无 JuiceFS"：
- §3.1 图去掉 "JuiceFS metadata"；
- §15.2 部署去掉 "JuiceFS CSI (per-node daemon)"；
- §18 风险表 "JuiceFS fallback 到直连 S3" 改为 "MinIO 不可用 → RemoteFilesystem 回退 JdbcStore（§14.1）"。
避免实施时被误导引入 FUSE。

### S5 · 借鉴文章反面教训做容量/索引设计（吸收文章经验）

文章警示：**单桶共享 + 高频 LIST 是 FUSE 雪崩根因**。当前虽不走 FUSE，但任何用到对象存储"列目录"的路径（按前缀列快照、按 org 列上传）都需规避高频 LIST：

- **坚持方案分工**：Postgres 存元数据索引（`(org,user,agent,path) → object key` 映射），MinIO 存大对象。目录列举走 Postgres 索引，MinIO `LIST` 仅作低频管理。
- **负面缓存穿透**：文章"不存在路径探测"在 KV 模型里已天然消解（`get` 返空即不存在），无需额外处理；但若 S2 引入任何 prefix-based 列举，需对"空结果"做缓存，避免热点前缀打穿。
- **分片与弹性**：快照按 `{org}/{user}/{agent}` 前缀分桶，避免单前缀热点；对应文章 newCOS "小分片弹性扩容"思路。

### S6 · RLS + 降级 + 可观测（补 D3/D4/D5，与存储配套）

- **RLS**：Flyway `V6__rls.sql`，为所有 tenant 表 `ENABLE ROW LEVEL SECURITY` + `org_id` 策略，作 service 层隔离的纵深防御。
- **DegradationManager**：MinIO 不可用时 `RemoteFilesystem` 回退 `JdbcStore`（仅小文件，方案 §14.1 已写）；Redis 不可用时 `SandboxStateStore` 回退 JDBC（§14.2）。
- **可观测**：MinIO 健康检查 + Micrometer 指标（快照上传延迟、KV get/put 延迟、快照大小分布、驱逐率、回退触发次数）；接 `AgentTraceMiddleware`（OTel）。

## 5. 落地顺序与 DoD

| 步 | 项 | 对应 Phase | DoD |
|----|----|-----------|-----|
| 1 | S4 消歧 + S3 namespace 对齐 | B 收口 | 文档无 JuiceFS 矛盾；存储 key 含 org 维度 |
| 2 | S1 MinIO 快照后端 | D5 | `backend=minio` 时快照落 MinIO，Pg 仅 dev；`backend=pg` 回归不变 |
| 3 | S2 热路径持久化 | B 收口 | 沙箱开启时 `MEMORY.md`/skills 写穿 BaseStore，崩溃后不丢；与沙箱关路径统一 |
| 4 | S5 索引设计 | D5 | 目录列举走 Postgres 索引，MinIO LIST 仅低频；空结果缓存 |
| 5 | S6 RLS+降级+可观测 | D3/D4/D5 | V6 RLS 生效；MinIO 故障回退可用；存储指标可见 |

> S1/S2 是核心，S4/S3 可先行（低风险）。S5 与 S6 随 Phase D 推进。

## 6. 明确不做（避免被文章带偏）

- ❌ **不引入 FUSE / JuiceFS / GooseFS MountPoint**：当前无-FUSE 两层读已规避文章所述 LIST 放大雪崩，引入 FUSE 反而制造问题。
- ❌ **不把对象存储当 POSIX 文件系统挂载**：MinIO 仅作对象存储（快照/上传/导出大对象），文件语义由框架 `RemoteFilesystem`+`BaseStore`（KV）承载。
- ❌ **不为"像本地盘一样访问 COS"做客户端调参**：那是 FUSE 路线的补救措施，本方案不需要。

---
*与 [12-implementation-plan.md](./12-implementation-plan.md) 的关系：本文档细化其 Phase B 收口项（B2′ namespace、B3 工作区持久化）与 Phase D（D3 RLS / D4 可观测 / D5 MinIO+降级）的存储部分；明确保留"无 FUSE"主架构决策，吸收 MountPoint/newCOS 实践的容量与索引经验而非其 FUSE 实现。*
