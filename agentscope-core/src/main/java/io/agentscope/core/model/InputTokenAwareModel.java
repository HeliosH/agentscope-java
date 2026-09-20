/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.core.model;

import io.agentscope.core.message.Msg;
import java.util.List;

/** Model-specific estimation used consistently for compaction and request admission. */
public interface InputTokenAwareModel extends Model {
    long estimateInputTokens(List<Msg> messages, List<ToolSchema> tools);
}
