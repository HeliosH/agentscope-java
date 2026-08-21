/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.core.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.extension.ExtensionManifest.ContributionType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExtensionRegistryTest {

    @Test
    void activatesDependenciesFirstAndReleasesInReverseOrder() {
        ExtensionRegistry registry = new ExtensionRegistry();
        List<String> events = new ArrayList<>();
        register(registry, "base", List.of(), events);
        register(registry, "reporting", List.of("base"), events);

        ExtensionRegistry.ActiveExtensionSet active = registry.activate(List.of("reporting"));

        assertEquals(List.of("activate:base", "activate:reporting"), events);
        assertEquals(List.of("base@1.0.0", "reporting@1.0.0"), active.identities());
        assertEquals(64, active.hash().length());

        active.close();
        active.close();
        assertEquals(
                List.of("activate:base", "activate:reporting", "release:reporting", "release:base"),
                events);
    }

    @Test
    void rollsBackAlreadyActivatedDependenciesWhenActivationFails() {
        ExtensionRegistry registry = new ExtensionRegistry();
        List<String> events = new ArrayList<>();
        register(registry, "base", List.of(), events);
        registry.register(
                manifest("broken", List.of("base")),
                () -> {
                    events.add("activate:broken");
                    throw new IllegalStateException("broken extension");
                });

        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class, () -> registry.activate(List.of("broken")));
        assertEquals("broken extension", failure.getMessage());
        assertEquals(List.of("activate:base", "activate:broken", "release:base"), events);
    }

    @Test
    void rejectsMissingAndCyclicDependenciesBeforeApplyingEffects() {
        ExtensionRegistry missing = new ExtensionRegistry();
        missing.register(manifest("reporting", List.of("base")), () -> () -> {});
        IllegalStateException missingFailure =
                assertThrows(
                        IllegalStateException.class, () -> missing.activate(List.of("reporting")));
        assertEquals("required extension is not registered: base", missingFailure.getMessage());

        ExtensionRegistry cyclic = new ExtensionRegistry();
        cyclic.register(manifest("alpha", List.of("beta")), () -> () -> {});
        cyclic.register(manifest("beta", List.of("alpha")), () -> () -> {});
        IllegalStateException cycleFailure =
                assertThrows(IllegalStateException.class, () -> cyclic.activate(List.of("alpha")));
        assertTrue(cycleFailure.getMessage().contains("extension dependency cycle"));
    }

    private static void register(
            ExtensionRegistry registry, String id, List<String> dependencies, List<String> events) {
        registry.register(
                manifest(id, dependencies),
                () -> {
                    events.add("activate:" + id);
                    return () -> events.add("release:" + id);
                });
    }

    private static ExtensionManifest manifest(String id, List<String> dependencies) {
        return new ExtensionManifest(id, "1.0.0", dependencies, Set.of(ContributionType.TOOL));
    }
}
