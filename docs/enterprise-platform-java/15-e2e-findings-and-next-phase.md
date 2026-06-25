# 下阶段实施依据（2026-06-21 e2e 验证实况）

> 本文档来自截至 2026-06-21 的实际 e2e 全链路验证,每条结论都有诊断日志/代码 trace 证据。
> 用于下阶段方案设计和任务拆解的依据,避免凭空设计或遗忘已知教训。

## 当前完成度

| 阶段 | 状态 | 提交 |
|------|------|------|
| F1 namespace org 维度 | ✅ | d48e1e1a |
| F2 API 形态 agent 作用域 | ✅ | d48e1e1a |
| F3-S1 MinIO stop 时快照 | ✅ | 4280ab32 |
| F3-S2 热路径实时持久化 | ✅ | 当前工作树(2026-06-25) |
| F4 RLS 纵深隔离 | ✅ | 8102f233→dc094a15(修 3 个传播 bug) |
| F5 控制器 + content_json + JacksonConfig | ✅ | 00dd3d8a + ebbe8e88 |
| F6 paw 前端 fork + JWT 多租户 | ✅ | 83afc663 + fcb3d265 |
| C2 动态 per-user MCP | ✅ | 09e5186a |
| marketplace admin-gate | ✅ | 496351d2 |
| 文件下载端点 + 极简 MCP server + e2e | ✅ | 45e2eb11→fca90731 |

**e2e-full.sh 14/14 PASS**（真 LLM + docker 沙箱 + MCP + 跨沙箱 tar 持久化 + 释放 + admin-gate）。

---

## 一、待修复 Bug（影响功能正确性）

### B1: sandboxes 表 RLS 拒 INSERT → tracking 行丢失（已修）

- **现象**:`SandboxTrackingMiddleware` 在每次 agent call 开始时注册 tracking 行,日志报 `violates row-level security policy for table "sandboxes"`。
- **根因**:`SandboxTrackingMiddleware.onInput` 运行在 Reactor boundedElastic 调度器上,`TenantContextHolder` 的 GUC(`app.current_org`) 没传到该线程。与 F4 @Async 传播 bug 同类——都是 Spring 独立调度器不参与 Reactor Context 传播。
- **代码位置**:
  - `agentscope-saas/agentscope-saas-sandbox/src/main/java/io/agentscope/saas/sandbox/middleware/SandboxTrackingMiddleware.java:70-86`
  - `agentscope-saas/agentscope-saas-sandbox/src/main/java/io/agentscope/saas/sandbox/SandboxBroker.java:88-115`(已修 id,修后暴露 RLS 问题)
- **当前状态**:`SandboxTrackingMiddleware` 已在注册/释放 tracking 行时包裹 tenant org context，并补了 org UUID 防御；不再作为当前 P0 bug 跟踪。
- **剩余风险**:仍需要在真实 PG + Cube/Docker e2e 中检查 tracking 行生命周期和过期标记是否符合运维预期。

### B2: workspace 端点 call 外 500（F3-S2 热路径 gap，已修）

- **现象**:sandbox-on(docker)模式,call 结束后 `GET /workspace/file/download` / `GET /workspace/memory` / `GET /workspace/file` 全部 500:`SandboxConfigurationException: No active sandbox`。
- **根因**:`SandboxBackedFilesystem` 在无 active call context 时拒绝所有文件操作。工作区文件在 docker 容器内,call 结束 sandbox stop(容器销毁),文件只能通过 stop 时 tar 快照持久化——但 download/read 端点不走快照恢复路径。
- **代码位置**:
  - `agentscope-harness/.../filesystem/sandbox/SandboxBackedFilesystem.java`
  - `agentscope-saas/.../workspace/AgentWorkspaceController.java:downloadFile`
- **当前状态**:已在框架侧实现 `SandboxBackedFilesystem` remote fallback；sandbox-on 时 `AgentConfig` 会在存在 BaseStore 时启用 remote projection。call 外 workspace/skill/memory 文件 IO 从 BaseStore 读写，不再依赖 active sandbox。
- **补充修复**:2026-06-25 已补 release 前 workspace tar → remote projection，同步 shell/execute 在沙箱内生成或修改的普通文件，让任务产物在沙箱释放后仍可被网页端读取。
- **剩余风险**:当前 release 投影是 upload-only，避免误删 session mirror 等未物化到沙箱的远程文件；shell 删除/移动导致的 remote 旧文件清理还需要带路径作用域的 reconciliation。

### B3: execute 工具写的文件不进网页 workspace（已收敛为删除同步问题）

- **现象**:LLM 用 `execute` 工具在沙箱运行 `sh -c "..."` 后,`find /workspace -maxdepth 2 -ls` 只看到 `skills/.curator_state.json`,没有 execute 产生的文件。tar 快照不含结果文件。
- **根因**(待确认):可能是 ShellExecuteTool 的 `workingDirectory` 未默认 `/workspace`,或 LLM 传的命令里用 shell 重定向 `> result.txt` 写到 cwd 而非 workspaceRoot。DockerSandbox 的 `doExec` 确有 `-w /workspace`,但 execute 的输出不在工作区。
- **代码位置**:
  - `agentscope-harness/.../tool/ShellExecuteTool.java:46-73`
  - `agentscope-harness/.../sandbox/impl/docker/DockerSandbox.java:136-191(doExec,-w /workspace)`
- **当前状态**:release 前 workspace tar 会投影普通文件到 BaseStore；只要文件最终进入 sandbox workspace tar，网页端在 call 外即可读取。
- **剩余风险**:需要补一个真实 Cube/Docker e2e，覆盖“LLM 调 execute 生成文件→沙箱释放→网页下载文件”。如果实际 execute cwd 仍偏离 `/workspace`，再修 ShellExecuteTool 默认 workingDirectory。

