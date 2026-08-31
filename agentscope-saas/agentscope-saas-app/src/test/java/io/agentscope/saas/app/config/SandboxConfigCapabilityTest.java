/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.saas.sandbox.SandboxCapability;
import java.util.List;
import org.junit.jupiter.api.Test;

class SandboxConfigCapabilityTest {

    private final SandboxConfig config = new SandboxConfig();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsDeploymentWhenActiveProviderSatisfiesRequiredCapabilities() {
        SaasProperties properties = properties("docker");
        properties
                .getSandbox()
                .setRequiredCapabilities(List.of("snapshot", "resource-limits", "CUSTOM_IMAGE"));

        var deployment = config.activeSandboxDeployment(properties, objectMapper);

        assertThat(deployment.providerId()).isEqualTo("docker");
        assertThat(deployment.capabilities())
                .containsExactlyInAnyOrder(
                        SandboxCapability.SNAPSHOT,
                        SandboxCapability.RESOURCE_LIMITS,
                        SandboxCapability.CUSTOM_IMAGE);
    }

    @Test
    void rejectsDeploymentWhenActiveProviderMissesRequiredCapability() {
        SaasProperties properties = properties("e2b");
        properties.getSandbox().setRequiredCapabilities(List.of("RESOURCE_LIMITS"));

        assertThatThrownBy(() -> config.activeSandboxDeployment(properties, objectMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("e2b")
                .hasMessageContaining("RESOURCE_LIMITS");
    }

    @Test
    void rejectsUnknownCapabilityNameInsteadOfIgnoringIt() {
        SaasProperties properties = properties("opensandbox");
        properties.getSandbox().setRequiredCapabilities(List.of("GPU_MAGIC"));

        assertThatThrownBy(() -> config.activeSandboxDeployment(properties, objectMapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown sandbox capability");
    }

    @Test
    void treatsBlankEnvironmentCapabilityValueAsNoRequiredBaseline() {
        SaasProperties properties = properties("e2b");
        properties.getSandbox().setRequiredCapabilities(List.of(""));

        assertThat(config.activeSandboxDeployment(properties, objectMapper).providerId())
                .isEqualTo("e2b");
    }

    @Test
    void snapshotCapabilityRequiresDurableSnapshotConfiguration() {
        SaasProperties properties = properties("cube");
        properties.getSandbox().getSnapshot().setEnabled(false);
        properties.getSandbox().setRequiredCapabilities(List.of("SNAPSHOT"));

        assertThatThrownBy(() -> config.activeSandboxDeployment(properties, objectMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SNAPSHOT");
    }

    @Test
    void persistentWorkspaceCapabilityRequiresCubeVolumeConfiguration() {
        SaasProperties properties = properties("cube");
        properties.getSandbox().setCubeWorkspaceVolumeEnabled(true);
        properties.getSandbox().setRequiredCapabilities(List.of("persistent-workspace"));

        assertThat(config.activeSandboxDeployment(properties, objectMapper).capabilities())
                .contains(SandboxCapability.PERSISTENT_WORKSPACE);
    }

    @Test
    void cubePersistentWorkspaceDoesNotAdvertiseUnusedSnapshotCapability() {
        SaasProperties properties = properties("cube");
        properties.getSandbox().setCubeWorkspaceVolumeEnabled(true);
        properties.getSandbox().setRequiredCapabilities(List.of("snapshot"));

        assertThatThrownBy(() -> config.activeSandboxDeployment(properties, objectMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SNAPSHOT");
    }

    @Test
    void parsesDeploymentHostMountsAndAddsReadOnlyCommonSkills() {
        SaasProperties properties = properties("cube");
        properties
                .getSandbox()
                .setCubeHostMountsJson(
                        "[{\"hostPath\":\"/data/shared/models\","
                                + "\"mountPath\":\"/models\",\"readOnly\":true}]");
        properties
                .getSandbox()
                .setCubeCommonSkillsHostPath("/data/shared/agentscope-common-skills");

        var mounts = SandboxConfig.cubeHostMounts(properties.getSandbox(), new ObjectMapper());

        assertThat(mounts).hasSize(2);
        assertThat(mounts.get(1).mountPath()).isEqualTo("/opt/agentscope-common-skills");
        assertThat(mounts.get(1).readOnly()).isTrue();
    }

    @Test
    void rejectsDuplicateDeploymentMountTargets() {
        SaasProperties properties = properties("cube");
        properties
                .getSandbox()
                .setCubeHostMountsJson(
                        "[{\"hostPath\":\"/data/shared/custom\","
                                + "\"mountPath\":\"/opt/agentscope-common-skills\","
                                + "\"readOnly\":true}]");
        properties
                .getSandbox()
                .setCubeCommonSkillsHostPath("/data/shared/agentscope-common-skills");

        assertThatThrownBy(
                        () ->
                                SandboxConfig.cubeHostMounts(
                                        properties.getSandbox(), new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unique mountPath");
    }

    @Test
    void rejectsDeploymentMountOutsideApplicationAllowlist() {
        SaasProperties properties = properties("cube");
        properties
                .getSandbox()
                .setCubeHostMountsJson(
                        "[{\"hostPath\":\"/etc\",\"mountPath\":\"/host-etc\","
                                + "\"readOnly\":true}]");

        assertThatThrownBy(
                        () ->
                                SandboxConfig.cubeHostMounts(
                                        properties.getSandbox(), new ObjectMapper()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowed prefix");
    }

    @Test
    void rejectsCommonSkillsTargetOutsideWorkspace() {
        SaasProperties properties = properties("cube");
        properties.getSandbox().setCubeCommonSkillsTargetPath("/root/skills");

        assertThatThrownBy(
                        () ->
                                SandboxConfig.validateCommonSkillsTarget(
                                        properties.getSandbox(), "/workspace"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("child of the workspace");
    }

    @Test
    void mountsEnterpriseSkillsFromReadOnlyVolume() {
        SaasProperties properties = properties("cube");
        properties.getSandbox().setCubeCommonSkillsVolumeId("enterprise-skills");

        var mounts = SandboxConfig.cubeVolumeMounts(properties.getSandbox(), objectMapper);

        assertThat(mounts).hasSize(1);
        assertThat(mounts.get(0).volumeId()).isEqualTo("enterprise-skills");
        assertThat(mounts.get(0).readOnly()).isTrue();
    }

    @Test
    void rejectsTwoEnterpriseSkillsSources() {
        SaasProperties properties = properties("cube");
        properties.getSandbox().setCubeCommonSkillsHostPath("/data/shared/skills");
        properties.getSandbox().setCubeCommonSkillsVolumeId("enterprise-skills");

        assertThatThrownBy(() -> SandboxConfig.validateCommonSkillsSource(properties.getSandbox()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("either hostPath or Volume");
    }

    private static SaasProperties properties(String provider) {
        SaasProperties properties = new SaasProperties();
        properties.getSandbox().setType(provider);
        return properties;
    }
}
