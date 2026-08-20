/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.model;

import io.agentscope.saas.app.config.SaasProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** AES-256-GCM envelope for model credentials persisted in the tenant database. */
@Component
public class ModelCredentialCipher {

    private static final String PREFIX = "v1:";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public ModelCredentialCipher(SaasProperties properties) {
        String encoded = properties.getModel().getManagement().getEncryptionKey();
        if (encoded == null || encoded.isBlank()) {
            this.key = null;
            return;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "saas.model.management.encryption-key must be base64 encoded", e);
        }
        if (decoded.length != 32) {
            throw new IllegalStateException(
                    "saas.model.management.encryption-key must decode to 32 bytes");
        }
        this.key = new SecretKeySpec(decoded, "AES");
    }

    public boolean isConfigured() {
        return key != null;
    }

    public String encrypt(String plaintext, UUID orgId, String modelId) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        requireKey();
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(orgId, modelId));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] envelope = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, envelope, 0, nonce.length);
            System.arraycopy(encrypted, 0, envelope, nonce.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(envelope);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt model credential", e);
        }
    }

    public String decrypt(String ciphertext, UUID orgId, String modelId) {
        if (ciphertext == null || ciphertext.isBlank()) {
            return null;
        }
        requireKey();
        if (!ciphertext.startsWith(PREFIX)) {
            throw new IllegalStateException("Unsupported model credential envelope");
        }
        try {
            byte[] envelope = Base64.getDecoder().decode(ciphertext.substring(PREFIX.length()));
            if (envelope.length <= NONCE_BYTES) {
                throw new IllegalStateException("Invalid model credential envelope");
            }
            byte[] nonce = new byte[NONCE_BYTES];
            byte[] encrypted = new byte[envelope.length - NONCE_BYTES];
            System.arraycopy(envelope, 0, nonce, 0, NONCE_BYTES);
            System.arraycopy(envelope, NONCE_BYTES, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(orgId, modelId));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Failed to decrypt model credential", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException(
                    "Model credential encryption is not configured; set"
                            + " SAAS_MODEL_MANAGEMENT_ENCRYPTION_KEY");
        }
    }

    private static byte[] aad(UUID orgId, String modelId) {
        return (orgId + ":" + modelId).getBytes(StandardCharsets.UTF_8);
    }
}
