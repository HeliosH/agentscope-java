# LlamaFirewall Agent Runtime 安全集成

## 1. 定位

平台以 LlamaFirewall 替代 ClawSentry，承担 Agent 运行时的内容与代码安全扫描。它不会替代本地 `PermissionEngine`、`ToolInputSecurityGuard` 或 Sandbox：

- LlamaFirewall 判断输入、工具返回和生成代码是否包含提示注入或危险内容；
- PermissionEngine 判断用户和 Agent 是否有权执行工具；
- ToolInputSecurityGuard 阻断确定性的危险参数、路径和凭据外传；
- Sandbox 对文件、进程和网络实施最终资源隔离。

所有策略合并时采用最严格结果。LlamaFirewall 的 `ALLOW` 不能覆盖本地 `ASK` 或 `DENY`。

## 2. 运行链路

```text
用户输入
        │
        ▼
LlamaFirewall USER_INPUT 扫描
        │
        ▼
企业内部模型规划
        │
        ▼
ToolInputSecurityGuard + PermissionEngine
        │
        ▼
LlamaFirewall TOOL_REQUEST / CodeShield 扫描
        │
        ▼
Sandbox 执行工具（含读取上传文件）
        │
        ▼
LlamaFirewall TOOL_RESULT 扫描
        │
        ▼
结果重新进入企业内部模型
        │
        ▼
LlamaFirewall ASSISTANT_OUTPUT 扫描
        │
        ▼
通过网页返回用户
```

Java Runtime 通过 `LlamaFirewallSecurityMiddleware` 在每次模型调用前，按顺序扫描本轮尚未检查的全部用户消息和工具结果，并在最终输出返回网页前完成整体扫描；通过 `LlamaFirewallToolSecurityPolicy` 在工具执行前扫描参数。并行工具返回不会只检查最后一条，已经检查的消息在同一次 Agent 调用内不会重复发送。上传文件本身先经过类型校验和部署可选的 ClamAV；文件内容被工具读取并准备进入模型上下文时，再作为工具结果接受 LlamaFirewall 检查。启用安全服务后，最终输出会在扫描通过后释放，避免不安全的流式片段先到达用户。扫描服务返回：

- `allow`：继续执行，但仍须通过本地权限与沙箱策略；
- `block`：阻断输入或工具调用；
- `human_in_the_loop_required`：工具调用进入现有人工确认流程，内容输入暂停并返回安全错误；
- 扫描服务不可用：默认进入 `LOCAL_GUARD_ONLY`，跳过远端扫描但继续执行本地权限、危险参数检查和 Sandbox 隔离；也可按部署要求改为 `ASK`、`DENY` 或仅只读降级。

LlamaFirewall 返回的明确 `block` 或 `human_in_the_loop_required` 决策不会触发降级，也不会被本地降级策略覆盖。只有连接失败、超时、非成功 HTTP 响应或非法响应协议才视为外部服务故障。超过本地扫描长度上限的数据始终要求人工确认（`DENY` 模式下直接阻断），不能通过 `LOCAL_GUARD_ONLY` 放行。

## 3. 部署架构

LlamaFirewall 是独立进程的安全服务，不属于当前平台进程，也不运行在用户任务沙箱内。Java 应用只维护 HTTP 适配器和安全决策接线，不包含 Python/Torch 依赖或模型权重，也不会加载、启动或停止 LlamaFirewall。

```text
AgentScope SaaS Runtime ──HTTP/token──> 企业 LlamaFirewall 服务
       │                                  │
       │                                  ├─ CodeShield
       │                                  ├─ Hidden ASCII
       │                                  └─ Regex
       └──────────────> Sandbox Provider
```

集成测试可通过仓库中的独立 Docker Compose 启动参考服务，但它不被平台应用或平台本地启动脚本依赖。生产环境的版本、镜像、计算资源、容量和扩缩容由企业安全基础设施独立管理。平台只依赖稳定的扫描协议，因此安全服务可以独立升级；协议不兼容时通过新路径或版本化接口灰度切换。

CodeShield、Hidden ASCII、Regex 等扫描器的组合由独立服务配置，平台不感知扫描器实现。其他实验能力应在安全服务侧完成中文数据评测和延迟验证后再加入策略。

## 4. 平台配置

```yaml
saas:
  security:
    llama-firewall:
      enabled: true
      base-url: http://llama-firewall:8080
      scan-path: /v1/scan
      api-token: ${SAAS_SECURITY_LLAMA_FIREWALL_API_TOKEN}
      connect-timeout-millis: 250
      decision-timeout-millis: 2000
      max-content-chars: 100000
      failure-mode: LOCAL_GUARD_ONLY
      circuit-failure-threshold: 3
      circuit-open-duration-millis: 30000
      audit-enabled: true
```

对应环境变量：

- `SAAS_SECURITY_LLAMA_FIREWALL_ENABLED=true`
- `SAAS_SECURITY_LLAMA_FIREWALL_BASE_URL=http://llama-firewall:8080`
- `SAAS_SECURITY_LLAMA_FIREWALL_API_TOKEN=<internal-token>`
- `SAAS_SECURITY_LLAMA_FIREWALL_FAILURE_MODE=LOCAL_GUARD_ONLY|ASK|DENY|ALLOW_READ_ONLY`
- `SAAS_SECURITY_LLAMA_FIREWALL_CIRCUIT_FAILURE_THRESHOLD=3`
- `SAAS_SECURITY_LLAMA_FIREWALL_CIRCUIT_OPEN_DURATION_MS=30000`

