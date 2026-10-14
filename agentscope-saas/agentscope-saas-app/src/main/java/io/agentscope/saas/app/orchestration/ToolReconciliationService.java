/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.StepSnapshot;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.NewEvent;
import io.agentscope.saas.domain.orchestration.RunOrchestrationRepository.NewOutboxMessage;
import io.agentscope.saas.domain.orchestration.ToolReconciliationRepository;
import io.agentscope.saas.domain.orchestration.ToolReconciliationRepository.Decision;
import io.agentscope.saas.domain.orchestration.ToolReconciliationRepository.Operation;
import io.agentscope.saas.domain.orchestration.ToolReconciliationRepository.ReplayPermit;
import io.agentscope.saas.domain.orchestration.ToolReconciliationRepository.Resolution;
import io.agentscope.saas.domain.orchestration.ToolReconciliationRepository.RunScope;
import io.agentscope.saas.domain.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** External evidence changes the effective outcome, never erases the original unknown fact. */
@Service
public class ToolReconciliationService {
    public record Request(
            Long expectedRunVersion,
            Long expectedOperationVersion,
            Long expectedResolutionVersion,
            Decision decision,
            String reason,
            String evidenceRef,
            String resultText,
            boolean approveReplay) {}

    public record Result(
            UUID id,
            UUID runId,
            UUID operationId,
            long sequence,
            String decision,
            boolean replayApproved,
            OffsetDateTime createdAt,
            boolean reused) {}

    public record OperationView(
            UUID id,
            UUID runId,
            UUID userId,
            UUID agentId,
            UUID taskId,
            String toolName,
            String toolCallId,
            String inputHash,
            String retrySafety,
            String status,
            String effectiveStatus,
            long version,
            long runVersion,
            long resolutionVersion,
            OffsetDateTime preparedAt,
            String toolContractHash,
            boolean toolContractRecoverable,
            String logicalOriginId) {}

    public record Page(List<OperationView> items, UUID nextCursor) {}

    public record EvidenceView(
            UUID id,
            long sequence,
            long operationVersion,
            UUID actorId,
            String decision,
            String reason,
            String evidenceRef,
            boolean replayApproved,
            OffsetDateTime createdAt) {}

    private final ToolReconciliationRepository repository;
    private final RunOrchestrationRepository runs;
    private final UserRepository users;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public ToolReconciliationService(
            ToolReconciliationRepository repository,
            RunOrchestrationRepository runs,
            UserRepository users,
            ObjectMapper json,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.repository = repository;
        this.runs = runs;
        this.users = users;
        this.json = json;
        tx = new TransactionTemplate(manager);
        tx.setTimeout(10);
    }

    public Page ownedOperations(TenantContext tenant, UUID agent, UUID run, UUID after, int limit) {
        window(limit);
        return scoped(
                tenant,
                () ->
                        tx.execute(
                                ignored -> {
                                    var source =
                                            repository
                                                    .run(org(tenant), run)
                                                    .orElseThrow(
                                                            ToolReconciliationService::missing);
                                    if (!source.userId().equals(actor(tenant))
                                            || !source.agentId().equals(agent)) throw missing();
                                    requireSession(source);
                                    return page(
                                            repository.operations(
                                                    source.orgId(), run, after, limit + 1),
                                            limit);
                                }));
    }

    public Page organizationOperations(TenantContext tenant, String state, UUID after, int limit) {
        window(limit);
        if (!List.of(
                        "ALL",
                        "OUTCOME_UNKNOWN",
                        "RUNNING",
                        "SUCCEEDED",
                        "FAILED",
                        "CANCELLED",
                        "CLAIMED",
                        "SUSPENDED")
                .contains(state)) throw bad("Invalid operation state");
        return scoped(
                tenant,
                () ->
                        tx.execute(
                                ignored -> {
                                    requireAdministrator(tenant);
                                    return page(
                                            repository.organizationOperations(
                                                    org(tenant), state, after, limit + 1),
                                            limit);
                                }));
    }

    public List<EvidenceView> history(
            TenantContext tenant, UUID run, UUID operation, long after, int limit) {
        window(limit);
        if (after < 0) throw bad("Invalid evidence cursor");
        return scoped(
                tenant,
                () ->
                        tx.execute(
                                ignored -> {
                                    requireAdministrator(tenant);
                                    var source =
                                            repository
                                                    .run(org(tenant), run)
                                                    .orElseThrow(
                                                            ToolReconciliationService::missing);
                                    requireSession(source);
                                    repository
                                            .lockOperation(source.orgId(), run, operation)
                                            .orElseThrow(ToolReconciliationService::missing);
                                    return repository
                                            .history(source.orgId(), run, operation, after, limit)
                                            .stream()
                                            .map(
                                                    r ->
                                                            new EvidenceView(
                                                                    r.id(),
                                                                    r.sequence(),
                                                                    r.operationVersion(),
                                                                    r.actorId(),
                                                                    r.decision(),
                                                                    r.reason(),
                                                                    r.evidenceRef(),
                                                                    r.replayApproved(),
                                                                    r.createdAt()))
                                            .toList();
                                }));
    }

