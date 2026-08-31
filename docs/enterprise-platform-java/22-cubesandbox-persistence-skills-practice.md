# CubeSandbox 持久化、Skills 与恢复方案

> 本文保留 Cube hostPath 与快照兼容方案。Cube 0.6 及以上生产部署的推荐工作区方案已升级为
> [CubeSandbox Volume 持久工作区优化方案](./24-cubesandbox-volume-workspace-plan.md)。

## 1. 目标与边界

CubeSandbox 是部署时选择的企业生产沙箱 Provider，用户、任务规划器和子 Agent 不感知
Provider。本文方案参考《在 CubeSandbox 中运行 Hermes Agent：从持久化挂载、Skills 分层到
网络与恢复故障的工程实践》，但按当前系统的数据边界进行适配，而不是复制 Hermes 目录结构。

当前系统的数据归属如下：

| 数据 | 权威存储 | 是否放入 Cube host-mount |
|---|---|---|
| 对话、任务、审计 | PostgreSQL | 否 |
| 个人长期记忆 | PostgreSQL 账本，Mem0/向量索引为检索投影 | 否 |
| Redis 会话、锁、运行态 | Redis | 否 |
| 用户任务工作区、上传文件、生成文件 | MinIO 快照与文件对象 | 默认否 |
| 企业公共 Skills、大型只读数据集 | Cubelet 共享存储 | 是，只读 |
| 用户私有 Skills | 用户工作区与远程投影 | 否，私有版本优先 |

因此不增加 Hermes 的 `/root/.hermes` 挂载。Agent 配置和记忆必须继续由应用层管理，不能把
沙箱磁盘变成第二套权威数据源。

## 2. 推荐架构

```text
Git/内部 Skills 集市
        |
        | 受控发布
        v
Cubelet/NFS: /data/shared/agentscope-common-skills
        |
        | metadata["host-mount"]，只读
        v
Cube: /opt/agentscope-common-skills
        |
        | 启动时为不存在的 Skill 建立符号链接
        v
/workspace/skills/<name>
        ^
        | 同名目录已存在时不覆盖
用户私有 Skills（优先）

用户工作区 <----> Sandbox workspace <----> MinIO tar snapshot
                                        跨节点、跨实例、跨 Provider 恢复
```

采用两层持久化：

1. Cube host-mount 只承载企业统一维护、适合共享且默认只读的内容，更新无需向每个沙箱复制。
2. MinIO 快照继续承载用户工作区，是跨 Cubelet、跨 Provider 的可移植恢复基线。

不默认把 `/workspace` 直接映射到 node-local hostPath。企业多租户环境中，这会引入调度绑定、
目录预创建、UID/GID、租户串读和节点故障恢复问题。确需原生工作区挂载时，应使用每租户独立
目录、统一 NFS 路径和受控目录预置服务，并保留 MinIO 冷备。

## 3. 创建与启动链路

1. 应用根据部署配置构造挂载，用户请求不能传入 hostPath。
2. 应用校验 hostPath、mountPath 均为规范绝对路径，拒绝 `..`、根目录和允许前缀之外的路径。
3. Cube 创建请求按官方协议发送 JSON 编码的 `metadata["host-mount"]`。
4. CubeMaster 再执行平台侧允许前缀校验；应用校验不能替代 CubeMaster 安全边界。
5. 沙箱启动后检查每个声明的挂载是否真实存在，防止旧版本或错误部署静默忽略 metadata。
6. Workspace 快照恢复和投影完成后，刷新公共 Skills 符号链接。
7. `/workspace/skills/<name>` 已存在时视为私有 Skill，不创建同名公共链接。
8. 工作区内的嵌套 host-mount 在生成或恢复 tar 快照时被排除，避免归档大型只读数据或向
   只读挂载写入。

Cube 连接或恢复失败时，现有运行时会重建沙箱，并从 MinIO 快照恢复用户工作区。故障实例
不作为权威状态源，避免暂停恢复异常导致用户文件丢失。

## 4. 配置

公共 Skills 推荐配置：

```bash
SAAS_SANDBOX_TYPE=cube
SAAS_SANDBOX_CUBE_COMMON_SKILLS_HOST_PATH=/data/shared/agentscope-common-skills
SAAS_SANDBOX_CUBE_COMMON_SKILLS_MOUNT_PATH=/opt/agentscope-common-skills
SAAS_SANDBOX_CUBE_ALLOWED_HOST_MOUNT_PREFIXES=/data/shared/
SAAS_SANDBOX_CUBE_VERIFY_HOST_MOUNTS=true
SAAS_SANDBOX_SNAPSHOT_BACKEND=minio
```

其他只读数据集可通过部署级 JSON 增加：

```bash
SAAS_SANDBOX_CUBE_HOST_MOUNTS_JSON='[
  {
    "hostPath": "/data/shared/enterprise-datasets",
    "mountPath": "/opt/enterprise-datasets",
    "readOnly": true
  }
]'
```

`hostPath` 支持 `{sessionId}` 占位符，用于调用方已经具备按沙箱预创建目录能力的场景。目录
仍必须在实际承载沙箱的 Cubelet 节点上提前存在；应用服务器本地同名目录无效。

## 5. Cube 集群前置条件

- CubeMaster 的 `allowed_host_mount_prefixes` 只允许具体受控目录，禁止 `/` 或宽泛根路径。
- 单节点可以使用本地 `/data/shared`；多节点必须在所有 Cubelet 上挂载同一 NFS/CephFS，且
  路径一致。
- 目录必须在创建沙箱前存在。公共 Skills 发布流水线负责创建目录和同步内容。
- 可写目录的宿主 UID/GID、ACL 必须与沙箱运行用户匹配；`readOnly=false` 不会绕过 Linux
  权限。
- 公共 Skills 必须只读挂载，更新由 Git/内部集市发布流程完成，沙箱内 Agent 不得修改。
- 模板需显式配置所需内网 CIDR。允许互联网访问不等于允许访问企业局域网地址。
- 模板发布前清理 `.DS_Store` 和 `._*` AppleDouble 文件，避免 Agent 扫描时出现解码错误。

## 6. 网络与健康检查

模型调用由应用侧模型路由执行，不依赖 Cube 到模型网关的网络；但沙箱内 MCP、包安装、内部
数据服务和命令工具仍可能需要出站访问。网络策略在 Cube 模板/集群部署时确定，不开放为用户
参数。

发布模板时至少验证：

1. Cube command service 健康检查可用。
2. 沙箱到必需内网服务的 TCP 端口可达。
3. HTTP 未携带凭据时能得到预期的 401/403，而不是连接超时。
4. 不需要的公网和其他租户网段不可达。

生产环境不建议直接使用 `0.0.0.0/0`，应按模型网关、MCP、制品仓库和必要数据服务配置最小
CIDR 与端口范围。

## 7. 恢复与发布验收

每次 Cube 版本、模板或挂载策略变更都必须执行重建测试：

1. 创建沙箱，确认公共 Skills 可见且不可写。
2. 创建同名私有 Skill，确认私有版本优先且不会被刷新覆盖。
3. 上传文件并生成任务产物，释放沙箱。
4. 在新沙箱中恢复同一用户工作区，确认文件、版本和目录隔离正确。
5. 模拟原实例无法 connect，确认自动重建后从 MinIO 恢复。
6. 多节点环境强制调度到另一 Cubelet，确认公共挂载与快照恢复均成功。
7. 检查日志和指标中的挂载校验失败、快照失败、恢复失败与后端释放失败事件。

只有“销毁原沙箱后仍能正确恢复”才算完成持久化验证。
