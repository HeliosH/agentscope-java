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
package io.agentscope.saas.app.security;

import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.ToolSecurityPolicy;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Scans tool requests with LlamaFirewall before they enter the permission and execution path. */
@Component
public class LlamaFirewallToolSecurityPolicy implements ToolSecurityPolicy {

    private final LlamaFirewallClient client;

    public LlamaFirewallToolSecurityPolicy(LlamaFirewallClient client) {
        this.client = client;
    }

    @Override
    public Mono<PermissionDecision> evaluate(Request request) {
        if (!client.isEnabled()) {
            return Mono.just(PermissionDecision.passthrough("LlamaFirewall disabled"));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", request.tool().getName());
        payload.put("arguments", request.input());
        String content = client.serializeRedacted(payload);
        return client.scan(
                        new LlamaFirewallClient.ScanRequest(
                                LlamaFirewallClient.Stage.TOOL_REQUEST,
                                LlamaFirewallClient.Role.ASSISTANT,
                                content,
                                request.runtimeContext(),
                                "tool:" + request.tool().getName(),
                                request.tool().isReadOnly(),
                                Map.of(
                                        "tool_name", request.tool().getName(),
                                        "external", request.tool().isExternalTool(),
                                        "mcp", request.tool().isMcp())))
                .map(this::toPermissionDecision);
    }

    private PermissionDecision toPermissionDecision(LlamaFirewallClient.ScanDecision decision) {
        String reason =
                decision.reason()
                        + " [scanner="
                        + decision.scanner()
                        + ", score="
                        + decision.score()
                        + "]";
        String decisionReason = "llama-firewall:" + decision.scanner();
        return switch (decision.action()) {
            case ALLOW -> PermissionDecision.passthrough(reason).withDecisionReason(decisionReason);
            case BLOCK -> PermissionDecision.deny(reason).withDecisionReason(decisionReason);
            case ASK -> PermissionDecision.ask(reason).withDecisionReason(decisionReason);
        };
    }
}
