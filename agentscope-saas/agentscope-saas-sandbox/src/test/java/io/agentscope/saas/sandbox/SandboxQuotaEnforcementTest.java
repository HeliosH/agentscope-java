/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.saas.sandbox;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.saas.core.persistence.entity.SandboxEntity;
import io.agentscope.saas.core.persistence.entity.UserEntity;
import io.agentscope.saas.core.persistence.repo.SandboxRepository;
import io.agentscope.saas.core.persistence.repo.UserRepository;
import io.agentscope.saas.core.ratelimit.QuotaExceededException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link SandboxBroker} quota enforcement logic. */
@ExtendWith(MockitoExtension.class)
class SandboxQuotaEnforcementTest {

    @Mock private SandboxRepository sandboxRepository;
    @Mock private UserRepository userRepository;
    @Mock private SandboxMetrics metrics;

    private SandboxBroker broker;

    private static final UUID ORG_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @BeforeEach
    void setUp() {
        broker = new SandboxBroker(sandboxRepository, userRepository, metrics);
    }

    @Test
    void allowsQuotaWhenUnderLimit() {
        when(sandboxRepository.countByOrgIdAndUserIdAndStatus(ORG_ID, USER_ID, "active"))
                .thenReturn(0);

        assertDoesNotThrow(() -> broker.checkQuota(ORG_ID, USER_ID, 1));
    }

    @Test
    void rejectsQuotaWhenAtLimit() {
        when(sandboxRepository.countByOrgIdAndUserIdAndStatus(ORG_ID, USER_ID, "active"))
                .thenReturn(1);

        QuotaExceededException ex =
                assertThrows(
                        QuotaExceededException.class, () -> broker.checkQuota(ORG_ID, USER_ID, 1));
        assertEquals(
                "Sandbox quota exceeded for user " + USER_ID + " in org " + ORG_ID + " (1/1)",
                ex.getMessage());
    }

    @Test
    void allowsUpToMaxSandboxes() {
        when(sandboxRepository.countByOrgIdAndUserIdAndStatus(ORG_ID, USER_ID, "active"))
                .thenReturn(2);

        // maxSandboxes=3, currently 2 active → should pass
        assertDoesNotThrow(() -> broker.checkQuota(ORG_ID, USER_ID, 3));

        // maxSandboxes=2, currently 2 active → should reject
        assertThrows(QuotaExceededException.class, () -> broker.checkQuota(ORG_ID, USER_ID, 2));
    }

    @Test
    void zeroMaxSandboxesAlwaysRejects() {
        when(sandboxRepository.countByOrgIdAndUserIdAndStatus(ORG_ID, USER_ID, "active"))
                .thenReturn(0);

        // Even with 0 active, maxSandboxes=0 means no sandboxes allowed
        assertThrows(QuotaExceededException.class, () -> broker.checkQuota(ORG_ID, USER_ID, 0));
    }

