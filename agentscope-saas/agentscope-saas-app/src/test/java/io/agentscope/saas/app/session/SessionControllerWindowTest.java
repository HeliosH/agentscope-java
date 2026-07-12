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
package io.agentscope.saas.app.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.saas.app.chat.ChatPersistenceService;
import io.agentscope.saas.core.persistence.entity.ChatMessageEntity;
import io.agentscope.saas.core.persistence.entity.ChatSessionEntity;
import io.agentscope.saas.core.persistence.repo.ChatMessageRepository;
import io.agentscope.saas.core.persistence.repo.ChatSessionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.security.oauth2.jwt.Jwt;

class SessionControllerWindowTest {

    private final ChatSessionRepository sessions = mock(ChatSessionRepository.class);
    private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    private final SessionController controller =
            new SessionController(
                    sessions, messages, mock(ChatPersistenceService.class), new ObjectMapper());

    @Test
    void returnsNewestWindowInChronologicalOrderAndWalksBackwards() {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(sessionId);
        when(sessions.findByIdAndOrgIdAndUserIdAndAgentId(sessionId, orgId, userId, agentId))
                .thenReturn(Optional.of(session));
        when(messages.pageBeforeSeq(
                        org.mockito.ArgumentMatchers.eq(sessionId),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(List.of(message(5), message(4), message(3), message(2)));
        when(messages.pageBeforeSeq(
                        org.mockito.ArgumentMatchers.eq(sessionId),
                        org.mockito.ArgumentMatchers.eq(3L),
                        org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(List.of(message(2), message(1)));

        SessionController.TurnWindow latest =
                controller
                        .turnsWindow(
                                jwt(orgId, userId),
                                agentId.toString(),
                                sessionId.toString(),
                                null,
                                3)
                        .block();
        assertThat(latest).isNotNull();
        assertThat(latest.items())
                .extracting(SessionController.TurnEntry::seq)
                .containsExactly(3L, 4L, 5L);
        assertThat(latest.nextBeforeSeq()).isEqualTo(3L);
        assertThat(latest.hasMore()).isTrue();

        SessionController.TurnWindow older =
                controller
                        .turnsWindow(
                                jwt(orgId, userId), agentId.toString(), sessionId.toString(), 3L, 3)
                        .block();
        assertThat(older).isNotNull();
        assertThat(older.items())
                .extracting(SessionController.TurnEntry::seq)
                .containsExactly(1L, 2L);
        assertThat(older.hasMore()).isFalse();
    }

    private static ChatMessageEntity message(long seq) {
        ChatMessageEntity message = new ChatMessageEntity();
        message.setId(UUID.randomUUID());
        message.setSeq(seq);
        message.setRole("user");
        message.setContentJson("[{\"type\":\"text\",\"text\":\"m" + seq + "\"}]");
        return message;
    }

    private static Jwt jwt(UUID orgId, UUID userId) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("org_id", orgId.toString())
                .claim("user_id", userId.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}
