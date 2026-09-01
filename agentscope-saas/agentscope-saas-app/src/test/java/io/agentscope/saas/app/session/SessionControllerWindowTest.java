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
import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.domain.model.ChatMessageEntity;
import io.agentscope.saas.domain.model.ChatSessionEntity;
import io.agentscope.saas.domain.orchestration.RunArtifactRepository;
import io.agentscope.saas.domain.orchestration.RunArtifactRepository.RunArtifact;
import io.agentscope.saas.domain.repository.ChatMessageRepository;
import io.agentscope.saas.domain.repository.ChatSessionRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class SessionControllerWindowTest {

    private final ChatSessionRepository sessions = mock(ChatSessionRepository.class);
    private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    private final RunArtifactRepository artifacts = mock(RunArtifactRepository.class);
    private final SessionController controller =
            new SessionController(
                    sessions,
                    messages,
                    artifacts,
                    mock(ChatPersistenceService.class),
                    new ObjectMapper(),
                    claims ->
                            new TenantContext(
                                    String.valueOf(claims.get("org_id")),
                                    String.valueOf(claims.get("user_id")),
                                    "member",
                                    "standard",
                                    1,
                                    Long.MAX_VALUE));

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
                        org.mockito.ArgumentMatchers.eq(4)))
                .thenReturn(List.of(message(5), message(4), message(3), message(2)));
        when(messages.pageBeforeSeq(
                        org.mockito.ArgumentMatchers.eq(sessionId),
                        org.mockito.ArgumentMatchers.eq(3L),
                        org.mockito.ArgumentMatchers.eq(4)))
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

    @Test
    void restoresUserFacingArtifactsForHistoricalAssistantTurns() {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(sessionId);
        ChatMessageEntity assistant = message(2);
        assistant.setRole("assistant");
        assistant.setSourceRunId(runId);
        when(sessions.findByIdAndOrgIdAndUserIdAndAgentId(sessionId, orgId, userId, agentId))
                .thenReturn(Optional.of(session));
        when(messages.pageBeforeSeq(sessionId, null, 2)).thenReturn(List.of(assistant));
        when(artifacts.findByRunIds(List.of(runId), orgId))
                .thenReturn(
                        List.of(
                                new RunArtifact(
                                        UUID.randomUUID(),
                                        orgId,
                                        runId,
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        versionId,
                                        "outputs/report.pdf",
                                        "result",
                                        "{\"sizeBytes\":2048}",
                                        OffsetDateTime.now())));

        SessionController.TurnWindow window =
                controller
                        .turnsWindow(
                                jwt(orgId, userId),
                                agentId.toString(),
                                sessionId.toString(),
                                null,
                                1)
                        .block();

        assertThat(window).isNotNull();
        assertThat(window.items()).hasSize(1);
        assertThat(window.items().get(0).sourceRunId()).isEqualTo(runId.toString());
        assertThat(window.items().get(0).artifacts())
                .containsExactly(
                        new SessionController.TurnArtifact(
                                "outputs/report.pdf", versionId.toString(), 2048L));
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
