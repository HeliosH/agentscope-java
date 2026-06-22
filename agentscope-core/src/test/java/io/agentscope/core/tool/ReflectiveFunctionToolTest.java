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
package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.state.AgentState;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@code @Tool}-annotated methods are now bridged into the {@link ToolBase}
 * contract through {@link ReflectiveFunctionTool}, and that the new annotation fields propagate
 * onto the registered tool.
 */
class ReflectiveFunctionToolTest {

    static final class SafeReadTools {
        @Tool(
                name = "safe_read",
                description = "Read-only echo",
                readOnly = true,
                concurrencySafe = true)
        public String safeRead(@ToolParam(name = "key", description = "key") String key) {
            return "value:" + key;
        }
    }

    static final class StatefulTools {
        @Tool(
                name = "with_state",
                description = "Tool that reads agent state",
                stateInjected = true)
        public String withState(
                @ToolParam(name = "label", description = "label") String label, AgentState state) {
            return label + ":" + (state == null ? "<null>" : state.getSessionId());
        }
    }

    static final class MismatchedTools {
        // stateInjected=true but no AgentState parameter -> registration should fail.
        @Tool(name = "broken_state", description = "broken", stateInjected = true)
        public String broken(@ToolParam(name = "x", description = "x") String x) {
            return x;
        }
    }

    static final class UndeclaredStateTools {
        // AgentState parameter but stateInjected=false -> registration should fail.
        @Tool(name = "undeclared_state", description = "undeclared")
        public String undeclared(
                @ToolParam(name = "x", description = "x") String x, AgentState state) {
            return x + ":" + (state == null ? "<null>" : state.getSessionId());
        }
    }

    static final class ExternalTools {
        @Tool(name = "external_call", description = "external", externalTool = true)
        public String external(@ToolParam(name = "q", description = "q") String q) {
            return q;
        }
    }

    /** A shell-like tool with a {@code command} argument, used to verify parameter-level rules. */
    static final class ShellTools {
        @Tool(name = "run_shell", description = "run a shell command")
        public String runShell(
                @ToolParam(name = "command", description = "command to run") String command) {
            return "ran:" + command;
        }
    }

    /** A tool with no {@code command} argument, to confirm parameter-level rules skip it. */
    static final class NoCommandTools {
        @Tool(name = "no_command", description = "tool without command arg")
        public String noCommand(@ToolParam(name = "path", description = "path") String path) {
            return path;
        }
    }

    @Test
    void registersAsToolBaseSubclassWithAnnotationFlags() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SafeReadTools());

        AgentTool tool = toolkit.getTool("safe_read");
        ToolBase tb = assertInstanceOf(ToolBase.class, tool);
        assertTrue(tb.isReadOnly(), "readOnly flag must propagate");
        assertTrue(tb.isConcurrencySafe(), "concurrencySafe flag must propagate");
        assertFalse(tb.isExternalTool(), "externalTool default false");
        assertFalse(tb.isStateInjected(), "stateInjected default false");
        assertFalse(tb.isMcp(), "mcp default false");
    }

    @Test
    void stateInjectedToolExcludesAgentStateFromSchemaAndRegisters() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new StatefulTools());

        AgentTool tool = toolkit.getTool("with_state");
        ToolBase tb = assertInstanceOf(ToolBase.class, tool);
        assertTrue(tb.isStateInjected());

        Map<String, Object> schema = tb.getParameters();
        assertNotNull(schema.get("properties"));
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertTrue(properties.containsKey("label"), "label parameter must be in schema");
        assertFalse(
                properties.containsKey("state"),
                "AgentState parameter must NOT appear in schema (auto-injected)");
    }

    @Test
    void stateInjectedTrueWithoutAgentStateParameterFailsAtRegistration() {
        Toolkit toolkit = new Toolkit();
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> toolkit.registerTool(new MismatchedTools()));
        assertTrue(
                ex.getMessage().contains("AgentState"),
                "error must mention AgentState requirement: " + ex.getMessage());
    }

    @Test
    void agentStateParameterWithoutStateInjectedFlagFailsAtRegistration() {
        Toolkit toolkit = new Toolkit();
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> toolkit.registerTool(new UndeclaredStateTools()));
        assertTrue(
                ex.getMessage().contains("stateInjected"),
                "error must mention stateInjected: " + ex.getMessage());
    }

    @Test
    void externalToolFlagIsRecognisedByToolkit() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ExternalTools());

        assertTrue(
                toolkit.isExternalTool("external_call"),
                "@Tool(externalTool=true) must be reported as external");
        AgentTool tool = toolkit.getTool("external_call");
        ToolBase tb = assertInstanceOf(ToolBase.class, tool);
        assertTrue(tb.isExternalTool());
    }

    @Test
    void nullRuleContentMatchesEveryInvocation() {
        ReflectiveFunctionTool tool = reflectiveShellTool();
        // null ruleContent is the tool-name-level wildcard — matches regardless of command.
        assertTrue(tool.matchRule(null, Map.of("command", "rm -rf /")));
        assertTrue(tool.matchRule(null, Map.of("command", "echo hi")));
        assertTrue(tool.matchRule(null, Map.of()));
    }

    @Test
    void regexRuleMatchesOnlyCommandsThatContainThePattern() {
        ReflectiveFunctionTool tool = reflectiveShellTool();
        String danger = "rm -rf|mkfs|dd if=";
        assertTrue(tool.matchRule(danger, Map.of("command", "rm -rf /tmp/x")));
        assertTrue(tool.matchRule(danger, Map.of("command", "sudo mkfs.ext4 /dev/sda")));
        assertFalse(tool.matchRule(danger, Map.of("command", "echo hello")));
        assertFalse(tool.matchRule(danger, Map.of("command", "ls -la")));
    }

    @Test
    void regexRuleDoesNotMatchWhenCommandAbsentOrEmpty() {
        ReflectiveFunctionTool tool = reflectiveShellTool();
        String danger = "rm -rf";
        // No command argument at all.
        assertFalse(tool.matchRule(danger, Map.of("path", "/tmp")));
        // Empty command.
        assertFalse(tool.matchRule(danger, Map.of("command", "")));
        // Non-string command.
        assertFalse(tool.matchRule(danger, Map.of("command", 42)));
    }

    @Test
    void invalidRegexIsTreatedAsNonMatchingRatherThanPropagated() {
        ReflectiveFunctionTool tool = reflectiveShellTool();
        // Unbalanced bracket is an invalid regex — must not throw, must not match.
        assertFalse(tool.matchRule("[unclosed", Map.of("command", "rm -rf /")));
    }

    @Test
    void nonCommandToolIgnoresParameterLevelRegex() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new NoCommandTools());
        AgentTool tool = toolkit.getTool("no_command");
        ReflectiveFunctionTool rft = assertInstanceOf(ReflectiveFunctionTool.class, tool);
        // A tool without a `command` argument never matches a non-null ruleContent.
        assertFalse(rft.matchRule("rm -rf", Map.of("path", "/tmp/rm -rf")));
        // null still matches (tool-name level).
        assertTrue(rft.matchRule(null, Map.of("path", "/tmp")));
    }

    private static ReflectiveFunctionTool reflectiveShellTool() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ShellTools());
        return assertInstanceOf(ReflectiveFunctionTool.class, toolkit.getTool("run_shell"));
    }
}
