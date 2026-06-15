# 07 · 模型网关（Java，内网 LLM Gateway）

> 私有化模型部署在企业内网。模型网关是**密钥收口、用量管控、统一路由**的关键点。**计量服务于配额与审计，不计费。**

## 1. 为什么需要模型网关

agentscope-java 的 `ChatModel` 可直连模型后端，但多用户私有化下需统一收口：密钥集中托管、用量统一计量、按组织配额准入、屏蔽多后端差异、缓存/限流/审计在一处。运行时的 `model` 指向网关（OpenAI 兼容端点），而非直连。

## 2. 网关架构

```
HarnessAgent ──(OpenAI兼容, 带 org/user/session header)──▶ 模型网关
   │
   ▼
[1]鉴权&租户解析 → [2]配额准入(Valkey计数) → [3]路由&密钥注入
  → [4]调用后端(内网vLLM/Ollama/企业模型) → [5]流式回传
  → [6]计量(token) → 异步写 usage_records（配额/审计，非账单）
```

| 阶段 | 职责 |
|------|------|
| 鉴权 | 校验内部调用令牌，提取 `org_id/user_id/session_id` |
| 配额准入 | 查组织 token 余额 + 速率，超额拒绝/降级（[05](./05-resource-management.md)） |
| 路由 | 按组织可用模型 + Agent 选择 + 容灾选后端 |
| 密钥注入 | 从 Vault/KMS 解密对应后端密钥；密钥永不离开网关 |
| 调用后端 | 适配各后端协议（OpenAI 兼容 / vLLM / Ollama） |
| 计量 | 统计 token，写 `usage_records` |

## 3. 模型后端（私有化优先内网）

| 类型 | 说明 | 适用 |
|------|------|------|
| 内网自建推理（首选） | vLLM / Ollama / TGI 部署 Qwen 等，GPU 在内网 | 数据不出域 |
| 企业自有模型 | 已有模型服务（昇腾/自研） | 已有 AI 基建 |
| 外部厂商（可选） | 经网关代理，由管理员策略控制 | 非敏感、能力补充 |

密钥经 **Vault/KMS** 加密，按 `org_id` 分区存 `credentials` 表，解密只在网关内存。

## 4. 实现（Spring）

```java
// agentscope-saas-core: 网关客户端，作为 ChatModel 注入 HarnessAgent
@Component
public class GatewayChatModel implements ChatModel {
    public Flux<ChatResponse> stream(ChatRequest req, RuntimeContext rc) {
        TenantContext tc = rc.get(TenantContext.class);
        return webClient.post().uri(gatewayUrl + "/v1/chat/completions")
            .header("X-Org-Id", tc.orgId()).header("X-User-Id", tc.userId())
            .bodyValue(req).retrieve().bodyToFlux(ChatResponse.class);
    }
}

// 网关侧：配额准入 + 密钥注入 + 计量
@PostMapping("/v1/chat/completions")
public Flux<ChatChunk> completions(@RequestHeader("X-Org-Id") String orgId, ...) {
    if (!quotaService.tryConsume(orgId, "llm_tokens", estimate)) throw new QuotaExceeded();
    var creds = credentialService.decrypt(orgId, route.backend());   // Vault
    return backendClient.stream(route, creds, req)
        .doOnComplete(() -> usageService.record(orgId, "llm_tokens", actualTokens));
}
```

## 5. 计量（非计费）

### 5.1 采集点
- **LLM**：网关每次调用记 `(org,user,session,model,prompt/completion/cached tokens,ts)`，由 `AgentEvent` 的 token 统计或后端 usage 提供；
- **沙箱**：`SandboxManager` 分配/回收时记时长 → vCPU·分钟；
- **存储**：定期快照各组织占用 → GB。

### 5.2 管道
```
采集点 → 埋点 → (RocketMQ / PG) → 聚合
  → 实时计数(Valkey：配额准入) + 持久明细(usage_records, 按月分区)
  → 物化视图汇总 → 看板 & 审计
```

### 5.3 用途：配额准入、资源治理（按 org/部门/user/model 下钻）、审计、容量规划。**不生成账单。**

## 6. 路由与降级治理（管理面）
- 可用模型清单、各 Agent 默认模型；
- 主备容灾（后端 A 故障 → B）、按任务类型选模型；
- 配额吃紧自动降级低成本模型 / 降并发；
- 可选缓存。

> 配额维度见 [05](./05-resource-management.md)；审计见 [08](./08-channels-skills-security.md)。
