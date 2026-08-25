/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.saas.domain.orchestration.WorkspaceIsolationMode;
import io.agentscope.saas.orchestration.DurableTaskExecutor.DependencyContext;
import io.agentscope.saas.orchestration.DurableTaskExecutor.ExecutionRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskContextAssemblerTest {

    @Test
    void includesOnlyTaskContractAndBoundedDependencyResults() {
        String oversized = "{\"summary\":\"" + "x".repeat(6000) + "\"}";
        ExecutionRequest request =
                new ExecutionRequest(
                        UUID.randomUUID(),
                        "worker",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "researcher",
                        null,
                        "member",
                        "standard",
                        2,
                        10_000,
                        "Write report",
                        "{\"topic\":\"runtime\",\"_runtime\":{"
                                + "\"sandboxIsolationKey\":\"run/secret\"}}",
                        WorkspaceIsolationMode.ATTEMPT_ISOLATED,
                        "[\"report.pdf\"]",
                        "[\"sources are traceable\"]",
                        List.of(
                                new DependencyContext(
                                        UUID.randomUUID(),
                                        "Research",
                                        oversized,
                                        List.of("file-version://1"))));

        String context = new TaskContextAssembler().assemble(request);

        assertThat(context)
                .contains("report.pdf", "sources are traceable", "file-version://1")
                .doesNotContain("sandboxIsolationKey", "run/secret", "_runtime")
                .doesNotContain("x".repeat(5000));
        assertThat(context.length()).isLessThan(6000);
    }
}
