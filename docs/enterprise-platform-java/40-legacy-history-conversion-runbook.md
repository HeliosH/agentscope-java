# 旧 JSONL 历史受控转换方案与操作说明

更新日期：2026-10-12。对应方案 37 的 R05；不新增结构化企业记忆。

## 1. 转换边界

SaaS 在线消息继续以 PG `runtime_messages` 为权威来源，大正文复用现有 RuntimeBody/MinIO 存储。转换用例调用同一个 `PgSessionArchiveStore`，不绕过正文配额、稳定 ID、摘要冲突和会话 fencing，不新建数据源或业务 JDBC。

本转换器只接收 Harness `SessionTree` 格式的 JSONL：`message`、`summary`、`compaction`。建议使用完整的 `.log.jsonl`，而不是压缩后的上下文文件。格式版本固定为 `1`。普通 Msg JSONL、任意外部聊天导出不自动猜测格式。

旧 SessionTree 的 `content` 是渲染后的文本，不一定保留原工具参数、全部工具调用或媒体结构。因此转换是**历史证据归档**，不是恢复执行状态：

- 写入独立 `agentLabel=legacy`、`sessionKey=legacy:<jobId>` 流，不覆盖在线 assistant/子 Agent 流，不修改网页问答消息表或模型检查点。
- 保留原 entry ID、parent ID、toolCall ID 和逐行字节位置作为映射，不伪造原始 PG seq。归档 seq 只是转换后该流的顺序。
- 有 ID 的记录使用“源版本 + 原 ID”生成稳定归档 ID。同 ID/同内容复用；同 ID/不同内容拒绝。相同文字、不同 ID 不合并。
- 无 ID 的记录使用“源版本 + 源行号”生成稳定 legacy ID，并设置 `syntheticIdentity=true`。它不是精确的原始消息身份。
- TOOL/ASSISTANT 的旧渲染文本按文本归档，toolCall ID 只保存在溯源映射中，不生成可执行 ToolUseBlock，不进入 Journal。
- SYSTEM、summary、compaction、空行均有映射和跳过计数，不作为原始对话消息注入上下文。未知类型、错误 JSON、非法 UTF-8、缺失 role/content 不静默跳过。

现有 JSONL 读取兼容不删除。源文件不被重写，不在普通任务或用户登录路径自动批量转换。

## 2. 持久化与提交协议

Domain 提供 `LegacyArchiveImportRepository`，DAL 用租户 MyBatis Mapper 实现，App 提供受控用例和管理 API。PG/H2 前向迁移为 V54。

`legacy_archive_imports` 固定组织、员工、Agent、会话 generation、格式版本、源不可变 fileVersion ID、大小和 SHA-256；维护字节游标、源行号、转换/跳过数量、行摘要链及 READY/COMPLETE 状态。

`legacy_archive_entries` 保存逐行证据和归档消息 ID/seq/内容摘要。按 `afterLine + limit` 查询，不进行深 OFFSET 或全文加载。

单次推进过程：

1. 核验目标会话和源版本属于相同组织、员工和 Agent；源版本若绑定会话，必须是目标会话。组织只能来自管理员 JWT，不能来自请求正文。
2. 读取固定版本描述符，在 SQL 事务之外获取共享传输许可和私有 spool 预算，流式下载并校验完整大小/SHA-256。
3. 用文件字节 seek 从已持久化位置读取有界批次，严格解析 JSONL；一次请求可连续推进多个批次，复用同一份已校验 spool。
4. 通过现有归档服务提交本批稳定消息；每次提交均核验创建时的会话 generation。
5. 短事务按“会话锁 → 迁移任务锁”的顺序重新核验 generation、游标和状态；核验每条归档 identity/hash，写映射并推进游标。并发处理同一批只提交一份映射。
6. EOF 时核对实际映射数、转换行数及最后字节位置，才标记 COMPLETE。响应包含可核对的源 SHA、数量、摘要链和游标。

消息已提交、映射/游标尚未提交时崩溃：事务回滚进度，重新推进相同范围复用稳定消息，不重复追加源消息。缺失正文存储时复用既有 PG 降级与配额，不另建无限制后备路径。

`importedMessages` 表示已转换的**源记录数量**，不是归档唯一消息数。旧文件重复同一稳定 ID 时，多行映射可以指向同一 seq。

## 3. 权限、保留与撤销

