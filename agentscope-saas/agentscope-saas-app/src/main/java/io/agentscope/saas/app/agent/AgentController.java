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
package io.agentscope.saas.app.agent;

import io.agentscope.saas.core.persistence.entity.AgentEntity;
import io.agentscope.saas.core.persistence.repo.AgentRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Org-scoped agent management. Every query is filtered by the caller's {@code org_id} claim so users
 * only ever see agents within their own tenant.
 */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    /** Create-agent request payload. */
    public record CreateAgentRequest(String name, String visibility) {}

    /** Agent view returned to clients. */
    public record AgentView(
            String id,
            String orgId,
            String userId,
            String name,
            String visibility,
            String status) {}

    private final AgentRepository agentRepository;

    public AgentController(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    @GetMapping
    public Mono<ResponseEntity<List<AgentView>>> list(@AuthenticationPrincipal Jwt jwt) {
        UUID orgId = UUID.fromString(jwt.getClaimAsString("org_id"));
        return Mono.fromCallable(
                        () ->
                                agentRepository.findByOrgId(orgId).stream()
                                        .map(AgentController::toView)
                                        .toList())
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }

    @PostMapping
    public Mono<ResponseEntity<?>> create(
            @AuthenticationPrincipal Jwt jwt, @RequestBody CreateAgentRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of("error", "name is required")));
        }
        UUID orgId = UUID.fromString(jwt.getClaimAsString("org_id"));
        UUID userId = UUID.fromString(jwt.getClaimAsString("user_id"));
        return Mono.fromCallable(
                        () -> {
                            AgentEntity entity = new AgentEntity();
                            entity.setId(UUID.randomUUID());
                            entity.setOrgId(orgId);
                            entity.setUserId(userId);
                            entity.setName(request.name());
                            entity.setVisibility(
                                    request.visibility() == null
                                            ? "private"
                                            : request.visibility());
                            entity.setStatus("active");
                            return toView(agentRepository.save(entity));
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .map(view -> ResponseEntity.ok((Object) view));
    }

    private static AgentView toView(AgentEntity e) {
        return new AgentView(
                e.getId().toString(),
                e.getOrgId().toString(),
                e.getUserId().toString(),
                e.getName(),
                e.getVisibility(),
                e.getStatus());
    }
}
