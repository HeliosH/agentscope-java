/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolExecutionJournal;
import io.agentscope.core.tool.ToolLeaseLostException;
import io.agentscope.core.tool.ToolRetrySafety;
import io.agentscope.saas.domain.orchestration.ToolOperation;
import io.agentscope.saas.domain.orchestration.ToolOperationRepository;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/** Short administrative transactions around external tool execution. */
@Service
public class DurableToolExecutionJournalService {
    private final ToolOperationRepository repository;
    private final TransactionOperations transactions;
    private final ObjectMapper objectMapper;

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
        tryInsert(newOperation(orgId, runId, invocation));
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
        if (!repository.executionScopeActive(
                orgId, runId, taskId, agentRunId, attemptId, leaseOwner(input), now)) {
            throw leaseLost(input);
        }
        ToolOperation operation =
                repository
                        .lock(orgId, runId, input.operationId())
                        .orElseThrow(
                                () -> new IllegalStateException("Prepared tool operation missing"));
        verifySameIntent(operation, input);
        if ("SUCCEEDED".equals(operation.status())) {
            if (operation.resultJson() == null) {
                throw new IllegalStateException("Succeeded tool operation has no durable result");
            }
            return new ClaimedOperation(
                    operation.id(),
                    ToolExecutionJournal.PrepareResult.reuse(
                            fromJson(operation.resultJson(), ToolResultBlock.class)));
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
                now);
    }

    private void tryInsert(ToolOperationRepository.NewOperation candidate) {
        try {
            Integer inserted = transactions.execute(status -> repository.insertClaimed(candidate));
            if (inserted == null || (inserted != 0 && inserted != 1)) {
                throw new IllegalStateException("Unexpected tool operation insert result");
            }
        } catch (DuplicateKeyException ignored) {
            // The unique business key selected a previously prepared operation. Its state is
            // inspected under a row lock in the following transaction.
        }
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
                        status ->
                                repository.markRunning(
                                        operationId,
                                        orgId,
                                        invocationId,
                                        leaseOwner(invocation),
                                        now));
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
                                                .lock(orgId, runId, invocation.operationId())
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
