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
package io.agentscope.saas.app.memory;

import io.agentscope.core.memory.mem0.Mem0Message;
import io.agentscope.saas.core.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable source ledger for memory events. Semantic/vector memory systems are projections of this
 * ledger, not the source of truth.
 */
public interface MemoryLedger {

    record MemoryEventRef(UUID id, String orgId) {}

    Optional<MemoryEventRef> recordPending(
            TenantContext tenant,
            String agentName,
            String sessionId,
            List<Mem0Message> messages,
            Map<String, Object> metadata);

    void markSynced(MemoryEventRef ref);

    void markFailed(MemoryEventRef ref, Throwable error);

    static MemoryLedger noop() {
        return NoopMemoryLedger.INSTANCE;
    }

    final class NoopMemoryLedger implements MemoryLedger {

        private static final NoopMemoryLedger INSTANCE = new NoopMemoryLedger();

        private NoopMemoryLedger() {}

        @Override
        public Optional<MemoryEventRef> recordPending(
                TenantContext tenant,
                String agentName,
                String sessionId,
                List<Mem0Message> messages,
                Map<String, Object> metadata) {
            return Optional.empty();
        }

        @Override
        public void markSynced(MemoryEventRef ref) {}

        @Override
        public void markFailed(MemoryEventRef ref, Throwable error) {}
    }
}