    @Test
    void registerActiveLocksTenantUserAndRechecksQuota() {
        UserEntity user = new UserEntity();
        user.setId(USER_ID);
        user.setOrgId(ORG_ID);
        when(userRepository.lockTenantUser(ORG_ID, USER_ID)).thenReturn(Optional.of(user));
        when(sandboxRepository.countByOrgIdAndUserIdAndStatus(ORG_ID, USER_ID, "active"))
                .thenReturn(0);
        when(sandboxRepository.save(any(SandboxEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UUID id =
                broker.registerActive(
                        ORG_ID,
                        USER_ID,
                        "sess-1",
                        "e2b",
                        "external-1",
                        OffsetDateTime.now().plusSeconds(60),
                        1);

        verify(userRepository).lockTenantUser(ORG_ID, USER_ID);
        verify(sandboxRepository).countByOrgIdAndUserIdAndStatus(ORG_ID, USER_ID, "active");
        verify(sandboxRepository).save(any(SandboxEntity.class));
        assertDoesNotThrow(() -> UUID.fromString(id.toString()));
    }

    @Test
    void registerActiveRejectsInsideTransactionWhenQuotaNowExceeded() {
        UserEntity user = new UserEntity();
        user.setId(USER_ID);
        user.setOrgId(ORG_ID);
        when(userRepository.lockTenantUser(ORG_ID, USER_ID)).thenReturn(Optional.of(user));
        when(sandboxRepository.countByOrgIdAndUserIdAndStatus(ORG_ID, USER_ID, "active"))
                .thenReturn(1);

        assertThrows(
                QuotaExceededException.class,
                () ->
                        broker.registerActive(
                                ORG_ID,
                                USER_ID,
                                "sess-1",
                                "e2b",
                                "external-1",
                                OffsetDateTime.now().plusSeconds(60),
                                1));

        verify(sandboxRepository, never()).save(any(SandboxEntity.class));
    }

    @Test
    void refreshLeaseExtendsOnlyActiveRows() {
        UUID sandboxId = UUID.randomUUID();
        SandboxEntity active = new SandboxEntity();
        active.setId(sandboxId);
        active.setStatus("active");
        OffsetDateTime expiresAt = OffsetDateTime.now().plusSeconds(60);
        when(sandboxRepository.findById(sandboxId)).thenReturn(Optional.of(active));

        broker.refreshLease(sandboxId, expiresAt);

        assertEquals(expiresAt, active.getExpiresAt());
        verify(sandboxRepository).save(active);

        SandboxEntity released = new SandboxEntity();
        released.setId(sandboxId);
        released.setStatus("released");
        when(sandboxRepository.findById(sandboxId)).thenReturn(Optional.of(released));

        broker.refreshLease(sandboxId, OffsetDateTime.now().plusSeconds(120));

        verify(sandboxRepository, never()).save(eq(released));
    }

    @Test
    void updateExternalIdOnlyUpdatesActiveRows() {
        UUID sandboxId = UUID.randomUUID();
        SandboxEntity active = new SandboxEntity();
        active.setId(sandboxId);
        active.setStatus("active");
        active.setExternalId("sess-1");
        when(sandboxRepository.findById(sandboxId)).thenReturn(Optional.of(active));

        broker.updateExternalId(sandboxId, " provider-1 ");

        assertEquals("provider-1", active.getExternalId());
        verify(sandboxRepository).save(active);

        SandboxEntity released = new SandboxEntity();
        released.setId(sandboxId);
        released.setStatus("released");
        released.setExternalId("old");
        when(sandboxRepository.findById(sandboxId)).thenReturn(Optional.of(released));

        broker.updateExternalId(sandboxId, "provider-2");

        assertEquals("old", released.getExternalId());
        verify(sandboxRepository, never()).save(eq(released));
    }

    @Test
    void releaseSkipsRowsThatAreAlreadyTerminal() {
        UUID sandboxId = UUID.randomUUID();
        SandboxEntity evicted = new SandboxEntity();
        evicted.setId(sandboxId);
        evicted.setStatus("evicted");
        when(sandboxRepository.findById(sandboxId)).thenReturn(Optional.of(evicted));

        broker.release(sandboxId);

        assertEquals("evicted", evicted.getStatus());
        verify(sandboxRepository, never()).save(eq(evicted));
        verify(metrics, never()).release(anyString());
    }

    @Test
    void forceEvictMarksTenantSandboxEvictedAndRecordsMetric() {
        UUID sandboxId = UUID.randomUUID();
        SandboxEntity active = new SandboxEntity();
        active.setId(sandboxId);
        active.setOrgId(ORG_ID);
        active.setUserId(USER_ID);
        active.setSandboxType("e2b");
        active.setExternalId("external-1");
        active.setStatus("active");
        active.setExpiresAt(OffsetDateTime.now().plusSeconds(60));
        when(sandboxRepository.findById(sandboxId)).thenReturn(Optional.of(active));
        when(sandboxRepository.save(any(SandboxEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = broker.forceEvict(ORG_ID, sandboxId, "stuck row");

        assertTrue(result.isPresent());
        assertEquals("active", result.get().previousStatus());
        assertTrue(result.get().changed());
        assertEquals("evicted", active.getStatus());
        assertEquals(active.getLastUsedAt(), active.getExpiresAt());
        verify(sandboxRepository).save(active);
        verify(metrics).forceEvict("e2b");
    }

    @Test
    void forceEvictDoesNotCrossTenantBoundary() {
        UUID sandboxId = UUID.randomUUID();
        SandboxEntity active = new SandboxEntity();
        active.setId(sandboxId);
        active.setOrgId(UUID.randomUUID());
        active.setUserId(USER_ID);
        active.setSandboxType("e2b");
        active.setStatus("active");
        when(sandboxRepository.findById(sandboxId)).thenReturn(Optional.of(active));

        var result = broker.forceEvict(ORG_ID, sandboxId, "wrong tenant");

        assertTrue(result.isEmpty());
        assertEquals("active", active.getStatus());
        verify(sandboxRepository, never()).save(any(SandboxEntity.class));
        verify(metrics, never()).forceEvict(anyString());
    }
}
