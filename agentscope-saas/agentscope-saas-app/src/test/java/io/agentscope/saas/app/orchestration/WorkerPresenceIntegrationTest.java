/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.saas.domain.orchestration.WorkerPresenceRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:worker-presence-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            "saas.datasource.admin.url=jdbc:h2:mem:worker-presence-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
        })
@ActiveProfiles("local")
class WorkerPresenceIntegrationTest {
    @Autowired WorkerPresenceRepository repository;

    @Test
    void persistentHeartbeatsAreIdempotentScopedAndRetirementDoesNotEraseRegistration() {
        var config = RecoveryCapabilityTest.durable();
        config.getOrchestration().setDeploymentScope("test-" + UUID.randomUUID());
        var capability = new RecoveryCapability(config, repository);
        assertThat(capability.snapshot().registered()).isFalse();
        var worker = UUID.randomUUID();
        capability.heartbeat(worker, 2);
        capability.heartbeat(worker, 1);
        var presence =
                repository.presence(capability.deploymentScope(), capability.capabilityHash());
        assertThat(presence).isEqualTo(new WorkerPresenceRepository.Presence(1, 1, 1));
        assertThat(
                        repository
                                .presence("other-deployment", capability.capabilityHash())
                                .registered())
                .isZero();
        assertThat(repository.presence(capability.deploymentScope(), "0".repeat(64)).registered())
                .isZero();
        capability.retire(worker);
        assertThat(capability.snapshot().state()).isEqualTo("WAITING_FOR_WORKER");
        assertThat(capability.snapshot().canEnqueue()).isTrue();
        capability.heartbeat(worker, 2);
        assertThat(capability.snapshot().state()).isEqualTo("AVAILABLE");
        capability.retire(worker);
    }

    @Test
    void databaseClockExpiresADeadWorkerWithoutTheWebProcessesClock() throws Exception {
        String scope = "expiry-" + UUID.randomUUID();
        UUID worker = UUID.randomUUID();
        String hash = "1".repeat(64);
        repository.heartbeat(scope, worker, hash, 1, 1);
        assertThat(repository.presence(scope, hash).healthy()).isEqualTo(1);
        Thread.sleep(1100);
        assertThat(repository.presence(scope, hash))
                .isEqualTo(new WorkerPresenceRepository.Presence(1, 0, 0));
    }

    @Test
    void acceptedPolicyCountsOnlyWorkersWithSufficientPhysicalCapacity() {
        String scope = "capacity-" + UUID.randomUUID();
        String hash = "2".repeat(64);
        var small = UUID.randomUUID();
        var large = UUID.randomUUID();
        repository.heartbeat(scope, small, hash, 4, 60, 100, 10);
        assertThat(repository.presenceForPolicy(scope, hash, 101, 10).registered()).isZero();
        assertThat(repository.presenceForPolicy(scope, hash, 100, 11).registered()).isZero();
        repository.heartbeat(scope, large, hash, 2, 60, 1000, 100);
        assertThat(repository.presenceForPolicy(scope, hash, 500, 50))
                .isEqualTo(new WorkerPresenceRepository.Presence(1, 1, 2));
        repository.retire(scope, large);
        assertThat(repository.presenceForPolicy(scope, hash, 500, 50))
                .isEqualTo(new WorkerPresenceRepository.Presence(1, 0, 0));
        assertThat(repository.presenceForPolicy(scope, hash, 1001, 50).registered()).isZero();
        repository.retire(scope, small);
    }
}
