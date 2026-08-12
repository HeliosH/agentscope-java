/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.model.exception.BadRequestException;
import io.agentscope.core.model.exception.InternalServerException;
import org.junit.jupiter.api.Test;

class ExecutionConfigRetryPolicyTest {

    @Test
    void retriesProviderServerErrorsButNotBadRequests() {
        assertTrue(
                ExecutionConfig.RETRYABLE_ERRORS.test(
                        new InternalServerException("unavailable", 503, null, null)));
        assertFalse(
                ExecutionConfig.RETRYABLE_ERRORS.test(
                        new BadRequestException("bad request", null, null)));
    }
}
