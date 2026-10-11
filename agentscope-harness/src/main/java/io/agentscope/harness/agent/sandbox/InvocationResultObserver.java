/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.harness.agent.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;

/** Optional result observation before eager resource cleanup, not a completion assertion. */
public interface InvocationResultObserver {
    default void onStart(RuntimeContext context) {}

    void onResult(RuntimeContext context, Msg result);
}
