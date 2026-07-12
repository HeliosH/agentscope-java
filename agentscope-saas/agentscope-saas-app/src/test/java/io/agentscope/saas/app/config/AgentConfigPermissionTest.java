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
package io.agentscope.saas.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link AgentConfig#buildPermissionContext} translates the {@code askRules} map into
 * parameter-level permission rules and that the resulting {@link PermissionEngine} gates only
 * matching commands while allowing safe ones through.
 *
 * <p>This is the SaaS-side half of F7 parameter-level permission rules. The framework-side
 * {@code matchRule} regex semantics (on the reflective shell tool) are covered by tests in core;
 * here we assert the end-to-end engine behavior driven by the SaaS-built context.
 */
class AgentConfigPermissionTest {

    /** A shell-like tool with a {@code command} argument, registered via the reflective path. */
    static final class ShellTools {
        @io.agentscope.core.tool.Tool(name = "execute", description = "run a shell command")
        public String execute(
                @io.agentscope.core.tool.ToolParam(name = "command", description = "command")
                        String command) {
            return "ran:" + command;
        }
    }

    @Test
    void askRulesGateOnlyDangerousCommandsWhileSafeOnesAreAllowed() {
        SaasProperties.Agent agentCfg = new SaasProperties.Agent();
        SaasProperties.Permission p = agentCfg.getPermission();
        // execute is ALLOW by tool-name; a parameter-level ASK rule gates dangerous commands.
        p.setAllowTools(List.of("execute"));
        p.setAskTools(List.of());
        p.setAskRules(Map.of("execute", "rm -rf|mkfs|dd if="));

        PermissionContextState ctx = AgentConfig.buildPermissionContext(agentCfg);
        PermissionEngine engine = new PermissionEngine(ctx);
        ToolBase tool = registerShellTool();

        // Safe command: ask rule regex does not match -> falls through to allow rule -> ALLOW.
        assertThat(
                        engine.checkPermission(tool, Map.of("command", "echo hello"))
                                .block()
                                .getBehavior())
                .isEqualTo(PermissionBehavior.ALLOW);

        // Dangerous command: ask rule regex matches -> ASK (priority over allow).
        assertThat(
                        engine.checkPermission(tool, Map.of("command", "rm -rf /tmp/x"))
                                .block()
                                .getBehavior())
                .isEqualTo(PermissionBehavior.ASK);
    }

    @Test
    void noAskRulesKeepsToolNameLevelSemantics() {
        // Default config: execute in askTools (null content -> matches all -> ASK for every call).
        PermissionContextState ctx = AgentConfig.buildPermissionContext(new SaasProperties.Agent());
        PermissionEngine engine = new PermissionEngine(ctx);
        ToolBase tool = registerShellTool();

        assertThat(
                        engine.checkPermission(tool, Map.of("command", "echo hello"))
                                .block()
                                .getBehavior())
                .isEqualTo(PermissionBehavior.ASK);
    }

    @Test
    void defaultModeIsDefault() {
        PermissionContextState ctx = AgentConfig.buildPermissionContext(new SaasProperties.Agent());
        assertThat(ctx.getMode()).isEqualTo(PermissionMode.DEFAULT);
    }

    @Test
    void buildsBoundedLongSessionCompactionPolicy() {
        SaasProperties.Conversation cfg = new SaasProperties.Conversation();
        CompactionConfig compaction = AgentConfig.buildCompactionConfig(cfg);

        assertThat(compaction.getTriggerMessages()).isEqualTo(60);
        assertThat(compaction.getTriggerTokens()).isEqualTo(24_000);
        assertThat(compaction.getKeepTokens()).isEqualTo(8_000);
        assertThat(compaction.getTruncateArgsConfig()).isNotNull();
        assertThat(compaction.getTruncateArgsConfig().getTriggerTokens()).isEqualTo(12_000);
        assertThat(compaction.getTruncateArgsConfig().getMaxArgLength()).isEqualTo(2_000);
    }

    private static ToolBase registerShellTool() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ShellTools());
        AgentTool tool = toolkit.getTool("execute");
        return (ToolBase) tool;
    }
}
