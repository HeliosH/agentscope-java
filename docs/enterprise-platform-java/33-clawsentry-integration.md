# ClawSentry 安全网关集成

## 目标

AgentScope SaaS 可以在企业内网中接入 ClawSentry，作为工具执行前的第二道策略判断。接入不改变用户侧交互，部署环境通过配置决定是否启用。

## 执行链路

1. AgentScope 本地 `ToolInputSecurityGuard` 先检查危险命令、敏感路径和明显的凭据外发。
2. 本地检查通过后，`ClawSentryToolSecurityPolicy` 将工具名、参数、租户和会话信息映射为 AHP `pre_action` 事件，调用 ClawSentry `POST /ahp`。
3. `allow` 继续执行，`block` 直接生成拒绝结果，`defer` 进入现有人工确认流程，`modify` 使用网关返回的新参数并再次执行本地检查。
4. 阻断、人工确认、参数改写和网关故障会写入平台现有审计表；原始敏感参数不会写入审计记录，发送给网关的敏感字段会脱敏。

本地权限拒绝的优先级高于 ClawSentry，ClawSentry 不能放宽本地拒绝。策略服务故障默认进入人工确认，可按部署环境改为全部拒绝或仅放行只读工具。

## 配置

```yaml
saas:
  security:
    clawsentry:
      enabled: false
      base-url: http://clawsentry:8080
      api-path: /ahp
      api-token: ${SAAS_SECURITY_CLAWSENTRY_API_TOKEN:}
      decision-timeout-millis: 1500
      decision-tier: L1
      failure-mode: ASK
      audit-enabled: true
```

常用环境变量：

- `SAAS_SECURITY_CLAWSENTRY_ENABLED=true`
- `SAAS_SECURITY_CLAWSENTRY_BASE_URL=http://clawsentry:8080`
- `SAAS_SECURITY_CLAWSENTRY_API_TOKEN=<与 CS_AUTH_TOKEN 相同>`
- `SAAS_SECURITY_CLAWSENTRY_FAILURE_MODE=ASK|DENY|ALLOW_READ_ONLY`

## 本地启动

```bash
cp agentscope-saas/agentscope-saas-app/docker/.env.clawsentry.example \
   agentscope-saas/agentscope-saas-app/docker/.env.clawsentry
```

设置随机 `CS_AUTH_TOKEN` 后执行：

```bash
agentscope-saas/agentscope-saas-app/scripts/start-clawsentry-local.sh
```

停止服务但保留轨迹数据：

```bash
agentscope-saas/agentscope-saas-app/scripts/stop-clawsentry-local.sh
```

网关默认映射到 `http://localhost:18081`。应用本身仍需设置 `SAAS_SECURITY_CLAWSENTRY_ENABLED=true` 和同一个 API token。

## 部署边界

镜像按固定 ClawSentry 源码提交构建，容器使用非 root 用户，轨迹数据库挂载在 `/data`。该 SQLite 数据库只用于 ClawSentry 本地策略轨迹和报表；企业业务数据、用户会话、审批状态和审计主记录仍由平台 PostgreSQL、Redis 和对象存储负责。生产环境应将 ClawSentry 部署为内网独立服务，配置强 token、健康检查、日志采集和持久化卷，并在升级源码提交前完成安全评审。
