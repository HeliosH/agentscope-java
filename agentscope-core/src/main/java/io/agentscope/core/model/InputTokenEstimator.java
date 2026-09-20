/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.core.model;

import io.agentscope.core.message.Msg;
import java.util.List;

/** Trusted provider adapter estimating the entire input, including media and tool schemas. */
@FunctionalInterface
public interface InputTokenEstimator {
    long estimate(List<Msg> messages, List<ToolSchema> tools);
}
