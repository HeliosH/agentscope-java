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
package io.agentscope.core.permission;

import io.agentscope.core.tool.ToolBase;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Mandatory parameter-level security checks applied before tool permission rules. */
public final class ToolInputSecurityGuard {

    private static final Pattern SHELL_EVASION =
            Pattern.compile(
                    "(?is)(\\$\\([^)]|`[^`]|<\\(|>\\(|\\$['\"]|base64\\s+(?:--decode|-d).{0,80}\\|\\s*(?:ba)?sh|(?:curl|wget).{0,240}\\|\\s*(?:ba)?sh|/dev/(?:tcp|udp)/)");
    private static final Pattern DESTRUCTIVE_COMMAND =
            Pattern.compile(
                    "(?is)(?:^|[;&|\\s])(?:sudo\\s+)?rm\\s+-[^\\n"
                        + "]*r[^\\n"
                        + "]*f|\\bmkfs(?:\\.|\\s)|\\bdd\\s+[^\\n"
                        + "]*\\bof\\s*=\\s*/dev/|\\b(?:fdisk|shutdown|reboot)\\b|chmod\\s+(?:-R\\s+)?777\\b|chown\\s+-R\\b");
    private static final Pattern SENSITIVE_PATH =
            Pattern.compile(
                    "(?i)(?:^|[/\\\\])(?:\\.ssh|\\.aws|\\.kube|\\.gnupg)(?:[/\\\\]|$)|(?:^|[/\\\\])(?:\\.env(?:\\.[^/\\\\]+)?|\\.netrc|authorized_keys|id_rsa|id_ed25519)$");
    private static final Pattern SECRET_KEY =
            Pattern.compile(
                    "(?i)(?:api[-_]?key|access[-_]?token|secret|password|authorization|private[-_]?key)");
    private static final Pattern OUTBOUND_TOOL =
            Pattern.compile("(?i).*(?:http|request|fetch|webhook|upload|send|email|publish|mcp).*");

    private ToolInputSecurityGuard() {}

    /**
     * Returns a mandatory DENY/ASK decision, or PASSTHROUGH when no risk is detected.
     *
     * <p>DENY findings are deliberately approval-immune: obfuscated execution and reverse-shell
     * primitives have no valid reason to bypass the normal policy parser. Destructive operations,
     * sensitive paths, and possible credential egress require explicit approval.
     */
    public static PermissionDecision inspect(String toolName, Map<String, Object> input) {
        return inspect(toolName, false, false, input);
    }

    /** Inspect a concrete tool, including MCP/external transport metadata unavailable in its name. */
    public static PermissionDecision inspect(ToolBase tool, Map<String, Object> input) {
        if (tool == null) {
            return inspect("", false, false, input);
        }
        return inspect(tool.getName(), tool.isMcp(), tool.isExternalTool(), input);
    }

    private static PermissionDecision inspect(
            String toolName, boolean mcp, boolean external, Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return PermissionDecision.passthrough("No tool parameters to inspect");
        }
        String command = scalar(input.get("command"));
        if (command != null && SHELL_EVASION.matcher(command).find()) {
            return decision(
                    PermissionBehavior.DENY,
                    "Tool call blocked by the parameter security guard",
                    "shell_evasion");
        }
        if (command != null && DESTRUCTIVE_COMMAND.matcher(command).find()) {
            return decision(
                    PermissionBehavior.ASK,
                    "Destructive command requires explicit authorization",
                    "destructive_command");
        }
        if (containsSensitivePath(input)) {
            return decision(
                    PermissionBehavior.ASK,
                    "Access to a sensitive path requires explicit authorization",
                    "sensitive_path");
        }
        if ((mcp || external || OUTBOUND_TOOL.matcher(toolName == null ? "" : toolName).matches())
                && containsSecretField(input)) {
            return decision(
                    PermissionBehavior.ASK,
                    "Possible credential egress requires explicit authorization",
                    "credential_egress");
        }
        return PermissionDecision.passthrough("No parameter-level risk detected");
    }

    private static boolean containsSensitivePath(Map<String, Object> input) {
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
            if (key.contains("path") || key.contains("file") || key.contains("directory")) {
                String value = scalar(entry.getValue());
                if (value != null && SENSITIVE_PATH.matcher(value).find()) {
                    return true;
                }
            }
            if (entry.getValue() instanceof Map<?, ?> nested
                    && containsSensitivePath(cast(nested))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsSecretField(Map<String, Object> input) {
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            if (entry.getKey() != null
                    && SECRET_KEY.matcher(entry.getKey()).find()
                    && scalar(entry.getValue()) != null) {
                return true;
            }
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested && containsSecretField(cast(nested))) {
                return true;
            }
            if (value instanceof Collection<?> collection) {
                for (Object item : collection) {
                    if (item instanceof Map<?, ?> nested && containsSecretField(cast(nested))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> value) {
        return (Map<String, Object>) value;
    }

    private static String scalar(Object value) {
        if (value instanceof CharSequence chars && !chars.toString().isBlank()) {
            return chars.toString();
        }
        return null;
    }

    private static PermissionDecision decision(
            PermissionBehavior behavior, String message, String reason) {
        return PermissionDecision.builder()
                .behavior(behavior)
                .message(message)
                .decisionReason("parameter_guard:" + reason)
                .build();
    }
}
