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
package io.agentscope.saas.app.org;

import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import io.agentscope.saas.app.admin.AdminSecurity;
import io.agentscope.saas.app.admin.AuditService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Org-admin management of the org-level base MCP-server config, stored under {@code
 * orgs.settings.mcpServers} (see {@link OrgToolsConfigService}). This base is overlaid with each
 * user's workspace {@code tools.json} at runtime by {@code DynamicMcpMiddleware} to form the user's
 * effective MCP toolset.
 *
 * <p>Gated to org admins and platform admins (JWT {@code role} claim) — non-admins get 403. The
 * org id is taken from the caller's {@code org_id} claim, so an admin can only manage their own
 * org's base config (RLS is the second layer of defense).
 */
@RestController
@RequestMapping("/api/org/tools")
public class OrgToolsConfigController {

    private final OrgToolsConfigService service;
    private final AuditService audit;

    public OrgToolsConfigController(OrgToolsConfigService service, AuditService audit) {
        this.service = service;
        this.audit = audit;
    }

    /** Returns the org-level base MCP config (mcpServers map only). */
    @GetMapping("/config")
    public Mono<ResponseEntity<ToolsConfig>> getConfig(@AuthenticationPrincipal Jwt jwt) {
        requireAdmin(jwt);
        UUID orgId = orgId(jwt);
        return Mono.fromCallable(() -> ResponseEntity.ok(service.loadOrgBase(orgId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Replaces the org-level {@code mcpServers} sub-key with the supplied map (other {@code
     * settings} keys are preserved). Returns the persisted base config.
     */
    @PutMapping("/config")
    public Mono<ResponseEntity<ToolsConfig>> putConfig(
            @AuthenticationPrincipal Jwt jwt, @RequestBody ToolsConfig body) {
        requireAdmin(jwt);
        UUID orgId = orgId(jwt);
        return Mono.fromCallable(
                        () -> {
                            Map<String, McpServerConfig> servers =
                                    body == null ? Map.of() : body.getMcpServers();
                            service.saveOrgBase(orgId, servers);
                            audit.record(
                                    orgId,
                                    AdminSecurity.actorId(jwt),
                                    "admin.org_tools.update",
                                    "org:" + orgId + ":tools",
                                    Map.of(
                                            "serverCount",
                                            servers.size(),
                                            "servers",
                                            servers.keySet()));
                            return ResponseEntity.ok(service.loadOrgBase(orgId));
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static void requireAdmin(Jwt jwt) {
        AdminSecurity.requireOrgAdmin(jwt);
    }

    private static UUID orgId(Jwt jwt) {
        return AdminSecurity.orgId(jwt);
    }
}
