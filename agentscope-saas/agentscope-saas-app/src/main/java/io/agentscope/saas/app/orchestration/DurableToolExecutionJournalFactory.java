/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.orchestration;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolExecutionJournal;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/** Creates a tenant/run-scoped core journal backed by the durable orchestration store. */
@Component
public class DurableToolExecutionJournalFactory {
    private final DurableToolExecutionJournalService service;

    public DurableToolExecutionJournalFactory(DurableToolExecutionJournalService service) {
        this.service = service;
    }

    public ToolExecutionJournal create(UUID orgId, UUID runId) {
        return new ScopedJournal(orgId, runId);
    }

    private final class ScopedJournal implements ToolExecutionJournal {
        private final UUID orgId;
        private final UUID runId;
        private final ConcurrentMap<String, UUID> operationRows = new ConcurrentHashMap<>();

        private ScopedJournal(UUID orgId, UUID runId) {
            this.orgId = orgId;
            this.runId = runId;
        }

        @Override
        public PrepareResult prepare(Invocation invocation) {
            if (invocation.identity() == null
                    || !runId.toString().equals(invocation.identity().runId())) {
                throw new IllegalStateException("Tool invocation is not bound to this durable run");
            }
            DurableToolExecutionJournalService.ClaimedOperation claimed =
                    service.prepare(orgId, runId, invocation);
            operationRows.put(invocation.operationId(), claimed.operationId());
            return claimed.prepareResult();
        }

        @Override
        public void markRunning(Invocation invocation) {
            service.markRunning(
                    orgId,
                    runId,
                    row(invocation),
                    UUID.fromString(invocation.invocationId()),
                    invocation);
        }

        @Override
        public void complete(
                Invocation invocation,
                TerminalStatus status,
                ToolResultBlock result,
                Throwable error) {
            service.complete(
                    orgId,
                    runId,
                    row(invocation),
                    UUID.fromString(invocation.invocationId()),
                    invocation,
                    status,
                    result,
                    error);
        }

        private UUID row(Invocation invocation) {
            UUID row = operationRows.get(invocation.operationId());
            if (row == null) throw new IllegalStateException("Tool operation was not prepared");
            return row;
        }
    }
}
