/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WebConfigTest {

    @Test
    void fallsBackOnlyForClientRoutesAndLeavesStaticFilesToResourceHandling() {
        assertTrue(WebConfig.isClientRoute("/"));
        assertTrue(WebConfig.isClientRoute("/admin/models"));
        assertTrue(WebConfig.isClientRoute("/agents/123/settings"));

        assertFalse(WebConfig.isClientRoute("/chugou-mark.svg"));
        assertFalse(WebConfig.isClientRoute("/favicon.ico"));
        assertFalse(WebConfig.isClientRoute("/manifest.webmanifest"));
    }
}
