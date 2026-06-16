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
import static org.mockito.Mockito.when;

import io.agentscope.saas.core.persistence.repo.SandboxRepository;
import io.agentscope.saas.core.ratelimit.QuotaExceededException;
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

    private SandboxBroker broker;

    private static final UUID ORG_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @BeforeEach
    void setUp() {
        broker = new SandboxBroker(sandboxRepository);
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
}
