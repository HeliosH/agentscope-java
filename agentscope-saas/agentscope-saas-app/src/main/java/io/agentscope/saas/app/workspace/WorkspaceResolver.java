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
package io.agentscope.saas.app.workspace;

import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.saas.core.persistence.entity.AgentEntity;
import io.agentscope.saas.core.persistence.repo.AgentRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolves the caller's per-user {@link AbstractFilesystem} for a given agent, validating that the
 * agent belongs to the caller's org first (404 — not 403 — when absent, so existence is not leaked).
 *
 * <p>Shared by the workspace, skills, and subagents controllers so the tenant guard + filesystem
 * resolution logic lives in one place. The workspace itself is keyed by {@code userId} (shared
 * across an agent's sessions), per the SaaS namespace layout.
 */
@Component
public class WorkspaceResolver {

    private final HarnessAgent agent;
    private final AgentRepository agentRepository;

    public WorkspaceResolver(HarnessAgent agent, AgentRepository agentRepository) {
        this.agent = agent;
        this.agentRepository = agentRepository;
    }

    /**
     * Validates that {@code agentId} belongs to {@code orgId}, then returns the caller's per-user
     * filesystem.
     */
    public AbstractFilesystem resolve(UUID orgId, UUID userId, String agentId) {
        UUID id;
        try {
            id = UUID.fromString(agentId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: " + agentId);
        }
        agentRepository
                .findByIdAndOrgId(id, orgId)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Agent not found: " + agentId));
        return agent.workspaceFor(userId.toString(), null).getFilesystem();
    }

    /** Loads the agent entity (org-guarded) for callers that need its definition fields. */
    public AgentEntity requireAgent(UUID orgId, String agentId) {
        try {
            return agentRepository
                    .findByIdAndOrgId(UUID.fromString(agentId), orgId)
                    .orElseThrow(
                            () ->
                                    new ResponseStatusException(
                                            HttpStatus.NOT_FOUND, "Agent not found: " + agentId));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: " + agentId);
        }
    }
}
