/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.model;

import io.agentscope.core.model.PurposeBindableModel.Purpose;
import io.agentscope.saas.app.admin.AuditService;
import io.agentscope.saas.domain.modelinvocation.ModelInvocationRepository;
import io.agentscope.saas.domain.modelinvocation.ModelInvocationRepository.PurposePolicy;
import io.agentscope.saas.domain.modelinvocation.ModelInvocationRepository.Scope;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.server.ResponseStatusException;

/** Organization-admin use cases; callers authenticate administrators before invoking. */
@Service
public class ModelInvocationPolicyService {
    public record PolicyCommand(
            String modelId,
            int maxInputTokens,
            int maxOutputTokens,
            int timeoutSeconds,
            String version) {}

    private final ModelInvocationRepository repository;
    private final ModelInvocationService invocations;
    private final ModelCatalog catalog;
    private final TransactionOperations transactions;
    private final AuditService audit;

    public ModelInvocationPolicyService(
            ModelInvocationRepository repository,
            ModelInvocationService invocations,
            ModelCatalog catalog,
            @Qualifier("adminTransactionOperations") TransactionOperations transactions,
            AuditService audit) {
        this.repository = repository;
        this.invocations = invocations;
        this.catalog = catalog;
        this.transactions = transactions;
        this.audit = audit;
    }

    public List<PurposePolicy> list(UUID orgId) {
        return Arrays.stream(Purpose.values())
                .filter(p -> p != Purpose.REASONING)
                .map(p -> invocations.policy(orgId, p))
                .toList();
    }

    public PurposePolicy update(
            UUID orgId, UUID actorId, String purposeName, PolicyCommand command) {
        if (actorId == null)
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Valid administrator identity is required");
        Purpose purpose;
        try {
            purpose = Purpose.valueOf(purposeName);
        } catch (IllegalArgumentException e) {
            throw badRequest("Unknown model invocation purpose");
        }
        if (purpose == Purpose.REASONING
                || command == null
                || command.maxInputTokens() < 1
                || command.maxOutputTokens() < 1
                || command.timeoutSeconds() < 1
                || command.timeoutSeconds() > 3600
                || command.version() == null) {
            throw badRequest(
                    "A valid auxiliary purpose, positive limits and current version are required");
        }
        String modelId =
                command.modelId() == null || command.modelId().isBlank()
                        ? null
                        : command.modelId().trim();
        if (modelId != null) catalog.requireOption(orgId, modelId);
        PurposePolicy saved =
                transactions.execute(
                        status -> {
                            if (!repository.lockOwner(
                                    new Scope(orgId, actorId, null, null, null, null, null))) {
                                throw new ResponseStatusException(
                                        HttpStatus.FORBIDDEN,
                                        "Administrator does not belong to organization");
                            }
                            var current = invocations.policy(orgId, purpose);
                            if (!current.version().equals(command.version())) {
                                throw new ResponseStatusException(
                                        HttpStatus.CONFLICT,
                                        "Purpose policy was changed by another administrator");
                            }
                            var policy =
                                    new PurposePolicy(
                                            orgId,
                                            purpose.name(),
                                            modelId,
                                            command.maxInputTokens(),
                                            command.maxOutputTokens(),
                                            command.timeoutSeconds(),
                                            UUID.randomUUID().toString());
                            repository.savePolicy(policy);
                            return policy;
                        });
        audit.record(
                orgId,
                actorId,
                "admin.model.purpose.update",
                "purpose:" + purpose,
                Map.of(
                        "model",
                        modelId == null ? "INHERIT_SELECTED_MODEL" : modelId,
                        "version",
                        saved.version()));
        return saved;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
