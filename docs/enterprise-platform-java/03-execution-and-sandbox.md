# 03 · 执行层与沙箱（Java，核心优势）

> Java 方案最大的省力点：**沙箱执行层完全复用 agentscope-java harness，无需自建 ExecutionBackend，无需 FUSE**。

## 1. 框架已内置的沙箱体系（已核实）

| 组件 | 位置 | 作用 |
|------|------|------|
| `Sandbox` 接口 | `agentscope-harness/.../agent/sandbox` | 生命周期：start/stop/shutdown/exec |
| `SandboxClient` | 同上 | 工厂：create/resume/serializeState |
| `SandboxManager` | 同上 | **优先级 acquire**：external > persisted > create |
| `SandboxStateStore` | 同上 | 状态持久化（Session/Workspace/Redis） |
| `SandboxIsolationKey` | 同上 | 从 RuntimeContext + IsolationScope 解析唯一键 |
| `SandboxLifecycleMiddleware` | `.../agent/middleware` | 自动 acquire/release |
| `DockerSandbox` | harness `impl.docker` | Docker 容器沙箱 |
| `KubernetesSandbox` | extensions sandbox-kubernetes | K8s Pod 沙箱 |
| `DaytonaSandbox` / `AgentRunSandbox` / `E2bSandbox` | extensions | 其他后端 |

> 对比 Python 方案：Python 需自建 `ExecutionBackend` 抽象 + 逐个改造工具 + `SandboxBroker`；Java 这些**全部框架自带**，工具用 `@Tool` 注解自动在沙箱内执行。

## 2. CubeSandbox 接入（唯一需新建的沙箱代码）

CubeSandbox（腾讯开源，Apache 2.0，KVM microVM，兼容 E2B SDK，可离线）通过实现 `SandboxClient` 接口接入，私有化首选：

```java
// agentscope-saas-sandbox/cube/CubeSandboxClient.java
public class CubeSandboxClient implements SandboxClient<CubeSandboxClientOptions> {
    private final String apiUrl;   // http://cube-api.internal:8080

    @Override
    public Sandbox create(WorkspaceSpec spec, SnapshotSpec snapshot,
                          CubeSandboxClientOptions options) {
        // POST /sandboxes — E2B SDK 兼容 REST API
    }
    @Override
    public Sandbox resume(SandboxState state) {
        // POST /sandboxes/{id}/resume
    }
    @Override
    public String serializeState(Sandbox sandbox) { ... }
}
```

| 维度 | CubeSandbox | Docker | K8s |
|------|-------------|--------|-----|
| 隔离 | 硬件级（KVM μVM + eBPF） | 容器级 | 容器/Pod |
| 冷启动 | < 60ms | ~1-2s | ~2-5s |
| 单机密度 | 2000+ / 96 核 | ~200 | 取决节点 |
| 离线 | ✅ | ✅ | ✅ |

> 私有化生产用 CubeSandbox（强隔离+高密度+离线）；已有 K8s 基础设施可用 `KubernetesSandbox`；开发/降级用 `DockerSandbox`。

## 3. IsolationScope.USER — 一用户一沙箱

框架 `IsolationScope` 枚举含 SESSION/USER/AGENT/GLOBAL。选 **USER** 即实现 QwenPaw「一用户一沙箱」（同用户跨 session 共享）：

```
IsolationScope.USER + RuntimeContext(userId="alice")
  → SandboxIsolationKey{scope=USER, value="org-x:alice"}
  → SandboxManager.acquire()
       Priority 1: external sandbox
       Priority 2: external sandbox state
       Priority 3: stateStore.load() → resume 已有沙箱     ← 命中则复用
       Priority 4: client.create()  → 创建新沙箱
```

`SandboxManager` 的优先级 acquire 逻辑**已在框架实现**（已核实），SaaS 层无需自写。

## 4. SandboxBroker — DB 映射层（轻量新增）

框架 `SandboxStateStore` 已能持久化沙箱状态。SaaS 仅需一层 DB 映射，记录 `(org, user, agent) → sandbox` 元数据用于管理后台展示与配额：

```java
// agentscope-saas-sandbox/broker/SandboxBroker.java
public class SandboxBroker {
    private final SandboxRepository repo;   // JPA/MyBatis

    public SandboxState getOrCreateMapping(String orgId, String userId, String agentId) {
        return repo.findByOrgIdAndUserIdAndAgentId(orgId, userId, agentId)
            .filter(e -> !"terminated".equals(e.getStatus()))
            .map(e -> client.deserializeState(e.getSandboxState()))
            .orElse(null);   // null → SandboxManager 走 Priority 4 创建
    }
}
```

