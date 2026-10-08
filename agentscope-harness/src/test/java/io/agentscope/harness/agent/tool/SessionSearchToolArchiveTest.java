/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.harness.agent.tool;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.memory.session.SessionArchiveStore;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionSearchToolArchiveTest {
    private final RuntimeContext context = RuntimeContext.empty();
    private final SessionArchiveStore archive = mock(SessionArchiveStore.class);
    private final SessionSearchTool tool = new SessionSearchTool(null, archive);

    @Test
    void emptySearchDoesNotClaimAbsenceFromFullHistory() {
        when(archive.search(context, null, "needle", 10)).thenReturn(List.of());
        String result = tool.sessionSearch(context, "needle", null, null);
        assertTrue(result.contains("No preview matches"));
        assertTrue(result.contains("offloaded full bodies are not searched"));
        assertTrue(result.contains("does not prove absence"));
    }

    @Test
    void matchingSearchKeepsIdentityAndCoverageVisible() {
        when(archive.search(context, "assistant", "needle", 10))
                .thenReturn(
                        List.of(
                                new SessionArchiveStore.Hit(
                                        "assistant", "session-1", 4, "USER", "needle in preview")));
        String result = tool.sessionSearch(context, "needle", "assistant", null);
        assertTrue(result.contains("indexed previews only"));
        assertTrue(result.contains("assistant/session-1 seq=4 [USER]: needle in preview"));
    }
}
