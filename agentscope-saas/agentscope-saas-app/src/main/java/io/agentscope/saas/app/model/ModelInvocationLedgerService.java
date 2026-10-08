/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.model;

import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.app.orchestration.OrchestrationGovernanceService;
import io.agentscope.saas.domain.modelinvocation.ModelInvocationRepository;
import io.agentscope.saas.domain.modelinvocation.ModelInvocationRepository.NewInvocation;
import io.agentscope.saas.domain.modelinvocation.ModelInvocationRepository.Scope;
import io.agentscope.saas.domain.modelinvocation.ModelInvocationRepository.Settlement;
import io.agentscope.saas.domain.orchestration.OrchestrationBudget;
import io.agentscope.saas.domain.orchestration.OrchestrationGovernanceRepository;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/** Short admission/settlement transactions. Never holds a database lock during provider IO. */
@Service
public class ModelInvocationLedgerService {
    private static final Logger log = LoggerFactory.getLogger(ModelInvocationLedgerService.class);
    private final ModelInvocationRepository repository;
    private final OrchestrationGovernanceRepository budgets;
    private final OrchestrationGovernanceService governance;
    private final TransactionOperations transactions;
    private final SaasProperties properties;

    public ModelInvocationLedgerService(
            ModelInvocationRepository repository,
            OrchestrationGovernanceRepository budgets,
            OrchestrationGovernanceService governance,
            @Qualifier("adminTransactionOperations") TransactionOperations transactions,
            SaasProperties properties) {
        this.repository = repository;
        this.budgets = budgets;
        this.governance = governance;
        this.transactions = transactions;
        this.properties = properties;
    }

    public record Admission(UUID id, int outputTokens, OffsetDateTime deadline, String rejection) {
        public boolean permitted() {
            return rejection == null;
        }
    }

    public Admission admit(
            Scope scope,
            String purpose,
            String modelId,
            String routeVersion,
            long inputTokens,
            int requestedOutput,
            OffsetDateTime deadline,
            long monthlyTokenQuota) {
        if (!Set.of("REASONING", "COMPACTION", "MEMORY_EXTRACT", "MEMORY_CONSOLIDATE", "VERIFY")
                        .contains(purpose)
                || modelId == null
                || modelId.isBlank()
                || modelId.length() > 128
                || routeVersion == null
                || routeVersion.isBlank()
                || routeVersion.length() > 128
                || inputTokens < 0
                || requestedOutput < 1
                || deadline == null
                || monthlyTokenQuota < 0
                || (scope.runId() != null
                        && (scope.taskId() == null
                                || (scope.agentRunId() == null
                                        && (scope.attemptId() == null
                                                || scope.leaseOwner() == null))))
                || (scope.runId() == null
                        && (scope.taskId() != null
                                || scope.agentRunId() != null
                                || scope.attemptId() != null))) {
            throw new IllegalArgumentException("Invalid model invocation admission");
        }
        return transactions.execute(
                transaction -> {
                    var now = OffsetDateTime.now(ZoneOffset.UTC);
                    var effectiveDeadline = deadline;
                    if (!repository.lockOwner(scope))
                        return rejected("MODEL_INVOCATION_INVALID_OWNER");
                    if (!deadline.isAfter(now) || !repository.activeLease(scope, now))
                        return rejected("MODEL_INVOCATION_SCOPE_EXPIRED");
                    var limits = properties.getModel().getInvocations();
                    var midnight = now.toLocalDate().atStartOfDay().atOffset(ZoneOffset.UTC);
                    var org = repository.dailyUsage(scope, false, midnight, now);
                    var user = repository.dailyUsage(scope, true, midnight, now);
                    if (exhausted(limits.getMaxDailyOrgCalls(), org.calls())
                            || exhausted(limits.getMaxDailyUserCalls(), user.calls()))
                        return rejected("MODEL_INVOCATION_CALL_QUOTA_EXCEEDED");
                    long availableTokens =
                            Math.min(
                                    remaining(limits.getMaxDailyOrgTokens(), org.tokens()),
                                    remaining(limits.getMaxDailyUserTokens(), user.tokens()));
                    if (monthlyTokenQuota > 0) {
                        var month =
                                now.withDayOfMonth(1)
                                        .toLocalDate()
                                        .atStartOfDay()
                                        .atOffset(ZoneOffset.UTC);
                        availableTokens =
                                Math.min(
                                        availableTokens,
                                        remaining(
                                                monthlyTokenQuota,
                                                repository
                                                        .dailyUsage(scope, true, month, now)
                                                        .tokens()));
                    }
                    long availableCost = Long.MAX_VALUE;
                    if (scope.runId() != null) {
                        var decision = preflight(scope);
                        if (!decision.permitted()) return rejected(decision.reason());
                        var budget = lockBudget(scope);
                        if (budget.runDeadline() != null
                                && budget.runDeadline().isBefore(effectiveDeadline))
                            effectiveDeadline = budget.runDeadline();
                        if (budget.taskDeadline() != null
                                && budget.taskDeadline().isBefore(effectiveDeadline))
                            effectiveDeadline = budget.taskDeadline();
                        if (properties.getOrchestration().isBudgetEnforcementEnabled()) {
                            var runPending = repository.runReservations(scope, false, now);
                            var taskPending = repository.runReservations(scope, true, now);
                            availableTokens =
                                    Math.min(
                                            availableTokens,
                                            Math.min(
                                                    remaining(
                                                            budget.runTokenBudget(),
                                                            add(
                                                                    budget.runConsumedTokens(),
                                                                    runPending.tokens())),
                                                    remaining(
                                                            budget.taskTokenBudget(),
                                                            add(
                                                                    budget.taskConsumedTokens(),
                                                                    taskPending.tokens()))));
                            availableCost =
                                    Math.min(
                                            remaining(
                                                    budget.runCostBudget(),
                                                    add(
                                                            budget.runConsumedCost(),
                                                            runPending.costMicros())),
                                            remaining(
                                                    budget.taskCostBudget(),
                                                    add(
                                                            budget.taskConsumedCost(),
                                                            taskPending.costMicros())));
                        }
                    }
                    long capacity = Math.max(0, availableTokens - inputTokens);
                    int output =
                            (int) Math.min(requestedOutput, Math.min(Integer.MAX_VALUE, capacity));
                    // Binary search avoids overflow and preserves the same rounded cost rule as Run
                    // governance.
                    int low = 0, high = output;
                    while (low < high) {
                        int middle = low + (int) (((long) high - low + 1) / 2);
                        if (cost(inputTokens, middle) <= availableCost) low = middle;
                        else high = middle - 1;
                    }
                    output = low;
                    if (output < 1 || !effectiveDeadline.isAfter(now))
                        return rejected("MODEL_INVOCATION_BUDGET_EXCEEDED");
                    if (scope.runId() != null) {
                        var decision = consume(scope, 0, 0, 0, 1);
                        if (!decision.permitted()) return rejected(decision.reason());
                        if (!properties.getOrchestration().isBudgetEnforcementEnabled()) {
                            var budget = lockBudget(scope);
                            budgets.recordUsage(budget, now, 0, 0, 1);
                        }
                    }
                    var id = UUID.randomUUID();
                    repository.insert(
                            new NewInvocation(
                                    id,
                                    scope,
                                    purpose,
                                    modelId,
                                    routeVersion,
                                    add(inputTokens, output),
                                    cost(inputTokens, output),
                                    inputTokens,
                                    effectiveDeadline,
                                    now));
                    repository.recordMetric(scope, "model_calls", 1, modelId);
                    return new Admission(id, output, effectiveDeadline, null);
                });
    }

