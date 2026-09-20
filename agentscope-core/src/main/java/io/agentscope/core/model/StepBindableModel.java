/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.core.model;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import java.util.List;

/** Router capability for fixing the concrete provider and its limits for one model request. */
public interface StepBindableModel extends Model {
    /** Returns a stable model handle; later catalog changes must not alter this handle's route. */
    Model bindToStep(RuntimeContext context, List<Msg> messages);

    /** A fixed route with an opaque version, without exposing endpoint credentials. */
    interface BoundModel extends Model {
        String routeVersion();
    }
}
