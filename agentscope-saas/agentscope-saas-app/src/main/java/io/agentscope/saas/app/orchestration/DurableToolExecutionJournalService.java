/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolContractSnapshot;
import io.agentscope.core.tool.ToolExecutionJournal;
import io.agentscope.core.tool.ToolLeaseLostException;
import io.agentscope.core.tool.ToolRetrySafety;
import io.agentscope.saas.domain.orchestration.ToolOperation;
import io.agentscope.saas.domain.orchestration.ToolOperationRepository;
import io.agentscope.saas.domain.orchestration.ToolReconciliationRepository.ClaimAuthority;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/** Short administrative transactions around external tool execution. */
@Service
public class DurableToolExecutionJournalService {
    private final ToolOperationRepository repository;
    private final TransactionOperations transactions;
    private final ObjectMapper objectMapper;
    private io.agentscope.saas.domain.orchestration.ToolReconciliationRepository reconciliations;

    @org.springframework.beans.factory.annotation.Autowired
    void configureReconciliations(
            io.agentscope.saas.domain.orchestration.ToolReconciliationRepository repository) {
        reconciliations = repository;
    }

    public DurableToolExecutionJournalService(
            ToolOperationRepository repository,
            @Qualifier("adminTransactionOperations") TransactionOperations transactions,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    public ClaimedOperation prepare(
            UUID orgId, UUID runId, ToolExecutionJournal.Invocation invocation) {
        ClaimedOperation claimed =
                transactions.execute(status -> prepareInTransaction(orgId, runId, invocation));
        if (claimed == null)
            throw new IllegalStateException("Tool journal transaction returned null");
        return claimed;
    }

    private ClaimedOperation prepareInTransaction(
            UUID orgId, UUID runId, ToolExecutionJournal.Invocation invocation) {
        OffsetDateTime now = OffsetDateTime.now();
        UUID invocationId = uuid(invocation.invocationId(), "invocationId");
        ToolExecutionJournal.Invocation input = invocation;
        UUID taskId = identityUuid(input, StepIdentityPart.TASK);
        UUID agentRunId = identityUuid(input, StepIdentityPart.AGENT_RUN);
        UUID attemptId = identityUuid(input, StepIdentityPart.ATTEMPT);
        if (!repository.lockExecutionRun(orgId, runId, now)) {
            throw leaseLost(input);
        }
        var existing = existingOperation(orgId, runId, input);
        if (existing.isEmpty()) {
            if (input.toolContract() == null)
                throw new IllegalStateException(
                        "A new durable operation requires an executable contract snapshot");
            if (repository.insertClaimed(newOperation(orgId, runId, input)) != 1)
                throw new IllegalStateException("Prepared tool operation could not be created");
            existing = repository.lock(orgId, runId, input.operationId());
        }
        ToolOperation operation =
                existing.orElseThrow(
                        () -> new IllegalStateException("Prepared tool operation missing"));
        now = OffsetDateTime.now();
        if (!repository.executionScopeActive(
                orgId, runId, taskId, agentRunId, attemptId, leaseOwner(input), now))
            throw leaseLost(input);
        if (operation.toolContractHash() == null || operation.logicalOriginId() == null)
            return new ClaimedOperation(
                    operation.id(),
                    ToolExecutionJournal.PrepareResult.reconcile(
                            "Legacy operation has no verified tool contract or logical origin;"
                                    + " controlled conversion is required"));
        verifySameIntent(operation, input);
        if (!contractMatches(operation, input))
            return new ClaimedOperation(
                    operation.id(),
                    ToolExecutionJournal.PrepareResult.reconcile(
                            "Tool contract changed or cannot be verified for this original"
                                    + " operation"));
        if (!operation.toolContractRecoverable()
                && !("CLAIMED".equals(operation.status())
                        && invocationId.equals(operation.invocationId())))
            return new ClaimedOperation(
                    operation.id(),
                    ToolExecutionJournal.PrepareResult.reconcile(
                            "The remote or mutable tool has no verified implementation version;"
                                    + " automatic inheritance is blocked"));
        if ("CLAIMED".equals(operation.status())
                && claimAuthority(orgId, runId, operation, input, now) == ClaimAuthority.INVALID) {
            if (!repository.blockUndispatchedReplay(
                    operation.id(), orgId, operation.version(), now))
                throw new IllegalStateException("Replay claim changed before it could be fenced");
            appendTerminalOutbox(
                    orgId,
                    operation.id(),
                    input,
                    ToolExecutionJournal.TerminalStatus.OUTCOME_UNKNOWN);
            return new ClaimedOperation(
                    operation.id(),
                    ToolExecutionJournal.PrepareResult.reconcile(
                            "Replay approval cannot transfer to this invocation; record fresh"
                                    + " evidence and approval"));
        }
        if ("SUCCEEDED".equals(operation.status())) {
            if (operation.resultJson() == null) {
                throw new IllegalStateException("Succeeded tool operation has no durable result");
            }
            return new ClaimedOperation(
                    operation.id(),
                    ToolExecutionJournal.PrepareResult.reuse(
                            fromJson(operation.resultJson(), ToolResultBlock.class)));
        }
        if ("OUTCOME_UNKNOWN".equals(operation.status()) && reconciliations != null) {
            var resolution = reconciliations.executionResolution(orgId, runId, operation.id());
            if (resolution.isPresent()) {
                var confirmed = resolution.get();
                if (confirmed.operationVersion() != operation.version())
                    return new ClaimedOperation(
                            operation.id(),
                            ToolExecutionJournal.PrepareResult.reconcile(
                                    "External evidence no longer matches this operation version;"
                                            + " record fresh evidence and approval"));
                if ("CONFIRMED_SUCCESS".equals(confirmed.decision())) {
                    var result = fromJson(confirmed.resultJson(), ToolResultBlock.class);
                    if (!operation.toolCallId().equals(result.getId())
                            || !operation.toolName().equals(result.getName()))
                        throw new IllegalStateException(
                                "Reconciliation result does not match the original operation");
                    return new ClaimedOperation(
                            operation.id(), ToolExecutionJournal.PrepareResult.reuse(result));
                }
                if ("CONFIRMED_NOT_PERFORMED".equals(confirmed.decision())
                        && confirmed.replayApproved()
                        && operation.retrySafety().equals(input.retrySafety().name())
                        && reconciliations.consumeReplay(
                                orgId,
                                runId,
                                operation.id(),
                                operation.version(),
                                confirmed.id(),
                                invocationId,
                                input.toolName(),
                                input.toolCallId(),
                                input.inputHash(),
                                now)) {
                    reclaim(
                            operation.id(),
                            operation.version(),
                            orgId,
                            invocationId,
                            attemptId,
                            input,
                            now);
                    return new ClaimedOperation(
                            operation.id(), ToolExecutionJournal.PrepareResult.execute());
                }
                return new ClaimedOperation(
                        operation.id(),
                        ToolExecutionJournal.PrepareResult.reconcile(
                                "External outcome requires current evidence and an explicit,"
                                        + " unexpired replay approval"));
            }
        }
        if ("CLAIMED".equals(operation.status()) && invocationId.equals(operation.invocationId())) {
            return new ClaimedOperation(
                    operation.id(), ToolExecutionJournal.PrepareResult.execute());
        }
        if ("CLAIMED".equals(operation.status())) {
            reclaim(
                    operation.id(),
                    operation.version(),
                    orgId,
                    invocationId,
                    attemptId,
                    input,
                    now);
            return new ClaimedOperation(
                    operation.id(), ToolExecutionJournal.PrepareResult.execute());
        }
        if (retryAllowed(operation, input)
                && ("FAILED".equals(operation.status())
                        || "CANCELLED".equals(operation.status())
                        || "OUTCOME_UNKNOWN".equals(operation.status()))) {
            reclaim(
                    operation.id(),
                    operation.version(),
                    orgId,
                    invocationId,
                    attemptId,
                    input,
                    now);
            return new ClaimedOperation(
                    operation.id(), ToolExecutionJournal.PrepareResult.execute());
        }
        if ("RUNNING".equals(operation.status())
                && repository.markOutcomeUnknownIfAttemptInactive(
                        operation.id(),
                        orgId,
                        operation.version(),
                        ToolLeaseLostException.class.getName(),
                        "The previous execution lease expired before a terminal result was"
                                + " committed",
                        now)) {
            appendTerminalOutbox(
                    orgId,
                    operation.id(),
                    input,
                    ToolExecutionJournal.TerminalStatus.OUTCOME_UNKNOWN);
            if (retryAllowed(operation, input)) {
                reclaim(
                        operation.id(),
                        operation.version() + 1,
                        orgId,
                        invocationId,
                        attemptId,
                        input,
                        now);
                return new ClaimedOperation(
                        operation.id(), ToolExecutionJournal.PrepareResult.execute());
            }
        }
        return new ClaimedOperation(
                operation.id(),
                ToolExecutionJournal.PrepareResult.reconcile(
                        "Tool operation "
                                + input.operationId()
                                + " is "
                                + operation.status()
                                + "; verify its external outcome before retrying"));
    }

    private ToolOperationRepository.NewOperation newOperation(
            UUID orgId, UUID runId, ToolExecutionJournal.Invocation invocation) {
        OffsetDateTime now = OffsetDateTime.now();
        return new ToolOperationRepository.NewOperation(
                UUID.randomUUID(),
                orgId,
                runId,
                identityUuid(invocation, StepIdentityPart.TASK),
                identityUuid(invocation, StepIdentityPart.AGENT_RUN),
                identityUuid(invocation, StepIdentityPart.ATTEMPT),
                invocation.operationId(),
                uuid(invocation.invocationId(), "invocationId"),
                invocation.stepId(),
                invocation.toolCallId(),
                invocation.toolName(),
                invocation.inputHash(),
                json(invocation.input()),
                invocation.retrySafety().name(),
                now,
                invocation.toolContract() == null ? null : invocation.toolContract().hash(),
                invocation.toolContract() == null ? null : json(invocation.toolContract()),
                invocation.toolContract() != null && invocation.toolContract().recoverable(),
                invocation.logicalOriginId());
    }

    private java.util.Optional<ToolOperation> existingOperation(
            UUID org, UUID run, ToolExecutionJournal.Invocation input) {
        var existing = repository.lock(org, run, input.operationId());
        UUID caller = identityUuid(input, StepIdentityPart.AGENT_RUN);
        if (existing.isEmpty() && caller != null)
            existing =
                    repository.lockCallerOperation(
                            org, run, caller, input.logicalOriginId(), input.toolCallId());
        if (existing.isEmpty()) {
            var legacy = repository.lock(org, run, run + ":" + input.toolCallId());
            if (legacy.isPresent()
                    && (legacy.get().agentRunId() == null
                            || Objects.equals(caller, legacy.get().agentRunId()))
                    && (legacy.get().logicalOriginId() == null
                            || input.logicalOriginId().equals(legacy.get().logicalOriginId())))
                existing = legacy;
        }
        return existing;
    }

    private boolean contractMatches(
            ToolOperation operation, ToolExecutionJournal.Invocation input) {
        if (input.toolContract() == null
                || !operation.toolContractHash().equals(input.toolContract().hash())) return false;
        var stored = fromJson(operation.toolContractJson(), ToolContractSnapshot.class);
        return stored.hash().equals(operation.toolContractHash())
                && stored.equals(input.toolContract())
                && operation.toolContractRecoverable() == stored.recoverable();
    }

    public void markRunning(
            UUID orgId,
            UUID runId,
            UUID operationId,
            UUID invocationId,
            ToolExecutionJournal.Invocation invocation) {
        OffsetDateTime now = OffsetDateTime.now();
        Boolean updated =
                transactions.execute(
                        status -> {
                            if (!repository.lockExecutionRun(orgId, runId, now))
                                throw leaseLost(invocation);
                            OffsetDateTime dispatchedAt = OffsetDateTime.now();
                            if (!executionScopeActive(orgId, runId, invocation, dispatchedAt))
                                throw leaseLost(invocation);
                            var operation =
                                    existingOperation(orgId, runId, invocation).orElseThrow();
                            verifySameIntent(operation, invocation);
                            if (!operation.id().equals(operationId)
                                    || !contractMatches(operation, invocation))
                                throw new IllegalStateException(
                                        "Dispatch does not match the original executable contract");
                            if (!executionScopeActive(
                                    orgId, runId, invocation, OffsetDateTime.now()))
                                throw leaseLost(invocation);
                            if ("CLAIMED".equals(operation.status())
                                    && claimAuthority(
                                                    orgId,
                                                    runId,
                                                    operation,
                                                    invocation,
                                                    OffsetDateTime.now())
                                            == ClaimAuthority.INVALID)
                                throw new IllegalStateException(
                                        "Replay approval is no longer valid for dispatch");
                            return repository.markRunning(
                                    operationId,
                                    orgId,
                                    invocationId,
                                    leaseOwner(invocation),
                                    OffsetDateTime.now());
                        });
        if (!Boolean.TRUE.equals(updated)) {
            if (!executionScopeActive(orgId, runId, invocation, now)) {
                throw leaseLost(invocation);
            }
            throw new IllegalStateException("Tool operation was not in the claimed state");
        }
    }

    public void complete(
            UUID orgId,
            UUID runId,
            UUID operationId,
            UUID invocationId,
            ToolExecutionJournal.Invocation invocation,
            ToolExecutionJournal.TerminalStatus terminal,
            ToolResultBlock result,
            Throwable error) {
        CompletionDecision decision =
                transactions.execute(
                        transaction -> {
                            OffsetDateTime now = OffsetDateTime.now();
                            String resultJson = result == null ? null : json(result);
                            String errorType = error == null ? null : error.getClass().getName();
                            String errorMessage =
                                    error == null ? null : truncate(error.getMessage(), 2000);
                            if (!repository.complete(
                                    operationId,
                                    orgId,
                                    invocationId,
                                    terminal.name(),
                                    resultJson,
                                    errorType,
                                    errorMessage,
                                    leaseOwner(invocation),
                                    now)) {
                                ToolOperation operation =
                                        repository
                                                .lockCallerOperation(
                                                        orgId,
                                                        runId,
                                                        identityUuid(
                                                                invocation,
                                                                StepIdentityPart.AGENT_RUN),
                                                        invocation.logicalOriginId(),
                                                        invocation.toolCallId())
                                                .orElseThrow(
                                                        () ->
                                                                new IllegalStateException(
                                                                        "Tool operation disappeared"
                                                                                + " during"
                                                                                + " completion"));
                                if (operation.id().equals(operationId)
                                        && operation.invocationId().equals(invocationId)
                                        && sameTerminalOutcome(
                                                operation,
                                                terminal,
                                                resultJson,
                                                errorType,
                                                errorMessage)) {
                                    return CompletionDecision.ALREADY_COMMITTED;
                                }
                                if (operation.id().equals(operationId)
                                        && operation.invocationId().equals(invocationId)
                                        && repository.markOutcomeUnknownIfAttemptInactive(
                                                operationId,
                                                orgId,
                                                operation.version(),
                                                ToolLeaseLostException.class.getName(),
                                                "Execution lease expired before the tool result was"
                                                        + " committed",
                                                now)) {
                                    appendTerminalOutbox(
                                            orgId,
                                            operationId,
                                            invocation,
                                            ToolExecutionJournal.TerminalStatus.OUTCOME_UNKNOWN);
                                    return CompletionDecision.LEASE_LOST;
                                }
                                return CompletionDecision.REJECTED;
                            }
                            appendTerminalOutbox(orgId, operationId, invocation, terminal);
                            return CompletionDecision.COMMITTED;
                        });
        if (decision == CompletionDecision.LEASE_LOST) {
            throw leaseLost(invocation);
        }
        if (decision != CompletionDecision.COMMITTED
                && decision != CompletionDecision.ALREADY_COMMITTED) {
            throw new IllegalStateException("Tool operation terminal transition rejected");
        }
    }

    private void reclaim(
            UUID operationId,
            long expectedVersion,
            UUID orgId,
            UUID invocationId,
            UUID attemptId,
            ToolExecutionJournal.Invocation invocation,
            OffsetDateTime now) {
        if (!repository.reclaim(
                operationId,
                orgId,
                expectedVersion,
                invocationId,
                attemptId,
                invocation.stepId(),
                invocation.retrySafety().name(),
                now)) {
            throw new IllegalStateException("Tool operation reclaim lost an optimistic race");
        }
    }

    private boolean executionScopeActive(
            UUID orgId,
            UUID runId,
            ToolExecutionJournal.Invocation invocation,
            OffsetDateTime now) {
        return repository.executionScopeActive(
                orgId,
                runId,
                identityUuid(invocation, StepIdentityPart.TASK),
                identityUuid(invocation, StepIdentityPart.AGENT_RUN),
                identityUuid(invocation, StepIdentityPart.ATTEMPT),
                leaseOwner(invocation),
                now);
    }

    private ClaimAuthority claimAuthority(
            UUID orgId,
            UUID runId,
            ToolOperation operation,
            ToolExecutionJournal.Invocation invocation,
            OffsetDateTime now) {
        if (reconciliations == null) return ClaimAuthority.NOT_REQUIRED;
        return reconciliations.claimAuthority(
                orgId,
                runId,
                operation.id(),
                operation.version(),
                uuid(invocation.invocationId(), "invocationId"),
                invocation.toolName(),
                invocation.toolCallId(),
                invocation.inputHash(),
                now);
    }

    private void appendTerminalOutbox(
            UUID orgId,
            UUID operationId,
            ToolExecutionJournal.Invocation invocation,
            ToolExecutionJournal.TerminalStatus terminal) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operationId", invocation.operationId());
        payload.put("invocationId", invocation.invocationId());
        payload.put("stepId", invocation.stepId());
        payload.put("toolCallId", invocation.toolCallId());
        payload.put("toolName", invocation.toolName());
        payload.put("status", terminal.name());
        repository.appendTerminalOutbox(
                UUID.randomUUID(),
                orgId,
                operationId,
                "TOOL_OPERATION_" + terminal.name(),
                json(payload));
    }

