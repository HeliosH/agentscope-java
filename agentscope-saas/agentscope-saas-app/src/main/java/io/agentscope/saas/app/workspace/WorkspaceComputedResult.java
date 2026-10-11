/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.workspace;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.harness.agent.sandbox.InvocationResultObserver;
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantContextHolder;
import io.agentscope.saas.sandbox.SandboxRuntimeAttributes;
import java.util.UUID;
import java.util.function.Function;

/** Shared across context copies, but never between task/attempt identities. */
public final class WorkspaceComputedResult implements InvocationResultObserver {
    private final UUID taskId;
    private final UUID attemptId;
    private final Function<Msg, WorkspaceArchiveService.PendingResult> serialize;
    private volatile WorkspaceArchiveService.PendingResult result;

    public WorkspaceComputedResult(
            UUID taskId,
            UUID attemptId,
            Function<Msg, WorkspaceArchiveService.PendingResult> serialize) {
        this.taskId = taskId;
        this.attemptId = attemptId;
        this.serialize = serialize;
    }

    private boolean matches(RuntimeContext context) {
        return taskId.toString().equals(context.get(SandboxRuntimeAttributes.ATTR_TASK_ID))
                && attemptId
                        .toString()
                        .equals(context.get(SandboxRuntimeAttributes.ATTR_ATTEMPT_ID));
    }

    @Override
    public void onStart(RuntimeContext context) {
        if (matches(context)) result = null;
    }

    @Override
    public void onResult(RuntimeContext context, Msg message) {
        if (!matches(context)
                || message == null
                || message.getRole() != MsgRole.ASSISTANT
                || message.getContent().isEmpty()
                || message.getContent().stream().anyMatch(ToolUseBlock.class::isInstance)) return;
        var tenant = TenantContext.from(context);
        if (tenant == null) throw new IllegalStateException("WORKSPACE_COMPUTED_SCOPE_UNAVAILABLE");
        String prior = TenantContextHolder.getOrgId();
        TenantContextHolder.setOrgId(tenant.orgId());
        try {
            result = serialize.apply(message);
        } finally {
            if (prior == null) TenantContextHolder.clear();
            else TenantContextHolder.setOrgId(prior);
        }
    }

    public void sealed(WorkspaceCheckpointContext checkpoint, WorkspaceArchiveService archives) {
        var payload = result;
        if (payload == null || checkpoint.manifest() == null) return;
        var scope = checkpoint.manifest().scope();
        if (!taskId.equals(scope.execution().taskId())
                || !attemptId.equals(scope.execution().attemptId()))
            throw new IllegalStateException("WORKSPACE_COMPUTED_SCOPE_CHANGED");
        archives.prepare(
                checkpoint, payload.outputJson(), payload.answer(), payload.deliverReply());
    }
}
