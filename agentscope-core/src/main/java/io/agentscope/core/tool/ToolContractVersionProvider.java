/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.core.tool;

/** Explicit behavior/configuration identity for mutable or remotely backed annotated tool objects. */
public interface ToolContractVersionProvider {
    String getContractVersion();
}
