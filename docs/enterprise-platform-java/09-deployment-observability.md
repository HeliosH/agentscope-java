# 09 · 部署与可观测性（Java）

> 全栈可自部署、可离线，数据不出企业内网。

## 1. 小规模部署（PoC / 1-50 人，单机）

```
+--------------------------------------------------+
| Single Linux Server (16C32G, KVM)                |
|  QwenPaw SaaS App   DockerSandbox   PostgreSQL    |
|  Valkey             MinIO           Traefik       |
+--------------------------------------------------+

docker compose up -d
├─ qwenpaw-saas-app: Spring Boot WebFlux
├─ postgres: PostgreSQL 16    ├─ valkey: Valkey 8
├─ minio: MinIO Server        └─ traefik: TLS + routing
（沙箱用 DockerSandbox；模型指向内网 vLLM/Ollama 节点）
```

## 2. 生产级部署（1000+ 人，K8s）

```
                  Ingress (Higress / Spring Cloud Gateway, TLS)
                                |
+------------------------------------------------------------+
|                    K8s Cluster (3+ nodes)                  |
|  QwenPaw SaaS App     Sandbox Runtime        PostgreSQL    |
|  (Deploy x3, HPA)     (DockerSandbox /        (Patroni HA  |
|                        KubernetesSandbox /     primary+    |
|                        CubeSandbox 节点池)      standby)     |
|                                                            |
|  模型网关(HPA)         Valkey Cluster         MinIO Cluster  |
|  内网 vLLM/Ollama      (Sentinel, 3+)         (4+ nodes,NVMe)|
|  (GPU 节点池)                                               |
|                                                            |
|  XXL-Job/Quartz 集群   Grafana+Prometheus+Loki  Vault/KMS  |
+------------------------------------------------------------+
```

### 2.1 命名空间与隔离
`management` / `runtime` / `sandbox` / `infra` 分 namespace，`NetworkPolicy` 限东西向；沙箱节点池独立（taint + KVM，CubeSandbox）；GPU 模型节点独立池。

### 2.2 弹性伸缩
| 组件 | 控制器 | 指标 |
|------|--------|------|
| SaaS App / 模型网关 / 网关 | HPA | CPU + 在途会话 + QPS |
| 沙箱池 | KEDA | 并发需求；预热池保底 |
| Scheduler | **XXL-Job/Quartz 集群自带多副本** | 无需自做 leader 选举 |
| PostgreSQL/Valkey | Patroni/Sentinel | 主从、连接池 |

> **相对 Python 方案优势**：Spring Boot 应用无状态多副本天然友好（会话在 RedisSession、沙箱锁用 RedisSandboxExecutionGuard、调度用 XXL-Job 集群）—— Python 方案需自做的"渠道分片 + cron leader 选举"，Java 大部分由生态组件解决（渠道分片仍需处理）。

## 3. 镜像与离线交付

| 镜像 | 内容 |
|------|------|
| `qwenpaw-saas-app` | Spring Boot WebFlux 主应用 |
| `model-gateway` | 模型网关 |
| `sandbox-template` | 代码执行 + 浏览器(Playwright) + 内置技能（CubeSandbox/Docker 镜像） |
| `console` | 前端 SPA（AG-UI） |

**离线要点**：镜像预推内网 registry；Maven 依赖走内网仓库（Nexus）；模型权重内网下载（Ollama/vLLM 本地加载）；整体打离线包（镜像 + jar + 模型 + binary）。交付：Docker Compose（小）/ Helm chart（生产）。

## 4. 可观测性

框架已内置 OpenTelemetry（`AgentTraceMiddleware`，OTel 1.61）+ admin-starter（`MetricsRecorder`/`MetricsHook`，Micrometer）。三支柱：

| 支柱 | 工具 | 内容 |
|------|------|------|
| Tracing | OTel → Tempo/Jaeger | 端到端，带 `org_id` 标签 |
| Metrics | Micrometer + Prometheus + Grafana | QPS/延迟/错误率/用量/配额水位/队列深度 |
| Logging | Loki | 结构化日志（含 org/user/session/trace_id），审计另存 |

### 4.1 多租户追踪
```java
public class TenantTraceMiddleware implements MiddlewareBase {
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc,
            AgentInput in, Function<AgentInput, Flux<AgentEvent>> next) {
        TenantContext tc = rc.get(TenantContext.class);
        Span span = Span.current();
        if (tc != null) {
            span.setAttribute("tenant.org_id", tc.orgId());
            span.setAttribute("tenant.user_id", tc.userId());
            span.setAttribute("tenant.tier", tc.tier());
        }
        return next.apply(in);
    }
}
```
完整链路 span：`tenant.resolve → rate_limit.check → sandbox.acquire → [sandbox.exec → agent.reasoning/tool_call/acting] → usage.record → stream.forward`。

### 4.2 关键指标（Micrometer）
```
saas.sandbox.lifecycle.events    Counter type,event
saas.sandbox.run.duration        Timer   type,signal
saas.sandbox.pool.size           Gauge   type,status
saas.sandbox.pool.expired_active Gauge   type
saas.sandbox.create.duration     Timer   type               (planned)
saas.sandbox.resume.duration     Timer   type               (planned)
saas.sandbox.request.queue_depth Gauge   type               (planned)
saas.agent.call.duration         Timer   model              (planned)
saas.llm.token.usage             Counter model,type         (planned)
saas.channel.messages            Counter channel_type,direction (planned)
```