## 5. 沙箱生命周期与 TTL

```
NONE → (create ~30-60s) → CREATING → RUNNING
  RUNNING --idle TTL--> PAUSED --user returns--> RUNNING (resume <60ms Cube / <2s Docker)
  RUNNING --manual/超 max_lifetime--> TERMINATED
```

TTL 由框架 `SandboxLifecycleMiddleware` + sweeper 执行；等级按部门/角色分配（私有化，非套餐计费）：

| 等级 | idle TTL | max_lifetime | max 并发沙箱 |
|------|----------|--------------|--------------|
| standard | 10min | 24h | 1 |
| advanced | 1h | 无 | 3 |
| privileged | 2h | 无 | 5 |

## 6. 工具沙箱化（框架自带 + 少量新建）

| QwenPaw 工具 | Java 实现 | 来源 |
|--------------|-----------|------|
| shell / file read·write·edit / grep / glob | harness `ShellExecuteTool` / `FilesystemTool` | **框架自带，沙箱内执行** |
| browser_use / 快照 | 新建 `BrowserTool`（Java Playwright，沙箱内） | **Java 重写** |
| LSP / AST（Coding 模式） | 经 MCP Gateway 路由到沙箱 或后置 | 评估 |

工具用 `@Tool` 注解注册到 `Toolkit`，由 `SandboxContext(IsolationScope.USER)` 自动在用户沙箱内执行——**这是 Java 相对 Python 路径"逐个工具改造"的最大省力**。

## 7. 文件持久化（框架原生，无 FUSE）⭐

> Java 方案不用 JuiceFS/FUSE，直接用框架两层读架构。这是相对 Python 方案的天然优势。

```
read_file("MEMORY.md")
  → SandboxBackedFilesystem (proxy)
      ├─ 沙箱活跃：sandbox.exec("cat /workspace/MEMORY.md")    ← 直接代理
      └─ 沙箱不活跃：CompositeFilesystem
           ├─ RemoteFilesystem → BaseStore.get(namespace, key)  ← 持久化(JDBC/Redis)
           └─ LocalFilesystem → workspace template fallback
```

- **两层读**：活跃代理沙箱，不活跃走 `RemoteFilesystem` → `BaseStore`；
- **CAS 写**：`BaseStore.putIfVersion()`（已核实）保证并发一致；
- **多租户路径隔离**：`NamespaceFactory rc → List.of("org", orgId, "user", userId)`（见 [04](./04-iam-and-isolation.md)）；
- **大对象归档**：MinIO 存快照/上传/导出。

```java
BaseStore store = JdbcStore.builder(dataSource).initializeSchema(true).build();
RemoteFilesystemSpec remoteFs = new RemoteFilesystemSpec(store)
    .isolationScope(IsolationScope.USER)
    .anonymousUserId("_system")
    .namespaceFactory(rc -> {
        TenantContext tc = rc.get(TenantContext.class);
        return tc == null ? List.of("_anonymous")
                          : List.of("org", tc.orgId(), "user", tc.userId());
    });
```

MinIO 桶组织：
```
minio://qwenpaw-prod/
├── snapshots/{org_id}/{user_id}/{agent_id}/{timestamp}.tar.gz
├── uploads/{org_id}/{user_id}/
├── exports/{org_id}/{user_id}/
└── templates/skills/
```

## 8. 并发控制（同一沙箱多请求）

框架 `RedisSandboxExecutionGuard`（已核实）提供分布式锁。SaaS 加一层优先级队列：

```java
public class SandboxRequestQueue {
    private final SandboxExecutionGuard guard;
    public Mono<Msg> submit(String isolationKey, Msg msg, RuntimeContext rc, int priority) {
        // 入优先级队列；tryEnter 获锁则执行，否则排队
    }
}
```
优先级：① 用户直接输入（不可中断）② SubAgent 调用 ③ Cron（可被 P1 中断）④ 后台技能（最低）。抢占用框架 `Agent.interrupt()` 协作中断。

## 9. 流式穿越沙箱边界
`ReActAgent.streamEvents()` 返回 `Flux<AgentEvent>`（25 事件类型）。沙箱内执行时经 `StreamForwardingMiddleware` HTTP POST 转发到宿主机，再经 AG-UI starter 推 SSE 给前端。详见 [09](./09-deployment-observability.md)。

> 沙箱资源配额与调度见 [05](./05-resource-management.md)。
