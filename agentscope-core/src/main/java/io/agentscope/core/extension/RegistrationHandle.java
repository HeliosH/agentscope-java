/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.core.extension;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** An idempotent handle that reverses a runtime contribution. */
@FunctionalInterface
public interface RegistrationHandle extends AutoCloseable {

    @Override
    void close();

    /** Combines registrations and releases them in reverse registration order. */
    static RegistrationHandle composite(Collection<? extends RegistrationHandle> handles) {
        List<RegistrationHandle> copy = handles == null ? List.of() : new ArrayList<>(handles);
        Collections.reverse(copy);
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            RuntimeException failure = null;
            for (RegistrationHandle handle : copy) {
                if (handle == null) {
                    continue;
                }
                try {
                    handle.close();
                } catch (RuntimeException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        };
    }
}
