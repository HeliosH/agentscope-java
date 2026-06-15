# 04 · 身份与隔离（Java，IAM）

> 复用框架的 `RuntimeContext` + `Middleware` + `NamespaceFactory` 多租户原语，**无需自建 ThreadLocal/ContextVar**。

## 1. 租户模型

三级层次（与 Python 方案一致）：
```
Org (企业/一级组织)  ── 全局安全策略、可用模型、配额上限
  ├── Dept (部门，可选)  ── 共享 Agent/技能/知识库
  └── User (员工)  ── 角色(Owner/Admin/Member/Viewer) + 个人 Agent + 个人沙箱
```

## 2. 框架多租户原语（已核实）

| 构建块 | 位置 | 作用 |
|--------|------|------|
| `RuntimeContext` | core.agent | 每次调用携带 sessionId + userId + typed attributes（`rc.put/get(Class)`） |
| `IsolationScope` | harness.agent | SESSION/USER/AGENT/GLOBAL 四级隔离 |
| `NamespaceFactory` | harness...store | `rc -> List<String>` 动态命名空间（文件路径隔离） |
| `SandboxIsolationKey` | harness.agent.sandbox | 从 RuntimeContext + IsolationScope 解析沙箱槽位 |

> 注：框架无独立 `SessionKey` 类，会话身份经 `RuntimeContext.getSessionId()` 表达。本方案用 `TenantContext` typed attribute 承载租户信息。

## 3. TenantContext + 中间件注入

```java
public record TenantContext(String orgId, String userId, String role,
                            String tier, int maxSandboxes, long tokenQuota) {}

public class TenantContextMiddleware implements MiddlewareBase {
    private final TenantResolver tenantResolver;
    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc,
            AgentInput input, Function<AgentInput, Flux<AgentEvent>> next) {
        TenantContext tc = tenantResolver.resolve(rc.get("Authorization"));
        if (tc != null) rc.put(TenantContext.class, tc);
        return next.apply(input);
    }
}
```

`TenantResolver` 从 JWT 解析（私有化用企业 IdP 签发的 JWT，含 org/user/roles/tier）：
```java
@Component
public class JwtTenantResolver implements TenantResolver {
    public TenantContext resolve(String authHeader) {
        Claims c = parseJwt(authHeader);
        TierPolicy p = tierPolicyRepo.findByTier(c.getTier());  // 配额来自等级，非订阅
        return new TenantContext(c.getOrgId(), c.getUserId(), c.getRole(),
                                 c.getTier(), p.getMaxSandboxes(), p.getTokenQuota());
    }
}
```
> 与 Java PDF 的差异：PDF 用 `subscriptionRepo`（订阅计费），本方案改为 `tierPolicyRepo`（**部门/角色等级，无计费**）。

## 4. 身份认证（替换单口令）

- **企业 SSO（首选）**：Spring Security + OAuth2 Resource Server，对接 OIDC/SAML（Keycloak/AD/飞书钉钉扫码）；
- **本地账号（备选）**：argon2，无 IdP 的小部署；
- **JWT**：含 `org_id/user_id/roles/tier`；
- **API Key**：org 前缀 + 哈希存储，用于内网 API / 渠道 / SDK；
- **渠道身份映射**：外部 open_id ↔ 平台 user 绑定表。

```java
@Configuration @EnableWebFluxSecurity
public class SecurityConfig {
    @Bean SecurityWebFilterChain filterChain(ServerHttpSecurity http) {
        return http.authorizeExchange(ex -> ex
                .pathMatchers("/api/auth/**").permitAll()
                .pathMatchers("/api/admin/**").hasRole("ADMIN")
                .anyExchange().authenticated())
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtDecoder(jwtDecoder())))
            .build();
    }
}
```

## 5. 授权：RBAC + 资源归属

角色矩阵（Owner/Admin/Member/Viewer）与三层鉴权与 Python 方案一致，见 [../enterprise-platform/04-iam-and-isolation.md](../enterprise-platform/04-iam-and-isolation.md) §3。Java 侧：
- 粗粒度：Spring Security `@PreAuthorize` / `hasRole`；
- 资源归属：Service 层校验 `org_id` + `owner_user_id` / `visibility`；
- 工具级：框架 `PermissionEngine`（见 [08](./08-channels-skills-security.md)）。

## 6. 隔离模型（纵深防御）

| 层 | Java 隔离手段 |
|----|--------------|
| 入口 | BFF/Controller 强制从 JWT 取 `org_id`，禁止请求体覆盖 |
| 数据（行级） | 所有表带 `org_id` + PostgreSQL **RLS**；MyBatis/JPA 拦截器强制注入租户条件 |
| 数据（物理，可选） | 高隔离：每 Org 独立 schema |
| 缓存/会话 | `RedisStore`/`RedisSession` key 含 `org_id` |
| 运行时 | `RuntimeContext` 携带 `TenantContext`，全链路传播 |
| 算力沙箱 | `IsolationScope.USER` → 每用户独立 μVM/容器，硬件级隔离 |
| 文件 | `NamespaceFactory` 按 `org/user` 命名空间，`RemoteFilesystem` 隔离 |
| 密钥 | 模型密钥按 `org_id` 加密分区（Vault/KMS，见 [07](./07-model-gateway.md)） |
| 对象存储 | MinIO 按 `{org_id}/` 前缀 + bucket policy |

### 6.1 文件命名空间隔离
```java
NamespaceFactory workspaceNs = rc -> {
    TenantContext tc = rc.get(TenantContext.class);
    return tc == null ? List.of("anonymous")
                      : List.of("workspaces", tc.orgId(), tc.userId());
};
```

### 6.2 沙箱隔离验证（必做测试）
```java
@Test void tenantA_cannot_access_tenantB_sandbox() {
    var rcA = RuntimeContext.builder().userId("org-a:user-a").build();
    var rcB = RuntimeContext.builder().userId("org-b:user-b").build();
    var rA = manager.acquire(ctxA, rcA);
    var rB = manager.acquire(ctxB, rcB);
    assertThat(rA.sandbox().getState().getSessionId())
        .isNotEqualTo(rB.sandbox().getState().getSessionId());
}
```

## 7. 数据驻留与合规（私有化）
- 全部数据在企业内网（库/对象/向量/密钥/沙箱/模型自部署），不出域；
- 全链路审计（见 [08](./08-channels-skills-security.md)）；
- 按 `org_id`/`user_id` 级联导出与删除（离职清理）；
- 架构对齐等保 / ISO 27001 / SOC 2。

> 资源配额与调度见 [05-resource-management.md](./05-resource-management.md)。