- 仅 org admin/platform admin 可调用；platform admin 也不能通过请求参数跨 JWT 所属组织转换。所有查询仍核验完整 owner，PG 两表均 FORCE RLS。
- 创建、推进复用现有 AuditService，记录管理员身份、任务 ID、源版本和进度，不在审计中保存对话正文或存储凭证。审计保持平台既有 best-effort 语义。
- 源版本有外键引用；版本 GC、已删除文件 GC 的候选扫描和领取均跳过被引用证据，物理对象已有版本引用也会阻止删除。
- COMPLETE 不代表可以删除证据。源引用跟随会话生命周期；会话删除级联删除任务和映射，源版本恢复普通 GC 规则。
- 会话 reset 在同一撤销事务中先删除迁移任务，再清空 runtime 历史；generation fencing 阻止旧在途请求重新写回。重置后需要管理员显式创建新转换任务。
- 配置关闭时 API 返回 404，平台其余运行不受影响。关闭不删除已经转换的数据或解除证据保留。

## 4. 部署与操作

默认关闭，按维护窗口显式启用：

```yaml
saas:
  runtime-archive:
    legacy-import:
      enabled: true
      max-source-bytes: 536870912
      max-line-bytes: 1048576
      max-batch-bytes: 4194304
      max-batch-lines: 100
```

环境变量前缀为 `SAAS_RUNTIME_ARCHIVE_LEGACY_IMPORT_`。源文件最大允许配置到 1 GiB，单行最高 1 MiB，每批最高 16 MiB/500 行；实际归档限制、正文配额和共享 spool 预算仍可能更小，应一起核对。

先把**冻结的旧历史证据**登记为现有文件目录下的不可变版本，记录准确 owner 和 SHA-256；不向接口传宿主机路径或对象 key，不改写旧文件来绕过校验。

基准路径：

```text
/api/admin/agents/{agentId}/sessions/{sessionId}/legacy-archive-imports
```

| 操作 | 方法与路径 | 参数 |
|---|---|---|
| 创建/幂等获取 | POST 基准路径 | `{"userId":"...","sourceVersionId":"..."}` |
| 查询进度 | GET `/{id}?userId=...` | owner 不能省略 |
| 推进 | POST `/{id}/advance?userId=...` | `{"expectedOffset":0,"maxBatches":1000}`；每次使用最新响应游标 |
| 溯源分页 | GET `/{id}/entries?userId=...&afterLine=0&limit=100` | limit 1–500 |

`maxBatches` 可省略，默认单批，允许 1–1000。连续批处理在每批落库后检查 30 秒处理时间预算，耗尽时返回 READY 和最新游标；下载校验和当前 SQL 操作仍使用既有 I/O/事务约束，不承诺整个 HTTP 请求严格 30 秒结束。

响应丢失后先 GET 查询；409 表示游标过期，刷新后继续，不猜测新游标。422 表示格式/完整性或 I/O 文件错误，先核对源证据；服务容量问题返回 503，可在进度查询后重试。对于同 ID/不同内容冲突，不能改用文本去重掩盖，需要人工核查原文件并登记新的版本。

归档查看使用现有 owner-scoped runtime 消息 API，传 `agentLabel=legacy` 和由任务 ID 推导的 `sessionKey=legacy:<jobId>`。员工以自己的身份读取归档；管理接口不会赋予其跨员工的普通问答读取权限。它不等同于新增网页历史导入向导；普通问答时间线不会被历史转换替换。

## 5. 验证与剩余边界

确定性测试覆盖并发、源提交后进度事务故障、新服务实例补建、重复 ID/文本、超限行、字节游标、CRLF/UTF-8/BOM/末行、未知格式、源 SHA 错误、会话重置、默认关闭、越权和证据 GC。

本地 HTTP/真实 PG/MinIO 强杀门禁脚本：

```bash
python3 agentscope-saas/agentscope-saas-app/scripts/legacy-archive-import-smoke.py
```

脚本依赖本地 `saas-pg`、`saas-minio`、`saas-redis` 已运行；只使用自己创建的测试用户/Agent/会话和独占 18082 应用进程。强杀只发生在自己的进程组；故障触发器只匹配本次转换 UUID，最后移除触发器、停止应用、撤销测试账号的临时 admin 角色。测试模型是本地 stub，不调用企业模型或远程沙箱。

本轮验证事实记录在文档 38，不以脚本存在等同通过。

性能边界：每次推进请求会全量流式校验源文件一次，再复用 spool 连续处理有界批次；失败重启需再次校验。普通 Agent 历史读取不走此路径。对于特别大的长期批量迁移，后续可增加经过完整源摘要校验的分块清单和对象 Range 读取，但不能直接信任未校验的局部范围、改用全文件 heap 缓存或取消原始证据保留。尚未证明生产容量或全部历史格式兼容。
