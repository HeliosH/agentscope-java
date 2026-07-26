/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.dal.mybatis.admin;

import java.util.UUID;

/** Flattened dependency result and immutable artifact projection for task context assembly. */
public record TaskDependencyData(
        UUID taskId,
        String title,
        String outputJson,
        UUID artifactId,
        String logicalPath,
        UUID fileVersionId) {}
