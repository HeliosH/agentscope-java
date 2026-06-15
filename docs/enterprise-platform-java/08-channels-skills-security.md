# 08 · 渠道 / 技能 / 安全审计（Java）

## 1. 多渠道接入（Channel SPI，需 Java 重建）

QwenPaw 有 17 个 Python 渠道实现，agentscope-java **无直接等价物**，需新建 Channel SPI + 逐个适配器（这是 Java 方案的主要重建工作之一）。渠道保持宿主机运行（不进沙箱）。

### 1.1 SPI
```java
public interface Channel {
    String getType();                       // dingtalk/feishu/telegram...
    Mono<Void> start(ChannelContext ctx);
    Mono<Void> stop();
    Mono<Void> send(ChannelMessage msg);
    boolean isActive();
}
public record ChannelContext(String orgId, String agentId,
                             Map<String,Object> config, MessageRouter router) {}
```

### 1.2 ChannelManager + MessageRouter
```java
public class MessageRouter {
    public Mono<Msg> route(ChannelMessage msg) {
        Agent agent = agentRegistry.resolveAgent(msg.channelType(), msg.channelId());
        RuntimeContext rc = RuntimeContext.builder()
            .sessionId(msg.channelId() + ":" + msg.senderId())
            .userId(resolveBoundUser(msg.senderId()))      // 外部身份↔平台 user 绑定
            .build();
        rc.put(TenantContext.class, tenantResolver.byBinding(msg.channelType(), msg.channelId()));
        return agent.call(Msg.user(msg.content()), rc)
            .flatMap(resp -> channelManager.send(msg.channelType(), msg.channelId(), resp)
                                           .thenReturn(resp));
    }
}
```

### 1.3 渠道优先级与多副本
| 优先级 | 渠道 | 说明 |
|--------|------|------|
| P0 | Web(AG-UI) / Console | starter 现成，零额外开发 |
| P1 | DingTalk / Feishu / Telegram | 企业 + 海外 |
| P2 | Discord / WeChat·WeCom | 社区 + 中国 |
| P3+ | Matrix/Mattermost/QQ/Voice | 按需 |

多副本：按账号哈希分片到固定副本，避免 webhook 重复消费；配额按组织限连接数/消息速率（[05](./05-resource-management.md)）。

## 2. 技能市场（部分复用 + Java 重建）

框架有 Skill 仓库（Git/MySQL/PostgreSQL/Nacos/Classpath 五种来源），但 QwenPaw 的技能系统 + 自演进 + 市场审核需 Java 重建。

```
技能来源（框架 Skill 仓库）── 平台内置 / 组织私有 / 导入第三方
   │
   ├─ 上架审核 + 安全扫描 + 版本管理（新建）
   ▼
组织启用技能 → skills 表(org 维度) → Toolkit 按启用集注册
   │
   ▼
技能执行 → 一律在【用户沙箱】内，受 PermissionEngine 管控，绝不在主进程
```

| 层 | 措施 |
|----|------|
| 上架 | 管理员审核 + 自动扫描（检测危险 OS/网络/文件操作） |
| 分发 | 内置签名校验；第三方标风险等级；私有技能仅本组织可见 |
| 执行 | **仅沙箱内**；声明式权限由 `PermissionEngine` 强制 |
| 审计 | 技能调用、权限触发全量审计 |

自演进生成的技能默认进入"组织私有 + 待扫描"，审核通过且管理员批准后方可启用。

## 3. 安全模型（四层纵深，复用框架）

```
Layer 1: 沙箱隔离（CubeSandbox KVM μVM / DockerSandbox）
   ├─ 每用户独立沙箱，无法访问宿主机/他人沙箱
   └─ 网络隔离，出站仅允许：模型网关 + 内网 MinIO/PyPI + 白名单
Layer 2: PermissionEngine（core 框架，直接复用）
   ├─ PermissionRule: ALLOW/DENY/ASK per tool
   ├─ PermissionMode: DEFAULT/ACCEPT_EDITS/EXPLORE/BYPASS/DONT_ASK
   └─ 每次工具调用运行时决策
Layer 3: API 认证/授权
   ├─ Spring Security + JWT(含 org_id) + RBAC
   ├─ 按 org 限流（RateLimitMiddleware）
   └─ API Key 管理
Layer 4: 数据加密
   ├─ 模型密钥: AES-256-GCM（Vault/KMS）
   ├─ Memory/结构化: PG TDE 或应用级加密
   ├─ 存储: MinIO SSE-S3（AES-256）
   └─ 传输: TLS 1.3
```

### 3.1 工具权限：tool_guard → PermissionEngine
QwenPaw `ToolGuardMixin` 的语义映射到框架 `PermissionEngine`（已核实，5 种模式）。平台把组织安全基线编译为 `PermissionRule`，注入会话：
- 平台默认基线（禁任意外网、限文件路径、危险命令拦截）；
- 组织可在基线内收紧（不可放宽超上限）；
- 高危走 `ASK`（`REQUIRE_USER_CONFIRM` 事件，人工确认）。

### 3.2 网络隔离
```
内网入口(TLS) → SaaS Core Engine
   ├─ Sandbox(user A/B...)   出站仅: 模型网关 + 内网 MinIO/PyPI + 白名单
   ├─ PostgreSQL / Valkey / MinIO（内网）
   └─ 模型网关 → 内网模型
```

### 3.3 提示注入与外泄防护
外部内容标记"数据非指令"；沙箱出口白名单 + 审计；可选 DLP 对输出脱敏。

## 4. 审计与合规

### 4.1 审计日志（`audit_logs`）
记录：谁（actor+org）· 何时 · 对哪个资源 · 做了什么 · 结果。覆盖管理操作、会话与工具调用（含 PermissionEngine 决策/ASK 确认）、渠道消息、技能启用/执行、数据导出/删除、沙箱创建/销毁。按组织隔离、追加写 + 周期归档。

可借助框架 `AgentTraceMiddleware`（OTel）+ 新增 `AuditMiddleware` 在 `onActing` 拦截工具调用落审计。

### 4.2 合规（私有化）
- 数据不出内网（库/对象/向量/密钥/沙箱/模型自部署）；
- 按 `org_id`/`user_id` 级联导出与删除（离职清理）；
- 最小权限 + 全审计 + 强隔离，对齐等保 / ISO 27001 / SOC 2。

> 部署与可观测见 [09-deployment-observability.md](./09-deployment-observability.md)。