---

## 二、已确认工作的机制（诊断日志证实,不修）

| 机制 | 证据（日志） |
|------|------------|
| **跨沙箱 stop tar + start Branch C restore** | stop:persistenceEnabled=true, persisting 10240 bytes, upload snapshotId=...;start:exists=true, Branch C:restoring from snapshot;第二轮容器内 `cat /workspace/e2e-marker.txt` → `hello-cross-sandbox` |
| **C2 动态 MCP stdio 握手** | `StdioClientTransport: MCP server started` → LLM POST_REASONING tool_call: name=echo → SSE 含 MCP 工具结果 |
| **沙箱释放** | call 后 `docker ps --filter name=agentscope-sandbox` 空 |
| **LTM 记忆自动激活** | `memory_search`/`memory_get` 已自动注册到 Toolkit,`MemoryFlushMiddleware`/`MemoryMaintenanceMiddleware` 自动注册(HarnessAgent builder 默认,AgentConfig 未禁用) |

---

## 三、关键 gotcha（反复踩过的坑,下阶段别重蹈）

### 构建 / 启动

1. **改了 harness 必须 `java -jar` fat jar,不能用 `mvn spring-boot:run`**——`spring-boot:run` 不重新 resolve SNAPSHOT 依赖,新 class 不进 classpath(踩了 3 次)。
2. **`mvn -o -pl agentscope-saas/agentscope-saas-app test-compile` 只编译,不跑 javadoc:jar**;`package` 才触发 javadoc。C2 引入的跨模块 `{@link DynamicMcpMiddleware}` 引用找不到→`-Dmaven.javadoc.skip=true`。
3. **必须从 repo 根 `/Users/family/Documents/workspace/agentscope-java-main` 跑 maven**(记忆:cd 进子模块后 `-pl` 报 "Could not find the selected project in the reactor")。

### Docker 沙箱

4. **`SAAS_SANDBOX_WORKSPACE_ROOT=/workspace` 必设**,空则 DockerSandbox `mkdir ''` 失败→chat 500 `Failed to start workspace`。
5. **沙箱内无网(`--network=none`)不影响 MCP/LLM**:MCP server 是主进程 stdio 子进程,LLM 走主进程,都不经沙箱网络。
6. **ubuntu:latest 裸镜像无 curl/wget/git 等复杂工具**,但有 sh/echo/printf/cat 基本命令(`docker exec` 验证通过)。
7. **doPersistWorkspace** 用 `docker exec ... tar -cf - -C /workspace .` 打包;stop 成功不打印日志(调试时需加 info 诊断日志,已加到 `f0b6bd54`)。

### e2e 脚本

8. **chat prompt 不能含双引号**:`-d "{\"message\":\"$PROMPT\"}"` 里双引号破坏 JSON→400。用 `--data-binary @<(printf '{"message":"%s"}' "$PROMPT")` + prompt 无双引号。
9. **`users` 表无 `status` 列**(看 schema:`id|org_id|email|idp_subject|display_name|password_hash|role|tier|created_at`)。SQL INSERT 别写 status。
10. **查用户 hash 用 superuser `agentscope`**(`-U agentscope`),不用 `app` 角色(被 RLS deny 返空)。admin 植入同样用 superuser 绕 RLS。
11. **e2e DB**:`agentscope_saas_e2e` 库 + `app`/`agentscope` 角色,密码均 `agentscope`。primary 和 admin DataSource 的 URL 必须指向同库(记忆:admin DS 指不同库→Flyway 重复 seed 报 `tier_policies_pkey duplicate`)。
12. **`uuidgen` mac 可用**;找不到时回退 `/proc/sys/kernel/random/uuid`(Linux)。

### 模型 / LLM

13. **DeepSeek base-url 必须带 `/v1`**(`https://api.deepseek.com/v1`)。框架 `DeepSeekFormatter` 示例即如此;`OpenAIChatModel` endpointPath 默认 null(用 `/chat/completions`)。
14. **scripted/stub model 驱动不了 skill/MCP**——必须真 LLM。`ScriptedToolModel` 硬编码只调 `execute` 工具。
15. **LLM 不回显 tool output**(execute cat 的结果在 SSE 里被模型吞了)。跨沙箱文件存活验证不要依赖 LLM 回显,用 `docker exec cat` 直读。

---

## 四、剩余路线图

### 已完成并提交
F1–F6 + C2 MCP + marketplace admin-gate + 文件下载端点 + 极简 MCP server + e2e 验证脚本

### 已完成
- **F3-S2**:sandbox remote projection 已在框架侧落地；call 外 IO 和 release 前 workspace tar 投影均有单测覆盖。

### 延后(低风险,非阻塞)
- **F7**:PermissionEngine tool_guard 精配 + LTM 可选接线

### Phase C 工具补全（未开始）
- ✅ C2 动态 MCP
- ❌ BrowserTool(用户否决,企业内网不访问外网)
- ❌ Coding IDE/LSP(0%,前端编辑器只读)

### Phase D 生产化（未开始）
- ❌ Admin 控制面(`/admin/tenants`、`/admin/sandboxes`、`/admin/usage`、`/admin/audit`)
- ❌ 可观测(OTel TenantTraceMiddleware + Micrometer + Grafana)
- ❌ DegradationManager 优雅降级
- ❌ 离线交付(Docker Compose + Helm)
- ❌ 前端现代栈美化(11-doc shadcn,可选)

### 其他
- **模型网关**:代码已就绪(`SAAS_MODEL_TYPE=gateway`,OpenAI 兼容 base-url),部署时配环境变量即可
- **渠道系统**:用户剔除 IM 渠道(钉钉/飞书/微信),Web(AG-UI)已通过 F6 paw 前端 fork 落地
