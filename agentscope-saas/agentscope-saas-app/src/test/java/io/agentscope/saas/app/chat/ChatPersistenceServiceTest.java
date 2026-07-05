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
package io.agentscope.saas.app.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.saas.core.persistence.entity.ChatMessageEntity;
import io.agentscope.saas.core.persistence.entity.ChatSessionEntity;
import io.agentscope.saas.core.persistence.repo.AgentRepository;
import io.agentscope.saas.core.persistence.repo.ChatMessageRepository;
import io.agentscope.saas.core.persistence.repo.ChatSessionRepository;
import io.agentscope.saas.core.tenant.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChatPersistenceServiceTest {

    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final ChatSessionRepository sessionRepository = mock(ChatSessionRepository.class);
    private final ChatMessageRepository messageRepository = mock(ChatMessageRepository.class);
    private final ChatPersistenceService service =
            new ChatPersistenceService(
                    agentRepository, sessionRepository, messageRepository, new ObjectMapper());

    @Test
    void assignsSessionScopedMonotonicSeq() {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(sessionId);
        when(sessionRepository.lockById(sessionId)).thenReturn(Optional.of(session));
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(ChatSessionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.maxSeq(sessionId)).thenReturn(0L, 1L);
        when(messageRepository.save(any(ChatMessageEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ChatMessageEntity first =
                service.saveUserMessage(tenant(orgId, userId), sessionId, agentId, "first");
        ChatMessageEntity second =
                service.saveUserMessage(tenant(orgId, userId), sessionId, agentId, "second");

        assertThat(first.getSeq()).isEqualTo(1L);
        assertThat(second.getSeq()).isEqualTo(2L);
        verify(sessionRepository, times(2)).lockById(sessionId);
    }

    private static TenantContext tenant(UUID orgId, UUID userId) {
        return new TenantContext(
                orgId.toString(), userId.toString(), "member", "standard", 2, 1000);
    }
}
