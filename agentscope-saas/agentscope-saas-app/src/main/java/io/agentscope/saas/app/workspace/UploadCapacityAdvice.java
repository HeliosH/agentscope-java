/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.saas.app.workspace;

import java.util.Map;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class UploadCapacityAdvice {
    @ExceptionHandler(DataBufferLimitException.class)
    public ResponseEntity<Map<String, String>> capacityExceeded(DataBufferLimitException error) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(
                        Map.of(
                                "code",
                                "PAYLOAD_TOO_LARGE",
                                "message",
                                "Request payload exceeds the configured limit"));
    }

    @ExceptionHandler(
            io.agentscope.harness.agent.sandbox.WorkspaceTransferResources.CapacityException.class)
    public ResponseEntity<Map<String, String>> resourcesBusy(RuntimeException error) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "3")
                .body(
                        Map.of(
                                "code",
                                "FILE_TRANSFER_BUSY",
                                "message",
                                "File transfer capacity is temporarily unavailable"));
    }
}
