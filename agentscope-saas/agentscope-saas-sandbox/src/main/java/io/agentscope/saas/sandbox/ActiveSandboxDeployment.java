/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.sandbox;

import java.util.Set;
import java.util.stream.Collectors;

/** Immutable description of the single sandbox provider selected for this deployment. */
public record ActiveSandboxDeployment(
        String providerId,
        String imageOrTemplate,
        Set<SandboxCapability> capabilities,
        String capabilitiesJson) {

    public ActiveSandboxDeployment {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId is required");
        }
        providerId = providerId.trim().toLowerCase();
        imageOrTemplate =
                imageOrTemplate == null || imageOrTemplate.isBlank()
                        ? null
                        : imageOrTemplate.trim();
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        capabilitiesJson =
                capabilitiesJson == null || capabilitiesJson.isBlank()
                        ? "{\"capabilities\":[]}"
                        : capabilitiesJson;
    }

    public void require(Set<SandboxCapability> requiredCapabilities) {
        if (requiredCapabilities == null || requiredCapabilities.isEmpty()) {
            return;
        }
        Set<SandboxCapability> missing =
                requiredCapabilities.stream()
                        .filter(required -> !capabilities.contains(required))
                        .collect(Collectors.toUnmodifiableSet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Sandbox provider "
                            + providerId
                            + " does not satisfy deployment capabilities: "
                            + missing);
        }
    }
}