    private static boolean retryAllowed(
            ToolOperation operation, ToolExecutionJournal.Invocation invocation) {
        return !ToolRetrySafety.NEVER.name().equals(operation.retrySafety())
                && invocation.retrySafety() != ToolRetrySafety.NEVER;
    }

    private void verifySameIntent(
            ToolOperation operation, ToolExecutionJournal.Invocation invocation) {
        if (!operation.toolName().equals(invocation.toolName())
                || !operation.toolCallId().equals(invocation.toolCallId())
                || !operation.inputHash().equals(invocation.inputHash())
                || !Objects.equals(operation.logicalOriginId(), invocation.logicalOriginId())
                || !Objects.equals(
                        operation.taskId(), identityUuid(invocation, StepIdentityPart.TASK))
                || !Objects.equals(
                        operation.agentRunId(),
                        identityUuid(invocation, StepIdentityPart.AGENT_RUN))) {
            throw new IllegalStateException(
                    "Tool operation key was reused with a different identity, tool, or input");
        }
    }

    private UUID identityUuid(ToolExecutionJournal.Invocation invocation, StepIdentityPart part) {
        if (invocation.identity() == null) return null;
        String value =
                switch (part) {
                    case TASK -> invocation.identity().taskId();
                    case AGENT_RUN -> invocation.identity().agentRunId();
                    case ATTEMPT -> invocation.identity().attemptId();
                };
        return value == null || value.isBlank() ? null : uuid(value, part.name());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize tool journal payload", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception directError) {
            try {
                String nestedJson = objectMapper.readValue(json, String.class);
                return objectMapper.readValue(nestedJson, type);
            } catch (Exception nestedError) {
                directError.addSuppressed(nestedError);
                throw new IllegalStateException("Unable to read durable tool result", directError);
            }
        }
    }

    private boolean sameTerminalOutcome(
            ToolOperation operation,
            ToolExecutionJournal.TerminalStatus terminal,
            String resultJson,
            String errorType,
            String errorMessage) {
        return terminal.name().equals(operation.status())
                && jsonEquals(operation.resultJson(), resultJson)
                && Objects.equals(operation.errorType(), errorType)
                && Objects.equals(operation.errorMessage(), errorMessage);
    }

    private boolean jsonEquals(String left, String right) {
        if (left == null || right == null) return left == null && right == null;
        try {
            return jsonNode(left).equals(jsonNode(right));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to compare durable tool results", error);
        }
    }

    private JsonNode jsonNode(String value) throws Exception {
        JsonNode node = objectMapper.readTree(value);
        return node != null && node.isTextual() ? objectMapper.readTree(node.textValue()) : node;
    }

    private static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(field + " must be a UUID", error);
        }
    }

    private static String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) return value;
        return value.substring(0, limit);
    }

    private static String leaseOwner(ToolExecutionJournal.Invocation invocation) {
        return invocation.executionLease() == null ? null : invocation.executionLease().owner();
    }

    private static ToolLeaseLostException leaseLost(ToolExecutionJournal.Invocation invocation) {
        return new ToolLeaseLostException(
                "Execution lease is no longer valid for tool operation "
                        + invocation.operationId());
    }

    public record ClaimedOperation(
            UUID operationId, ToolExecutionJournal.PrepareResult prepareResult) {}

    private enum CompletionDecision {
        COMMITTED,
        ALREADY_COMMITTED,
        LEASE_LOST,
        REJECTED
    }

    private enum StepIdentityPart {
        TASK,
        AGENT_RUN,
        ATTEMPT
    }
}
