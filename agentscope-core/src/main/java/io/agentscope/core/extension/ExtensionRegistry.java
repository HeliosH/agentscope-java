/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.core.extension;

import io.agentscope.core.tool.RuntimeToolScope;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Registry for deployment-approved extensions with dependency ordering and transactional rollback.
 *
 * <p>Definitions are registered during application bootstrap. Each activation creates an immutable
 * extension set for one runtime scope; partial activation never remains installed.
 */
public final class ExtensionRegistry {

    private final Map<String, Definition> definitions = new ConcurrentHashMap<>();

    public RegistrationHandle register(ExtensionManifest manifest, ExtensionActivator activator) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(activator, "activator");
        Definition definition = new Definition(manifest, activator);
        if (definitions.putIfAbsent(manifest.id(), definition) != null) {
            throw new IllegalStateException("extension is already registered: " + manifest.id());
        }
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                definitions.remove(manifest.id(), definition);
            }
        };
    }

    public ActiveExtensionSet activate(Collection<String> extensionIds) {
        List<Definition> activationOrder = resolve(extensionIds);
        List<RegistrationHandle> handles = new ArrayList<>(activationOrder.size());
        try {
            for (Definition definition : activationOrder) {
                RegistrationHandle handle = definition.activator().activate();
                handles.add(Objects.requireNonNull(handle, "extension activation handle"));
            }
        } catch (RuntimeException e) {
            try {
                RegistrationHandle.composite(handles).close();
            } catch (RuntimeException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            throw e;
        }
        return new ActiveExtensionSet(
                activationOrder.stream().map(Definition::manifest).toList(),
                RegistrationHandle.composite(handles));
    }

    public List<ExtensionManifest> definitions() {
        return definitions.values().stream()
                .map(Definition::manifest)
                .sorted(Comparator.comparing(ExtensionManifest::id))
                .toList();
    }

    private List<Definition> resolve(Collection<String> extensionIds) {
        List<String> requested =
                extensionIds == null
                        ? List.of()
                        : extensionIds.stream()
                                .filter(Objects::nonNull)
                                .distinct()
                                .sorted()
                                .toList();
        Map<String, VisitState> states = new HashMap<>();
        Map<String, Definition> ordered = new LinkedHashMap<>();
        for (String id : requested) {
            visit(id, states, ordered, new LinkedHashSet<>());
        }
        return List.copyOf(ordered.values());
    }

    private void visit(
            String id,
            Map<String, VisitState> states,
            Map<String, Definition> ordered,
            Set<String> path) {
        VisitState state = states.get(id);
        if (state == VisitState.VISITED) {
            return;
        }
        if (state == VisitState.VISITING) {
            path.add(id);
            throw new IllegalStateException(
                    "extension dependency cycle: " + String.join(" -> ", path));
        }
        Definition definition = definitions.get(id);
        if (definition == null) {
            throw new IllegalStateException("required extension is not registered: " + id);
        }
        states.put(id, VisitState.VISITING);
        path.add(id);
        for (String dependency : definition.manifest().dependencies().stream().sorted().toList()) {
            visit(dependency, states, ordered, new LinkedHashSet<>(path));
        }
        states.put(id, VisitState.VISITED);
        ordered.put(id, definition);
    }

    private record Definition(ExtensionManifest manifest, ExtensionActivator activator) {}

    private enum VisitState {
        VISITING,
        VISITED
    }

    /** Immutable, auditable identity of an activated extension set. */
    public static final class ActiveExtensionSet implements RegistrationHandle {

        private final List<ExtensionManifest> manifests;
        private final List<String> identities;
        private final String hash;
        private final RegistrationHandle registrations;

        private ActiveExtensionSet(
                List<ExtensionManifest> manifests, RegistrationHandle registrations) {
            this.manifests = List.copyOf(manifests);
            this.identities = manifests.stream().map(ExtensionManifest::identity).sorted().toList();
            this.hash = RuntimeToolScope.hash(String.join("\n", identities));
            this.registrations = registrations;
        }

        public List<ExtensionManifest> manifests() {
            return manifests;
        }

        public List<String> identities() {
            return identities;
        }

        public String hash() {
            return hash;
        }

        @Override
        public void close() {
            registrations.close();
        }
    }
}
