/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.RuntimeToolScope;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.tools.McpClientRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class DynamicMcpMiddlewareIsolationTest {

    @Test
    void installsIndependentPerUserToolSnapshotsWithoutMutatingSingletonToolkit() {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        Toolkit platform = new Toolkit();
        platform.registerAgentTool(namedTool("alpha"));
        platform.registerAgentTool(namedTool("beta"));

        Agent agent = mock(Agent.class);
        when(agent.getToolkit()).thenReturn(platform);
        AbstractFilesystem filesystem = mock(AbstractFilesystem.class);
        when(filesystem.read(any(), eq("/tools.json"), eq(0), eq(0)))
                .thenAnswer(
                        invocation -> {
                            RuntimeContext context = invocation.getArgument(0);
                            String json =
                                    userA.toString().equals(context.getUserId())
                                            ? "{\"deny\":[\"beta\"]}"
                                            : "{\"deny\":[\"alpha\"]}";
                            return ReadResult.success(FileData.create(json));
                        });
        DynamicMcpMiddleware middleware =
                new DynamicMcpMiddleware(
                        filesystem,
                        new McpClientRegistry(),
                        context -> UUID.fromString(context.getUserId()),
                        context -> null,
                        ignored -> null);

        RuntimeContext contextA = RuntimeContext.builder().userId(userA.toString()).build();
        RuntimeContext contextB = RuntimeContext.builder().userId(userB.toString()).build();
        middleware
                .onAgent(agent, contextA, new AgentInput(List.of()), ignored -> Flux.empty())
                .then()
                .block();
        middleware
                .onAgent(agent, contextB, new AgentInput(List.of()), ignored -> Flux.empty())
                .then()
                .block();

        Toolkit scopedA = RuntimeToolScope.current(contextA).toolkit();
        Toolkit scopedB = RuntimeToolScope.current(contextB).toolkit();
        assertNotSame(scopedA, scopedB);
        assertEquals(Set.of("alpha"), scopedA.getToolNames());
        assertEquals(Set.of("beta"), scopedB.getToolNames());
        assertEquals(Set.of("alpha", "beta"), platform.getToolNames());
    }

    private static AgentTool namedTool(String name) {
        AgentTool tool = mock(AgentTool.class);
        when(tool.getName()).thenReturn(name);
        when(tool.getDescription()).thenReturn(name);
        when(tool.getParameters()).thenReturn(Map.of("type", "object"));
        return tool;
    }
}