已实现的 sandbox 指标刻意不带 `org_id`/`user_id`，避免高基数标签拖垮指标后端；租户级排查继续通过 `sandboxes` 表、`memory_events` 表和审计日志完成。
`saas.sandbox.lifecycle.events{type,event}` 当前事件包括：`registered`、`released`、`evicted`、`quota_rejected`、tracking failure，以及 release 链路的 `workspace_projection_succeeded`、`workspace_projection_failed`、`state_persist_failed`、`sandbox_stop_failed`、`sandbox_shutdown_failed`、`backend_release_failed`、`acquire_start_failed`。其中 `workspace_projection_succeeded` 只在实际投影文件数大于 0 时计数，`sandbox_stop_failed` 覆盖 stop 阶段的 workspace snapshot 持久化失败。
`saas.sandbox.pool.size{type,status}` 和 `saas.sandbox.pool.expired_active{type}` 由 eviction job 周期性刷新，直接反映 tracking table 中的资源池状态；`expired_active > 0` 应作为资源泄漏/回收延迟告警信号。

### 4.3 Grafana 面板
Sandbox Pool Overview / Request Latency(p50/p95/p99) / Token Usage by Org / Channel Activity / Error Rate / Sandbox Health。

### 4.4 告警规则

已落地 Prometheus 沙箱运行时告警规则：[alerts.yml](../../agentscope-saas/observability/prometheus/alerts.yml)。当前覆盖：
- expired active tracking row：资源释放延迟/泄漏；
- release 链路失败：tracking release、backend release、sandbox shutdown；
- snapshot / projection 持久化失败：workspace projection、state persist、sandbox stop；
- acquire 失败：provider/template/network 不可用；
- quota rejection spike：租户并发配额不足或资源池耗尽；
- active pool high：长期高水位容量风险。

`SaasSandboxActivePoolHigh` 的默认阈值是保守样例，生产环境应按 CubeSandbox/E2B 节点池容量调整；低基数 runtime 指标只做全局告警，租户级排查继续走 Admin API 和审计表。

### 4.5 运维查询

`GET /api/admin/sandboxes` 已提供 org-admin 级 sandbox inventory，支持 `userId`、`status`、`sandboxType`、`expiredOnly`、`limit` 过滤。该接口只从 JWT 读取 org scope，不能通过参数跨租户查询，适合巡检 active/expired tracking row 和资源泄漏。

### 4.6 健康检查
```java
@Scheduled(fixedRate = 30_000)
public Mono<Void> checkAll() {  // 每沙箱 exec("echo ok")，3 次失败 → rebuild
    ...
}
```

### 4.7 SLA 目标
| 指标 | 目标 |
|------|------|
| API p50（非 agent） | < 200ms |
| Agent 首 token | < 3s |
| 流式 chunk 间隔（TEXT_BLOCK_DELTA） | < 100ms |
| 沙箱 resume（Cube / Docker） | < 200ms / < 2s |
| 沙箱冷启动 | < 30s |
| 文件操作（RemoteFilesystem，缓存命中） | < 50ms |
| 沙箱可用率 | > 99.5%（月度） |
| 数据持久化成功率 | > 99.99%（MinIO 纠删码） |
| 并发用户 | 1000+（单集群） |

## 5. 优雅降级（DegradationManager）
| 故障 | 降级 |
|------|------|
| CubeSandbox/Docker 不可用 | `LocalFilesystemSpec`（无隔离）+ 告警 |
| MinIO 不可用 | `RemoteFilesystem` 回退 `JdbcStore`（仅小文件） |
| PostgreSQL 不可用 | 只读缓存继续，写排队 |
| Valkey 不可用 | `SandboxStateStore` 降级 `SessionSandboxStateStore`；限流降内存计数 |
| 模型后端超时 | 指数退避重试（1→2→4s），3 次失败友好提示 + 容灾 |
| 渠道 Webhook 失败 | 消息持久化 PG outbox，恢复后重发 |

```java
@Component
public class DegradationManager {
    public FilesystemSpec resolveFilesystem(TenantContext tc) {
        if (cubeHealth.health().getStatus() == Status.UP)
            return new SandboxFilesystemSpec().isolationScope(IsolationScope.USER)
                                              .sandboxClient(cubeSandboxClient);
        alertService.sendAlert("sandbox_unavailable", tc.orgId());
        return new LocalFilesystemSpec().mode(LocalFsMode.ROOTED)
                                        .pathPolicy(PathPolicy.of(Path.of("/workspace")));
    }
}
```

## 6. 容灾与备份
- PostgreSQL：Patroni 主从 + WAL 归档到 MinIO（PITR）；
- MinIO：纠删码（单节点故障不丢）+ 版本化；
- Valkey：AOF+RDB + Sentinel；
- 备份：按组织快照到 MinIO，支持组织级恢复。

## 7. 开源中间件选型
| 层 | 选型 | 许可证 |
|----|------|--------|
| 沙箱 | Docker / K8s / CubeSandbox | Apache 2.0 |
| 对象存储 | MinIO | AGPL 3.0 |
| 数据库 | PostgreSQL 16+ | PostgreSQL |
| 缓存 | Valkey | BSD-3 |
| 监控 | Grafana + Prometheus + Loki | AGPL/Apache |
| 网关 | Higress / Spring Cloud Gateway | Apache |
| 调度 | Quartz / XXL-Job | Apache |
| 消息 | RocketMQ / PG LISTEN-NOTIFY | Apache |

> 落地顺序见 [10-roadmap.md](./10-roadmap.md)。