配置不存在或 `enabled=false` 时，平台不访问扫描服务并保持正常运行。

四种故障策略的适用边界：

- `LOCAL_GUARD_ONLY`（默认）：保障任务连续性，远端不可用时继续经过本地安全链，适合本地控制完整的企业部署；
- `ALLOW_READ_ONLY`：只允许只读工具和最终输出继续，写操作转人工确认；
- `ASK`：所有无法扫描的请求转人工确认；
- `DENY`：所有无法扫描的请求直接阻断，适合最高安全等级环境。

## 5. 熔断与自动恢复

Runtime 对 LlamaFirewall 使用进程内调用级熔断器：

1. 连续失败达到 `circuit-failure-threshold` 后进入 `OPEN`，冷却期内不再访问故障服务，立即执行配置的故障策略；
2. `circuit-open-duration-millis` 到期后进入 `HALF_OPEN`，只允许一个探测请求，其他并发请求继续快速降级；
3. 探测成功后恢复 `CLOSED` 并清零连续失败计数，探测失败则重新开始完整冷却期；
4. 有效的 `allow`、`block`、`human_in_the_loop_required` 都表示服务健康并关闭熔断器，业务安全决策与依赖健康状态分开处理。

Actuator `health` 的 `llamaFirewall` 项公开 `enabled`、`degraded`、`circuitState`、连续失败数和剩余冷却时间。由于该服务属于可降级增强能力，熔断时组件状态仍为 `UP`，不会导致平台实例被摘除。Prometheus 指标 `saas_security_llama_firewall_requests_total` 和 `saas_security_llama_firewall_duration_seconds` 可用于配置错误率、超时和熔断告警。降级决策继续写入安全审计；审计存储本身失败不会影响任务主链路。

## 6. 服务协议

平台向配置的 `scan-path` 发送请求：

```json
{
  "request_id": "uuid",
  "stage": "user_input|tool_request|tool_result|assistant_output",
  "role": "user|assistant|tool",
  "content": "待扫描内容",
  "target": "message:id 或 tool:name",
  "read_only": false,
  "context": {
    "tenant_id": "tenant-id",
    "user_id": "user-id",
    "session_id": "session-id"
  },
  "metadata": {}
}
```

服务返回统一决策，不返回或记录原始内容：

```json
{
  "decision": "allow|block|human_in_the_loop_required",
  "reason": "可审计但不包含原文的原因",
  "score": 0.98,
  "scanner": "regex"
}
```

Java 单元测试使用 Mock HTTP Server 验证协议、认证、超时、敏感参数脱敏和故障降级。独立镜像不包含模型权重，运行 CodeShield、Hidden ASCII 和 Regex，可直接完成服务协议及真实扫描器 smoke。

启动集成服务：

```bash
cp agentscope-saas/agentscope-saas-app/docker/.env.llama-firewall-integration.example \
   agentscope-saas/agentscope-saas-app/docker/.env.llama-firewall-integration

agentscope-saas/agentscope-saas-app/scripts/start-llama-firewall-integration.sh
agentscope-saas/agentscope-saas-app/scripts/stop-llama-firewall-integration.sh
```

启动脚本会等待健康检查并发送一次真实扫描请求；未得到合法扫描决策时启动失败。独立镜像不下载或包含任何模型权重。由于 LlamaFirewall 1.0.3 的包依赖要求，镜像保留 CPU 版 PyTorch 和 Transformers，但运行时只初始化 CodeShield、Hidden ASCII 和 Regex，不加载模型。Java 应用镜像不包含这些 Python 依赖。启动 Java 应用时单独设置 `SAAS_SECURITY_LLAMA_FIREWALL_*` 指向 `http://localhost:18082`。生产资源规格由企业部署侧决定，且不得由应用进程管理该容器；平台只将 URL 切换到企业本地化部署的服务地址。

Java 到真实服务的协议可以使用同一容器执行 live test：

```bash
LLAMA_FIREWALL_LIVE_URL=http://localhost:18082 \
LLAMA_FIREWALL_LIVE_TOKEN=<local-token> \
mvn -Djacoco.skip=true -pl agentscope-saas/agentscope-saas-app \
  -Dtest=LlamaFirewallLiveIntegrationTest test
```

## 7. 安全与运维要求

1. 生产环境必须设置强随机 token，并通过网络策略限制为 Runtime 到扫描服务的单向访问。
2. 生产 LlamaFirewall 服务必须由独立服务团队负责扫描器、镜像、容量、健康检查和升级，平台不得管理其容器。
3. 双方日志和审计均不记录原始输入、工具输出、密钥或完整用户文件，只记录阶段、扫描器、分数、脱敏原因和关联运行标识。
4. 超出扫描长度上限不能静默截断放行，而是进入配置的故障策略。
5. 扫描服务必须强制认证、限制并发并提供可观测性；超时和过载由 Java Runtime 按故障策略处理。
6. 安全服务升级 CodeShield 或其他扫描器前，必须完成企业中文语料的误报、漏报、吞吐和 P99 延迟基线测试。
7. 生产环境应对熔断打开和 `LOCAL_GUARD_ONLY` 降级设置告警；降级保证可用性，不代表外部内容扫描仍然有效。
