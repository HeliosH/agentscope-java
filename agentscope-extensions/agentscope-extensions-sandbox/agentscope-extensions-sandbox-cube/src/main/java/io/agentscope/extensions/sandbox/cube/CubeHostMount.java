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
package io.agentscope.extensions.sandbox.cube;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** A Cube {@code metadata["host-mount"]} descriptor. */
public record CubeHostMount(String hostPath, String mountPath, boolean readOnly) {

    public CubeHostMount {
        hostPath = requireSafeAbsolutePath(hostPath, "hostPath", true);
        mountPath = requireSafeAbsolutePath(mountPath, "mountPath", false);
    }

    CubeHostMount resolve(String sessionId, List<String> allowedHostPrefixes) {
        String resolvedHostPath = hostPath.replace("{sessionId}", sessionId);
        CubeHostMount resolved = new CubeHostMount(resolvedHostPath, mountPath, readOnly);
        resolved.requireAllowedHostPrefix(allowedHostPrefixes);
        return resolved;
    }

    /** Validates this deployment-controlled path against the application allowlist. */
    public CubeHostMount validateAllowedHostPrefixes(List<String> allowedHostPrefixes) {
        requireAllowedHostPrefix(allowedHostPrefixes);
        return this;
    }

    private void requireAllowedHostPrefix(List<String> allowedHostPrefixes) {
        Path host = Path.of(hostPath).normalize();
        boolean allowed =
                allowedHostPrefixes != null
                        && allowedHostPrefixes.stream()
                                .filter(Objects::nonNull)
                                .map(String::trim)
                                .filter(prefix -> !prefix.isEmpty())
                                .map(
                                        prefix ->
                                                requireSafeAbsolutePath(
                                                        prefix, "allowedHostPrefix", false))
                                .map(Path::of)
                                .map(Path::normalize)
                                .anyMatch(host::startsWith);
        if (!allowed) {
            throw new IllegalArgumentException(
                    "Cube hostPath must be within an allowed prefix: " + hostPath);
        }
    }

    private static String requireSafeAbsolutePath(
            String value, String fieldName, boolean allowSessionPlaceholder) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Cube " + fieldName + " must not be blank");
        }
        String path = value.trim();
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        String pathForValidation =
                allowSessionPlaceholder ? path.replace("{sessionId}", "session") : path;
        if (!Path.of(pathForValidation).isAbsolute()) {
            throw new IllegalArgumentException("Cube " + fieldName + " must be absolute: " + path);
        }
        if (pathForValidation.indexOf('\0') >= 0
                || pathForValidation.equals("/")
                || !Path.of(pathForValidation).normalize().toString().equals(pathForValidation)) {
            throw new IllegalArgumentException("Unsafe Cube " + fieldName + ": " + path);
        }
        if (allowSessionPlaceholder
                && (path.contains("{") || path.contains("}"))
                && !path.replace("{sessionId}", "").matches("[^{}]*")) {
            throw new IllegalArgumentException(
                    "Cube hostPath only supports the {sessionId} placeholder: " + path);
        }
        return path;
    }
}