    public Result reconcile(
            TenantContext tenant, UUID run, UUID operation, String commandKey, Request input) {
        String key = required(commandKey, 128, "Idempotency-Key");
        if (input == null
                || input.decision() == null
                || input.expectedRunVersion() == null
                || input.expectedOperationVersion() == null
                || input.expectedResolutionVersion() == null
                || input.expectedRunVersion() < 0
                || input.expectedOperationVersion() < 0
                || input.expectedResolutionVersion() < 0)
            throw bad("Expected versions and decision are required");
        String reason = required(input.reason(), 2000, "reason");
        String reference = required(input.evidenceRef(), 2048, "evidenceRef");
        if (input.approveReplay() && input.decision() != Decision.CONFIRMED_NOT_PERFORMED)
            throw bad("Replay approval requires confirmed non-execution evidence");
        String resultText = input.resultText();
        if (input.decision() == Decision.CONFIRMED_SUCCESS) {
            if (resultText == null
                    || resultText.isBlank()
                    || resultText.getBytes(StandardCharsets.UTF_8).length > 65536)
                throw bad("Confirmed success requires a bounded external result");
        } else if (resultText != null && !resultText.isBlank())
            throw bad("Only confirmed success carries a result");
        var intent = new LinkedHashMap<String, Object>();
        intent.put("run", run);
        intent.put("operation", operation);
        intent.put("expectedRunVersion", input.expectedRunVersion());
        intent.put("expectedOperationVersion", input.expectedOperationVersion());
        intent.put("expectedResolutionVersion", input.expectedResolutionVersion());
        intent.put("decision", input.decision());
        intent.put("reason", reason);
        intent.put("evidenceRef", reference);
        intent.put("resultText", resultText);
        intent.put("approveReplay", input.approveReplay());
        String hash = StepSnapshot.fingerprint(intent);
        return scoped(
                tenant,
                () ->
                        tx.execute(
                                ignored -> {
                                    requireAdministrator(tenant);
                                    var duplicate =
                                            repository.command(org(tenant), actor(tenant), key);
                                    if (duplicate.isPresent()) {
                                        if (!hash.equals(duplicate.get().commandHash()))
                                            throw conflict(
                                                    "Command key was reused with different"
                                                            + " evidence");
                                        return view(duplicate.get(), true);
                                    }
                                    var scope =
                                            repository
                                                    .run(org(tenant), run)
                                                    .orElseThrow(
                                                            ToolReconciliationService::missing);
                                    requireSession(scope);
                                    runs.lockOwnedRun(
                                                    run,
                                                    scope.orgId(),
                                                    scope.userId(),
                                                    scope.agentId())
                                            .orElseThrow(ToolReconciliationService::missing);
                                    scope =
                                            repository
                                                    .run(org(tenant), run)
                                                    .orElseThrow(
                                                            ToolReconciliationService::missing);
                                    if (scope.runVersion() != input.expectedRunVersion())
                                        throw conflict(
                                                "Run changed; refresh before submitting evidence");
                                    var source =
                                            repository
                                                    .lockOperation(scope.orgId(), run, operation)
                                                    .orElseThrow(
                                                            ToolReconciliationService::missing);
                                    var latest = repository.latest(scope.orgId(), run, operation);
                                    long previous = latest.map(Resolution::sequence).orElse(0L);
                                    if (source.version() != input.expectedOperationVersion()
                                            || previous != input.expectedResolutionVersion())
                                        throw conflict(
                                                "Operation or evidence changed; refresh before"
                                                        + " submitting evidence");
                                    if (!"OUTCOME_UNKNOWN".equals(source.status()))
                                        throw conflict("Only unknown operations can be reconciled");
                                    if (input.approveReplay()
                                            && (source.toolContractHash() == null
                                                    || !source.toolContractRecoverable()
                                                    || source.logicalOriginId() == null))
                                        throw conflict(
                                                "Unverified or legacy tool contracts cannot grant"
                                                        + " replay authority");
                                    if (latest.isPresent()
                                            && latest.get().operationVersion() == source.version()
                                            && latest.get()
                                                    .decision()
                                                    .equals(Decision.CONFIRMED_SUCCESS.name()))
                                        throw conflict("The external result was already confirmed");
                                    if (scope.cancelRequested() && input.approveReplay())
                                        throw conflict(
                                                "Cancelled Run cannot grant replay authority");
                                    OffsetDateTime now = OffsetDateTime.now();
                                    String result =
                                            input.decision() == Decision.CONFIRMED_SUCCESS
                                                    ? serialize(
                                                            ToolResultBlock.text(input.resultText())
                                                                    .withIdAndName(
                                                                            source.toolCallId(),
                                                                            source.toolName()))
                                                    : null;
                                    var resolution =
                                            new Resolution(
                                                    UUID.randomUUID(),
                                                    scope.orgId(),
                                                    run,
                                                    operation,
                                                    previous + 1,
                                                    source.version(),
                                                    actor(tenant),
                                                    key,
                                                    hash,
                                                    input.decision().name(),
                                                    reason,
                                                    reference,
                                                    result,
                                                    serialize(source),
                                                    input.approveReplay(),
                                                    now);
                                    repository.append(resolution);
                                    if (input.approveReplay()) {
                                        var expires = now.plusMinutes(10);
                                        var deadline =
                                                runs.findOwnedRun(
                                                                run,
                                                                scope.orgId(),
                                                                scope.userId(),
                                                                scope.agentId())
                                                        .orElseThrow()
                                                        .deadlineAt();
                                        if (deadline != null && deadline.isBefore(expires))
                                            expires = deadline;
                                        if (!expires.isAfter(now))
                                            throw conflict("Run deadline has expired");
                                        repository.approve(
                                                new ReplayPermit(
                                                        UUID.randomUUID(),
                                                        scope.orgId(),
                                                        run,
                                                        operation,
                                                        resolution.id(),
                                                        source.version(),
                                                        actor(tenant),
                                                        source.toolName(),
                                                        source.toolCallId(),
                                                        source.inputHash(),
                                                        expires));
                                    }
                                    String payload =
                                            serialize(
                                                    Map.of(
                                                            "operationId",
                                                            operation,
                                                            "resolutionId",
                                                            resolution.id(),
                                                            "decision",
                                                            resolution.decision(),
                                                            "sequence",
                                                            resolution.sequence(),
                                                            "actorId",
                                                            resolution.actorId(),
                                                            "replayApproved",
                                                            resolution.replayApproved()));
                                    long seq = runs.nextEventSequence(run, scope.orgId(), now);
                                    runs.insertEvent(
                                            new NewEvent(
                                                    UUID.randomUUID(),
                                                    scope.orgId(),
                                                    scope.userId(),
                                                    run,
                                                    source.taskId(),
                                                    seq,
                                                    "TOOL_OPERATION_RECONCILED",
                                                    payload));
                                    runs.insertOutbox(
                                            new NewOutboxMessage(
                                                    UUID.randomUUID(),
                                                    scope.orgId(),
                                                    run,
                                                    "assistant_run",
                                                    "tool_operation_reconciled",
                                                    payload));
                                    requireSession(scope);
                                    return view(resolution, false);
                                }));
    }

