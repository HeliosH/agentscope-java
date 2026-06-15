# 05 · 资源治理（Java，无计费）

> 私有化不计费。资源治理 = **配额 + 公平调度 + 审计**，防止单用户/单部门耗尽集群。全部以**中间件 + 框架原语**实现。

## 1. 资源类型

| 资源 | 单位 | 谁消耗 | 治理手段 |
|------|------|--------|----------|
| LLM 推理 | token | 模型调用 | 模型网关计量 + 配额（[07](./07-model-gateway.md)） |
| 沙箱算力 | vCPU·分钟 / 并发数 | 代码/浏览器/文件 | `SandboxManager` + 并发配额 |
| 存储 | GB | 工作区/记忆/附件 | 存储配额 + 生命周期 |
| 会话并发 | 并发数 | 活跃 chat | `RateLimitMiddleware` + 队列 |
| 渠道 | 连接/消息量 | 渠道网关 | 配额 + 限流 |
| 定时任务 | 任务数/频率 | scheduler | 配额 |

## 2. 配额体系（按等级，非订阅）

配额来自**部门/角色等级**（`tier`），由管理员分配，不是付费套餐：

```yaml
tier: advanced          # standard / advanced / privileged
quotas:
  llm_tokens_month: 50_000_000
  sandbox_concurrent: 3
  sandbox_minutes_month: 20_000
  concurrent_sessions: 10
  storage_gb: 50
  agents_max: 20
  scheduled_jobs_max: 50
  channels_max: 5
rate_limits:
  requests_per_min: 120
  model_calls_per_min: 60
```

## 3. 配额执行（中间件）

```java
public class RateLimitMiddleware implements MiddlewareBase {
    private final RateLimiter rateLimiter;   // Valkey 滑动窗口
    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc,
            AgentInput in, Function<AgentInput, Flux<AgentEvent>> next) {
        TenantContext tc = rc.get(TenantContext.class);
        if (!rateLimiter.tryAcquire(tc.orgId(), tc.role())) {
            return Flux.error(new QuotaExceededException(tc.orgId()));   // 429
        }
        return next.apply(in);
    }
}

public class UsageMeteringMiddleware implements MiddlewareBase {
    // onAgent: 起计时；doFinally: 记录 token/沙箱时长 → usage_records（计量，非账单）
}
```

执行链路：
```
请求 → RateLimitMiddleware（QPS/并发，Valkey）
模型调用 → 模型网关：查 token 余额 → 超额拒绝/降级
沙箱分配 → SandboxManager + 并发配额校验 → 超额排队/拒绝
存储写入 → 查容量 → 超额阻止 + 告警
```

### 超额策略（可配置）
| 策略 | 行为 | 适用 |
|------|------|------|
| 硬限 | 拒绝 + 提示联系管理员 | 严格管控部门 |
| 软限 + 告警 | 放行但告警，事后约谈/调额 | 内部信任环境（私有化常用） |
| 降级 | 切低成本模型 / 降并发 | 平滑保障 |

## 4. 沙箱资源调度

- **一用户一沙箱 + 池化**：`SandboxManager` 优先级 acquire（resume 复用 > 新建）；预热池降冷启动；
- **空闲 pause / 长期回收**：框架 `SandboxLifecycleMiddleware` + sweeper，按 tier 的 TTL（见 [03](./03-execution-and-sandbox.md) §5）；
- **单沙箱并发**：`SandboxRequestQueue` + `RedisSandboxExecutionGuard`（分布式锁），优先级抢占（见 [03](./03-execution-and-sandbox.md) §8）；
- **集群公平**：按 org/user 并发上限排队，防 noisy neighbor；高 tier 更高优先级与更大预热池。

## 5. 存储治理

| 数据 | 后端 | 分区 | 生命周期 |
|------|------|------|----------|
| 工作区快照/上传/导出 | MinIO | `{org}/{user}/` | 配额 + 过期清理 |
| 小文件/元数据 | `RedisStore`/`JdbcStore`(BaseStore) | namespace | 随 Agent 删除级联 |
| 向量记忆 | pgvector | `org_id` | 配额 + TTL |
| 结构化数据 | PostgreSQL | `org_id` 行级 | 备份 + 归档 |

## 6. 弹性伸缩
| 组件 | 策略 |
|------|------|
| SaaS Core Engine / 网关 | K8s HPA：CPU + 在途会话 + QPS |
| 沙箱池 | KEDA：按并发需求扩缩；预热池保底 |
| Scheduler | Quartz/XXL-Job 集群（天然多副本，无需自做 leader 选举 ← 相对 Python 路径优势） |
| PostgreSQL/Valkey | Patroni HA / Sentinel 集群 |

> **相对 Python 方案的优势**：调度用 XXL-Job/Quartz 集群天然支持多副本；多副本沙箱锁用框架 `RedisSandboxExecutionGuard`——这两处 Python 路径需自建，Java 框架自带。

## 7. 资源治理后台（扩展 admin-starter）
复用 `admin-starter` 的 `MetricsRecorder`/`MetricsHook`，新增：
- 实时用量看板（token/沙箱/存储/并发，按 org/部门/user/model 下钻）；
- 配额配置（按 tier/部门）+ 告警阈值；
- 沙箱池运行态（`/admin/sandboxes`）+ 强制回收；
- 资源归集报表（趋势、Top 消耗者），用于内部规划与约谈（非账单）。

> 计量采集见 [07-model-gateway.md](./07-model-gateway.md) §5；指标定义见 [09-deployment-observability.md](./09-deployment-observability.md)。
