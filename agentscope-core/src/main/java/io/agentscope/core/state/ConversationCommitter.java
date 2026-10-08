/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.core.state;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import java.util.List;

/** Critical transcript barrier before recoverable state; implementations must commit or throw. */
@FunctionalInterface
public interface ConversationCommitter {
    void commit(RuntimeContext context, String agentName, List<Msg> messages);

    record Boundary(List<Msg> messages) {
        public Boundary {
            messages = List.copyOf(messages);
        }
    }

    /** Freeze before the session serialization gate is released, without doing external IO. */
    static void freezeBoundary(RuntimeContext context) {
        if (context != null
                && context.getAgentState() != null
                && context.get(ConversationCommitter.class) != null)
            context.put(Boundary.class, new Boundary(context.getAgentState().getContext()));
    }

    static void commitBoundary(RuntimeContext context, String agentName) {
        if (context == null) return;
        ConversationCommitter committer = context.get(ConversationCommitter.class);
        Boundary boundary = context.get(Boundary.class);
        if (committer != null && boundary != null)
            committer.commit(context, agentName, boundary.messages());
        else commitCurrent(context, agentName);
    }

    static void commitCurrent(RuntimeContext context, String agentName) {
        if (context == null || context.getAgentState() == null) return;
        ConversationCommitter committer = context.get(ConversationCommitter.class);
        if (committer != null)
            committer.commit(context, agentName, List.copyOf(context.getAgentState().getContext()));
    }
}