    /** Repeated settlement is a no-op; receipt, counters and usage projection commit together. */
    public boolean settle(
            Scope scope,
            UUID id,
            String status,
            long input,
            long output,
            long total,
            boolean reported,
            String reason) {
        if (!Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status)
                || input < 0
                || output < 0
                || total < 0
                || (reason != null && reason.length() > 64)) {
            throw new IllegalArgumentException("Invalid model invocation settlement");
        }
        return Boolean.TRUE.equals(
                transactions.execute(
                        transaction -> {
                            if (!repository.lockOwner(scope, false))
                                throw new IllegalStateException("Invocation owner disappeared");
                            var receipt = repository.find(scope.orgId(), id).orElseThrow();
                            if (!receipt.scope().equals(scope))
                                throw new IllegalStateException("Invocation scope mismatch");
                            if (!"STARTED".equals(receipt.status()))
                                return "SUCCEEDED".equals(receipt.status());
                            var now = OffsetDateTime.now(ZoneOffset.UTC);
                            boolean active =
                                    receipt.deadlineAt().isAfter(now)
                                            && repository.activeLease(scope, now);
                            String effectiveStatus =
                                    "SUCCEEDED".equals(status) && !active ? "CANCELLED" : status;
                            String effectiveReason =
                                    !active && reason == null
                                            ? "MODEL_INVOCATION_SCOPE_EXPIRED"
                                            : reason;
                            long tokens =
                                    Math.max(
                                            Math.max(0, total),
                                            add(Math.max(0, input), Math.max(0, output)));
                            long cost = cost(input, output);
                            if (scope.runId() != null) {
                                boolean directAccounting =
                                        !active
                                                || !properties
                                                        .getOrchestration()
                                                        .isBudgetEnforcementEnabled();
                                if (!directAccounting) {
                                    var decision = consume(scope, input, output, tokens, 0);
                                    active = decision.permitted();
                                    if (!active) {
                                        effectiveReason = decision.reason();
                                        if ("SUCCEEDED".equals(effectiveStatus))
                                            effectiveStatus = "FAILED";
                                        directAccounting =
                                                "RUN_NOT_ACTIVE".equals(decision.reason());
                                    }
                                }
                                // Terminal Runs still receive resource facts, but no state
                                // transition is replayed.
                                if (directAccounting) {
                                    var budget = lockBudget(scope);
                                    budgets.recordUsage(budget, now, tokens, cost, 0);
                                }
                            }
                            if (!repository.settle(
                                    new Settlement(
                                            scope.orgId(),
                                            id,
                                            effectiveStatus,
                                            input,
                                            output,
                                            tokens,
                                            cost,
                                            reported ? "REPORTED" : "ESTIMATED",
                                            effectiveReason,
                                            now))) {
                                throw new IllegalStateException(
                                        "Invocation changed while holding its owner lock");
                            }
                            metric(scope, "tokens_input", input, receipt.modelId());
                            metric(scope, "tokens_output", output, receipt.modelId());
                            metric(scope, "tokens_total", tokens, receipt.modelId());
                            metric(scope, "cost_micros", cost, receipt.modelId());
                            return active && "SUCCEEDED".equals(effectiveStatus);
                        }));
    }

    /** Unknown provider usage is conservatively bounded by the admitted reservation, never replayed. */
    public int reconcileExpired(OffsetDateTime before, int batchSize) {
        if (before == null || before.isAfter(OffsetDateTime.now(ZoneOffset.UTC)))
            throw new IllegalArgumentException("Reconciliation cannot settle future invocations");
        int settled = 0;
        for (var candidate : repository.findExpired(before, batchSize)) {
            try {
                boolean changed =
                        Boolean.TRUE.equals(
                                transactions.execute(
                                        transaction -> {
                                            var snapshot =
                                                    repository
                                                            .find(candidate.orgId(), candidate.id())
                                                            .orElse(null);
                                            if (snapshot == null
                                                    || !repository.lockOwner(
                                                            snapshot.scope(), false)) return false;
                                            var receipt =
                                                    repository
                                                            .find(candidate.orgId(), candidate.id())
                                                            .orElseThrow();
                                            if (!"STARTED".equals(receipt.status())
                                                    || !receipt.deadlineAt().isBefore(before))
                                                return false;
                                            long estimatedInput = receipt.estimatedInputTokens();
                                            long estimatedOutput =
                                                    Math.max(
                                                            0,
                                                            receipt.reservedTokens()
                                                                    - estimatedInput);
                                            settle(
                                                    receipt.scope(),
                                                    receipt.id(),
                                                    "FAILED",
                                                    estimatedInput,
                                                    estimatedOutput,
                                                    receipt.reservedTokens(),
                                                    false,
                                                    "MODEL_INVOCATION_ABANDONED");
                                            return true;
                                        }));
                if (changed) settled++;
            } catch (RuntimeException error) {
                log.warn(
                        "Model invocation reconciliation failed for {}: {}",
                        candidate.id(),
                        error.getClass().getSimpleName());
            }
        }
        return settled;
    }

    private OrchestrationBudget lockBudget(Scope scope) {
        return scope.agentRunId() == null
                ? budgets.lockTaskBudget(scope.orgId(), scope.runId(), scope.taskId())
                : budgets.lockBudget(scope.orgId(), scope.runId(), scope.agentRunId());
    }

    private OrchestrationGovernanceService.BudgetDecision preflight(Scope scope) {
        return scope.agentRunId() == null
                ? governance.preflightTask(scope.orgId(), scope.runId(), scope.taskId())
                : governance.preflight(scope.orgId(), scope.runId(), scope.agentRunId());
    }

    private OrchestrationGovernanceService.BudgetDecision consume(
            Scope scope, long input, long output, long total, int calls) {
        return scope.agentRunId() == null
                ? governance.consumeTaskUsage(
                        scope.orgId(), scope.runId(), scope.taskId(), input, output, total, calls)
                : governance.consumeUsage(
                        scope.orgId(),
                        scope.runId(),
                        scope.agentRunId(),
                        input,
                        output,
                        total,
                        calls);
    }

    private void metric(Scope scope, String metric, long value, String modelId) {
        if (value > 0) repository.recordMetric(scope, metric, value, modelId);
    }

    private Admission rejected(String code) {
        return new Admission(null, 0, null, code);
    }

    private static boolean exhausted(Long maximum, long used) {
        return maximum != null && used >= maximum;
    }

    private static long remaining(Long maximum, long used) {
        return maximum == null ? Long.MAX_VALUE : Math.max(0, maximum - Math.max(0, used));
    }

    private static long add(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private long cost(long input, long output) {
        var policy = properties.getOrchestration();
        BigInteger numerator =
                BigInteger.valueOf(Math.max(0, input))
                        .multiply(
                                BigInteger.valueOf(
                                        Math.max(0, policy.getInputTokenCostMicrosPerMillion())))
                        .add(
                                BigInteger.valueOf(Math.max(0, output))
                                        .multiply(
                                                BigInteger.valueOf(
                                                        Math.max(
                                                                0,
                                                                policy
                                                                        .getOutputTokenCostMicrosPerMillion()))));
        return numerator
                .add(BigInteger.valueOf(999999))
                .divide(BigInteger.valueOf(1000000))
                .min(BigInteger.valueOf(Long.MAX_VALUE))
                .longValue();
    }
}
