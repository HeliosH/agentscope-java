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

import org.junit.jupiter.api.Test;

class CompletionGateTest {

    private final CompletionGate gate = new CompletionGate();

    @Test
    void permitsLegacyTasksWithoutAnAcceptanceContract() {
        var decision = gate.evaluate("[]", "[]", "{}");

        assertThat(decision.passed()).isTrue();
        assertThat(decision.verificationRequired()).isFalse();
    }

    @Test
    void rejectsAgentAssertionsWithoutStructuredEvidence() {
        var decision =
                gate.evaluate(
                        "[\"research-summary\"]",
                        "[\"sources are traceable\"]",
                        "{\"status\":\"succeeded\",\"summary\":\"done\","
                                + "\"evidence\":[],\"artifactRefs\":[],\"followUpTasks\":[],"
                                + "\"usage\":{}}");

        assertThat(decision.passed()).isFalse();
        assertThat(decision.failures()).hasSize(2);
    }

    @Test
    void requiresArtifactsForDeclaredFileOutputs() {
        var decision =
                gate.evaluate(
                        "[\"report.pdf\"]",
                        "[\"format is valid\"]",
                        "{\"status\":\"succeeded\",\"summary\":\"done\","
                                + "\"evidence\":[{\"source\":\"agent_result\"}],"
                                + "\"artifactRefs\":[],\"followUpTasks\":[],\"usage\":{}}");

        assertThat(decision.passed()).isFalse();
        assertThat(decision.failures())
                .contains("file output requires an immutable artifact reference");
    }

    @Test
    void acceptsStructuredEvidenceAndImmutableArtifacts() {
        var decision =
                gate.evaluate(
                        "[\"report.pdf\"]",
                        "[\"format is valid\"]",
                        "{\"status\":\"succeeded\",\"summary\":\"done\","
                                + "\"evidence\":[{\"source\":\"workspace_checkpoint\"}],"
                                + "\"artifactRefs\":[\"workspace-catalog://attempt/1\"],"
                                + "\"followUpTasks\":[],\"usage\":{\"tokens\":0}}");

        assertThat(decision.passed()).isTrue();
        assertThat(decision.evidenceCount()).isOne();
        assertThat(decision.artifactCount()).isOne();
    }
}
