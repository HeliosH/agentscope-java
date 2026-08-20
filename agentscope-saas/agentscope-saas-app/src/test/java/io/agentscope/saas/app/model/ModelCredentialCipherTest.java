/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.saas.app.config.SaasProperties;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModelCredentialCipherTest {

    @Test
    void encryptsWithRandomNonceAndBindsCiphertextToTenantAndModel() {
        SaasProperties properties = new SaasProperties();
        properties
                .getModel()
                .getManagement()
                .setEncryptionKey("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        ModelCredentialCipher cipher = new ModelCredentialCipher(properties);
        UUID orgId = UUID.randomUUID();

        String first = cipher.encrypt("secret", orgId, "qwen");
        String second = cipher.encrypt("secret", orgId, "qwen");

        assertNotEquals(first, second);
        assertEquals("secret", cipher.decrypt(first, orgId, "qwen"));
        assertThrows(
                IllegalStateException.class,
                () -> cipher.decrypt(first, UUID.randomUUID(), "qwen"));
        assertThrows(
                IllegalStateException.class, () -> cipher.decrypt(first, orgId, "other-model"));
    }
}
