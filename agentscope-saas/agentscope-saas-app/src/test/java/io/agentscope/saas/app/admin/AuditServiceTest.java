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
package io.agentscope.saas.app.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.saas.core.persistence.entity.AuditLogEntity;
import io.agentscope.saas.core.persistence.repo.AuditLogRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AuditServiceTest {

    @Test
    void recordRedactsNestedSecretsBeforePersisting() {
        AuditLogRepository repository = mock(AuditLogRepository.class);
        when(repository.save(any(AuditLogEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        AuditService service = new AuditService(repository, new ObjectMapper());
        UUID orgId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        service.record(
                orgId,
                actorId,
                "admin.secret.change",
                "resource:1",
                Map.of(
                        "apiKey",
                        "plain-api-key",
                        "nested",
                        Map.of("password", "plain-password", "visible", "ok")));

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(repository).save(captor.capture());
        AuditLogEntity saved = captor.getValue();
        assertThat(saved.getOrgId()).isEqualTo(orgId);
        assertThat(saved.getActor()).isEqualTo(actorId);
        assertThat(saved.getDetail()).contains("[REDACTED]");
        assertThat(saved.getDetail()).doesNotContain("plain-api-key");
        assertThat(saved.getDetail()).doesNotContain("plain-password");
        assertThat(saved.getDetail()).contains("visible");
    }
}
