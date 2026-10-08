/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.workspace;

/** No storage SDK diagnostics or object keys are exposed to clients. */
public class FilePublicationRetryableException extends IllegalStateException {
    public FilePublicationRetryableException() {
        super("FILE_PUBLICATION_STORAGE_UNAVAILABLE");
    }
}
