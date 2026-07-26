/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.sandbox;

/** Immutable description of the single sandbox provider selected for this deployment. */
public record ActiveSandboxDeployment(
        String providerId, String imageOrTemplate, String capabilitiesJson) {

    public ActiveSandboxDeployment {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId is required");
        }
        providerId = providerId.trim().toLowerCase();
        imageOrTemplate =
                imageOrTemplate == null || imageOrTemplate.isBlank()
                        ? null
                        : imageOrTemplate.trim();
        capabilitiesJson =
                capabilitiesJson == null || capabilitiesJson.isBlank()
                        ? "{\"capabilities\":[]}"
                        : capabilitiesJson;
    }
}