    private Page page(List<Operation> rows, int limit) {
        var page = rows.subList(0, Math.min(limit, rows.size()));
        return new Page(
                page.stream()
                        .map(
                                o -> {
                                    var latest = repository.latest(o.orgId(), o.runId(), o.id());
                                    var r =
                                            latest.filter(
                                                    value ->
                                                            value.operationVersion()
                                                                    == o.version());
                                    String effective =
                                            r.map(Resolution::decision).orElse(o.status());
                                    return new OperationView(
                                            o.id(),
                                            o.runId(),
                                            o.userId(),
                                            o.agentId(),
                                            o.taskId(),
                                            o.toolName(),
                                            o.toolCallId(),
                                            o.inputHash(),
                                            o.retrySafety(),
                                            o.status(),
                                            effective,
                                            o.version(),
                                            o.runVersion(),
                                            latest.map(Resolution::sequence).orElse(0L),
                                            o.preparedAt(),
                                            o.toolContractHash(),
                                            o.toolContractRecoverable(),
                                            o.logicalOriginId());
                                })
                        .toList(),
                rows.size() > limit ? page.get(page.size() - 1).id() : null);
    }

    private void requireSession(RunScope scope) {
        long generation =
                runs.lockSessionGeneration(
                                scope.sessionId(), scope.orgId(), scope.userId(), scope.agentId())
                        .orElseThrow(ToolReconciliationService::missing);
        if (generation != scope.sessionGeneration()) throw missing();
    }

    private void requireAdministrator(TenantContext tenant) {
        var user =
                users.lockTenantUser(org(tenant), actor(tenant))
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.FORBIDDEN,
                                                "Administrator authorization revoked"));
        if (!List.of("admin", "platform_admin").contains(user.getRole()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator role required");
    }

    private static Result view(Resolution r, boolean reused) {
        return new Result(
                r.id(),
                r.runId(),
                r.operationId(),
                r.sequence(),
                r.decision(),
                r.replayApproved(),
                r.createdAt(),
                reused);
    }

    private static UUID org(TenantContext t) {
        return UUID.fromString(Objects.requireNonNull(t.orgId()));
    }

    private static UUID actor(TenantContext t) {
        return UUID.fromString(Objects.requireNonNull(t.userId()));
    }

    private static void window(int limit) {
        if (limit < 1 || limit > 100) throw bad("Page size must be between 1 and 100");
    }

    private static String required(String value, int max, String name) {
        if (value == null || value.isBlank() || value.length() > max || value.indexOf('\0') >= 0)
            throw bad(name + " is required and must be bounded");
        return value.trim();
    }

    private String serialize(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to encode reconciliation", e);
        }
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Operation not found");
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static <T> T scoped(TenantContext tenant, Supplier<T> action) {
        String prior = TenantContextHolder.getOrgId();
        TenantContextHolder.setOrgId(org(tenant).toString());
        try {
            return action.get();
        } finally {
            if (prior == null) TenantContextHolder.clear();
            else TenantContextHolder.setOrgId(prior);
        }
    }
}
