/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.domain.workspace.FilePublicationRepository.Execution;
import io.agentscope.saas.domain.workspace.WorkspaceManifestRepository;
import io.agentscope.saas.sandbox.SandboxRuntimeAttributes;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorkspaceComputedResultTest {
    final UUID task = UUID.randomUUID(), attempt = UUID.randomUUID(), org = UUID.randomUUID();
    final AtomicInteger serialized = new AtomicInteger();
    final WorkspaceComputedResult result =
            new WorkspaceComputedResult(
                    task,
                    attempt,
                    msg -> {
                        assertThat(TenantContextHolder.getOrgId()).isEqualTo(org.toString());
                        serialized.incrementAndGet();
                        return new WorkspaceArchiveService.PendingResult(
                                "{}", List.copyOf(msg.getContent()), true);
                    });
    final WorkspaceArchiveService archives = mock(WorkspaceArchiveService.class);

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
    }

    RuntimeContext context(UUID scopedTask) {
        return RuntimeContext.builder()
                .put(SandboxRuntimeAttributes.ATTR_TASK_ID, scopedTask.toString())
                .put(SandboxRuntimeAttributes.ATTR_ATTEMPT_ID, attempt.toString())
                .put(
                        TenantContext.ATTR_KEY,
                        new TenantContext(
                                org.toString(),
                                UUID.randomUUID().toString(),
                                "member",
                                "standard",
                                1,
                                0))
                .build();
    }

    WorkspaceCheckpointContext checkpoint() {
        var checkpoint = new WorkspaceCheckpointContext(true);
        checkpoint.planned(
                new WorkspaceManifestRepository.Manifest(
                        UUID.randomUUID(),
                        new WorkspaceManifestRepository.Scope(
                                org,
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                0,
                                UUID.randomUUID(),
                                new Execution(task, UUID.randomUUID(), attempt, "owner")),
                        1,
                        "hash",
                        0,
                        0,
                        0,
                        "SCANNED",
                        true,
                        OffsetDateTime.now()));
        return checkpoint;
    }

    Msg answer() {
        return Msg.builder().role(MsgRole.ASSISTANT).textContent("complete").build();
    }

    @Test
    void finalResultRestoresThreadTenantAndCannotBeResetByAnotherTask() {
        TenantContextHolder.setOrgId("prior");
        result.onStart(context(task));
        Msg message = answer();
        result.onResult(context(task), message);
        assertThat(TenantContextHolder.getOrgId()).isEqualTo("prior");
        result.onStart(context(UUID.randomUUID()));
        result.onResult(context(UUID.randomUUID()), answer());
        var checkpoint = checkpoint();
        result.sealed(checkpoint, archives);
        verify(archives).prepare(checkpoint, "{}", message.getContent(), true);
        assertThat(serialized).hasValue(1);
    }

    @Test
    void ownRetryClearsPriorResultBeforeAnErrorOrPausedTurn() {
        result.onResult(context(task), answer());
        result.onStart(context(task));
        result.onResult(
                context(task),
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        ToolUseBlock.builder()
                                                .id("tool")
                                                .name("execute")
                                                .input(java.util.Map.of())
                                                .build()))
                        .build());
        result.sealed(checkpoint(), archives);
        verifyNoInteractions(archives);
        assertThat(serialized).hasValue(1);
    }

    @Test
    void unrelatedTaskAndNonAssistantMessageNeverBecomeComputedEvidence() {
        result.onResult(context(UUID.randomUUID()), answer());
        result.onResult(
                context(task), Msg.builder().role(MsgRole.USER).textContent("question").build());
        result.sealed(checkpoint(), archives);
        verifyNoInteractions(archives);
        assertThat(serialized).hasValue(0);
    }
}
