[中文贡献指南](CONTRIBUTING_zh.md)

# Contributing to Chugou

Thank you for contributing to Chugou, the enterprise private-deployment AI assistant platform built on AgentScope Java.

This repository contains both the upstream AgentScope Java foundation and the Chugou enterprise platform. Contributions must preserve the boundary between reusable framework capabilities and product-specific behavior.

## Before You Start

1. Search the [agentscope-saas-harness issues](https://github.com/HeliosH/agentscope-saas-harness/issues) for related work.
2. Open an issue before implementing a large feature, schema redesign, public API break, or runtime architecture change.
3. Keep each change focused. Do not combine unrelated refactoring, generated files, or formatting churn with a feature or fix.
4. Never commit credentials, access tokens, private model endpoints, customer data, or local environment files.

## Development Environment

Required tools:

- JDK 17 or newer
- Maven 3.9 or newer
- Node.js 20 and npm 10 for frontend work
- Docker Desktop or Docker Engine for middleware and sandbox integration tests

Start the recommended local environment with OpenSandbox:

```bash
./agentscope-saas/agentscope-saas-app/scripts/start-opensandbox-local.sh
```

Stop the application and its local dependencies with:

```bash
./agentscope-saas/agentscope-saas-app/scripts/stop-opensandbox-local.sh
```

See [README.md](README.md) for architecture, deployment, and configuration details.

## Repository Boundaries

Use the existing module ownership when placing code:

| Area | Responsibility |
| --- | --- |
| `agentscope-core` and `agentscope-extensions` | Reusable AgentScope framework and integration capabilities |
| `agentscope-harness` | General-purpose agent planning and execution runtime |
| `agentscope-saas-domain` | Domain models and repository contracts without infrastructure dependencies |
| `agentscope-saas-core` | Enterprise application services and use cases |
| `agentscope-saas-orchestration` | Task planning, recovery, context, and multi-agent orchestration |
| `agentscope-saas-dal` | MyBatis persistence implementations and database mappings |
| `agentscope-saas-storage` | Object storage and file persistence |
| `agentscope-saas-sandbox` | Provider-neutral sandbox lifecycle and provider adapters |
| `agentscope-saas-app` | Spring Boot assembly, HTTP APIs, configuration, and React frontend |

The following architecture rules are mandatory:

- Keep controllers thin; business behavior belongs in application or domain services.
- Keep domain modules independent from Spring, MyBatis, storage SDKs, and sandbox vendors.
- Use repository interfaces at domain boundaries and MyBatis in the DAL. Do not introduce direct JDBC access into application services.
- Keep sandbox behavior provider-neutral. Provider selection is a deployment setting, not a user-facing runtime choice.
- Preserve tenant and user isolation in every query, cache key, object key, task, and sandbox workspace.
- Parent and child agents participating in one task must use the task's shared sandbox unless an explicit isolation requirement is documented.
- PostgreSQL is the source of truth for business records; Redis is ephemeral runtime state; MinIO/S3 stores object bytes; Mem0 is an optional memory projection.

## Implementing Changes

### Backend

- Add tests for changed behavior, including failure and recovery paths where applicable.
- Keep reactive flows non-blocking. Do not call blocking APIs on request or event-loop threads.
- Preserve idempotency for task execution, retries, event delivery, and artifact publication.
- Update configuration metadata and examples when adding environment variables or feature flags.

### Database

- Add append-only Flyway migrations; never edit a migration that may already have been applied.
- Keep PostgreSQL and H2 development schemas behaviorally aligned where both are supported.
- Include tenant predicates and suitable indexes for tenant-scoped reads.
- Do not store file contents, complete conversations, or unbounded event histories in a single database field.

### Frontend

- Follow the existing Chugou visual system and component patterns.
- Cover loading, empty, error, permission-denied, and long-content states.
- Do not expose provider-specific sandbox controls to end users.
- Update API types and user-facing documentation with contract changes.

### Security and Integrations

- External security services such as LlamaFirewall must remain optional and fail according to the documented fallback and circuit-breaker policy.
- Validate file paths, tool arguments, outbound destinations, and tenant ownership at trust boundaries.
- Redact credentials and sensitive request data from logs, audit details, and test fixtures.
- Mock external model, MCP, storage, and sandbox services in unit tests. Live tests must be explicitly enabled.

## Verification

Run the smallest relevant test set while developing, then the broader gate required by the change.

Java formatting:

```bash
mvn spotless:check
```

Enterprise platform tests:

```bash
mvn -pl agentscope-saas/agentscope-saas-app -am test
```

Full repository tests when shared AgentScope or Harness code changes:

```bash
mvn test
```

Frontend validation:

```bash
cd agentscope-saas/agentscope-saas-app/frontend
npm ci
npm run lint
npm run build
```

OpenSandbox end-to-end gate for runtime, sandbox, file, or artifact changes:

```bash
./agentscope-saas/agentscope-saas-app/scripts/start-opensandbox-local.sh --smoke
```

If an external dependency prevents a required test, state exactly what was not run and why in the pull request.

## Documentation

Update documentation in the same change when behavior, configuration, architecture, or operations change:

- Update [README.md](README.md) for user-visible setup or capability changes.
- Update `docs/enterprise-platform-java/` for architecture and operating decisions.
- Keep examples free of real credentials and environment-specific internal addresses.
- Document defaults, fallback behavior, and rollout or rollback considerations for new integrations.

## Commit Messages

Use [Conventional Commits](https://www.conventionalcommits.org/):

```text
<type>(<scope>): <subject>
```

Common types are `feat`, `fix`, `docs`, `refactor`, `test`, `perf`, `ci`, and `chore`.

Examples:

```text
feat(models): add tenant model availability policy
fix(sandbox): preserve workspace ownership for subagents
docs(readme): document private deployment topology
refactor(dal): move task persistence to MyBatis mapper
```

## Pull Requests

A pull request should include:

- The problem and intended outcome
- The architectural impact and important tradeoffs
- Database, configuration, compatibility, and security impact
- Tests run and their results
- Screenshots for visible UI changes
- Rollout and rollback notes for operationally significant changes

Before requesting review, ensure the diff contains only intended files, generated artifacts are excluded, documentation is current, and all applicable checks pass.

## Upstream AgentScope Changes

Changes under the reusable AgentScope modules should remain generally useful and backward compatible. Keep Chugou tenant policy, product UI, enterprise persistence, and deployment-specific behavior in `agentscope-saas` unless the capability is genuinely framework-level.

For upstream AgentScope usage and contribution rules, refer to the [AgentScope Java project](https://github.com/agentscope-ai/agentscope-java).
