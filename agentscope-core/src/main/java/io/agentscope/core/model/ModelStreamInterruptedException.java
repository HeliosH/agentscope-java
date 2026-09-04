/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.core.model;

/**
 * Signals that a model stream failed after at least one response item was received.
 *
 * <p>This is deliberately different from a normal request failure. Retrying the provider Flux
 * after it has emitted data would replay already delivered tokens. The Agent/runtime layer can
 * instead restore the last committed turn and retry the complete model turn without exposing a
 * duplicated prefix or executing a partial tool call.
 */
public final class ModelStreamInterruptedException extends ModelException {

    public ModelStreamInterruptedException(
            String message, Throwable cause, String modelName, String provider) {
        super(message, cause, modelName, provider);
    }
}
