/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.orchestration;

import io.agentscope.saas.app.config.SaasProperties;
import io.agentscope.saas.domain.orchestration.RunExecutionPolicy;
import io.agentscope.saas.domain.orchestration.WorkerPresenceRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** One deployment decision; database liveness is evidence, never a business authorization. */
@Component
public class RecoveryCapability {
    private final SaasProperties properties;
    private final WorkerPresenceRepository presence;
    private final String deployment;
    private final String capability;
    private final boolean durable;

    @Autowired
    public RecoveryCapability(SaasProperties properties, WorkerPresenceRepository presence) {
        this.properties = properties;
        this.presence = presence;
        var config = properties.getOrchestration();
        var policy = properties.getModel().getStreamRecovery();
        deployment = config.getDeploymentScope();
        if (deployment == null
                || !deployment.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")
                || config.getConsumerHeartbeatTtlSeconds() < 3
                || config.getConsumerHeartbeatTtlSeconds() > 86400
                || config.getSchedulerHeartbeatSeconds() < 1
                || config.getSchedulerHeartbeatSeconds()
                        > (config.getConsumerHeartbeatTtlSeconds() - 1) / 2
                || config.getRecoveryQueueSlaSeconds() < 1)
            throw new IllegalArgumentException("Invalid runtime consumer scope or liveness policy");
        if (config.getConsumerMode() == SaasProperties.Orchestration.ConsumerMode.EXTERNAL
                && "local".equals(deployment))
            throw new IllegalArgumentException(
                    "External consumers require an explicit shared deployment scope");
        durable =
                policy.isEnabled()
                        && policy.getMode() == SaasProperties.ModelStreamRecovery.Mode.DURABLE;
        if (durable
                && (!config.isEnabled()
                        || (!config.isSchedulerEnabled()
                                && config.getConsumerMode()
                                        != SaasProperties.Orchestration.ConsumerMode.EXTERNAL)))
            throw new IllegalArgumentException(
                    "DURABLE model recovery requires orchestration and a local or external"
                            + " consumer");
        if (durable
                && properties.getSandbox().isEnabled()
                && !properties.getFileStore().isEnabled())
            throw new IllegalArgumentException(
                    "DURABLE sandbox recovery requires a durable file catalog");
        capability =
                digest(
                        io.agentscope.core.util.JsonUtils.getJsonCodec()
                                .toJson(
                                        java.util.List.of(
                                                "runtime-workspace-v3",
                                                properties.getSandbox().getType(),
                                                Boolean.toString(
                                                        properties.getSandbox().isEnabled()),
                                                properties.getFileStore().getBackend(),
                                                sandboxIdentity(properties),
                                                storageIdentity(properties))));
    }

    private static String sandboxIdentity(SaasProperties properties) {
        var sandbox = properties.getSandbox();
        if (!sandbox.isEnabled()) return "disabled";
        var identity =
                switch (sandbox.getType()) {
                    case "opensandbox" ->
                            java.util.Arrays.asList(
                                    sandbox.getOpenSandboxApiBaseUrl(),
                                    sandbox.getOpenSandboxImage());
                    case "cube", "cubesandbox" ->
                            java.util.Arrays.asList(
                                    sandbox.getCubeApiUrl(),
                                    sandbox.getCubeDomain(),
                                    sandbox.getCubeTemplateId());
                    case "e2b" ->
                            java.util.Arrays.asList(
                                    sandbox.getE2bApiBaseUrl(),
                                    sandbox.getE2bDomain(),
                                    sandbox.getE2bTemplateId());
                    default ->
                            java.util.Arrays.asList(
                                    sandbox.getType(),
                                    sandbox.getImage(),
                                    sandbox.getWorkspaceRoot());
                };
        return io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(identity);
    }

    private static String storageIdentity(SaasProperties properties) {
        var store = properties.getFileStore();
        var identity =
                "minio".equals(store.getBackend())
                        ? java.util.Arrays.asList(
                                store.getMinio().getEndpoint(), store.getMinio().getBucket())
                        : java.util.Arrays.asList(store.getBackend(), store.getTable());
        return io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(identity);
    }

    public boolean localConsumerEnabled() {
        return properties.getOrchestration().isEnabled()
                && properties.getOrchestration().isSchedulerEnabled();
    }

    public boolean durableConfigured() {
        return durable;
    }

    public Snapshot snapshot() {
        return snapshot(durable);
    }

    public Snapshot snapshot(boolean requestedDurable) {
        if (!requestedDurable) return new Snapshot(false, false, false, "REQUEST_ONLY");
        // Compatibility constructor callers have no operational repository; only local mode
        // qualifies.
        if (presence == null) {
            boolean local = localConsumerEnabled();
            return new Snapshot(
                    local, local, local, local ? "AVAILABLE" : "CONSUMER_NOT_REGISTERED");
        }
        var value = presence.presence(deployment, capability);
        return snapshot(value);
    }

    public Snapshot snapshot(RunExecutionPolicy accepted) {
        if (!accepted.recoveryEnabled() || !"DURABLE".equals(accepted.recoveryMode()))
            return new Snapshot(false, false, false, "REQUEST_ONLY");
        if (!deployment.equals(accepted.deploymentScope()))
            return new Snapshot(false, false, false, "WAITING_FOR_WORKER");
        var policy =
                new io.agentscope.harness.agent.sandbox.WorkspaceTransferPolicy(
                        accepted.maxFiles(), accepted.maxFileBytes(), accepted.maxWorkspaceBytes());
        if (presence == null) {
            boolean compatible =
                    capability.equals(accepted.capabilityHash())
                            && io.agentscope.harness.agent.sandbox.WorkspaceTransferResources
                                            .archiveLimit(policy)
                                    <= RunExecutionPolicyService.physicalArchiveLimit(properties)
                            && accepted.maxFileBytes()
                                    <= RunExecutionPolicyService.physicalFileLimit(properties);
            return compatible
                    ? snapshot(true)
                    : new Snapshot(false, false, false, "WAITING_FOR_WORKER");
        }
        return snapshot(
                presence.presenceForPolicy(
                        deployment,
                        accepted.capabilityHash(),
                        io.agentscope.harness.agent.sandbox.WorkspaceTransferResources.archiveLimit(
                                policy),
                        accepted.maxFileBytes()));
    }

    private Snapshot snapshot(WorkerPresenceRepository.Presence value) {
        boolean known = value.registered() > 0;
        boolean healthy = value.healthy() > 0;
        boolean available = healthy && value.availableSlots() > 0;
        return new Snapshot(
                known,
                healthy,
                available,
                !known
                        ? "CONSUMER_NOT_REGISTERED"
                        : !healthy
                                ? "WAITING_FOR_WORKER"
                                : !available ? "WAITING_FOR_CAPACITY" : "AVAILABLE");
    }

    public void heartbeat(UUID worker, int slots) {
        if (!localConsumerEnabled() || presence == null) return;
        presence.heartbeat(
                deployment,
                worker,
                capability,
                Math.max(0, slots),
                properties.getOrchestration().getConsumerHeartbeatTtlSeconds(),
                RunExecutionPolicyService.physicalArchiveLimit(properties),
                RunExecutionPolicyService.physicalFileLimit(properties));
    }

    public void retire(UUID worker) {
        if (presence != null) presence.retire(deployment, worker);
    }

    public String deploymentScope() {
        return deployment;
    }

    public String capabilityHash() {
        return capability;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record Snapshot(
            boolean registered, boolean healthy, boolean capacityAvailable, String state) {
        public boolean canEnqueue() {
            return registered;
        }
    }
}
