/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.harness.agent.sandbox;

import java.util.List;

/** Receipt of a workspace scan. An incomplete receipt is never a successful publication. */
public record WorkspaceProjectionReport(
        int scannedFiles,
        int projectedFiles,
        int unchangedFiles,
        long transferredBytes,
        boolean completeScan,
        List<String> rejectedFiles) {
    public WorkspaceProjectionReport {
        rejectedFiles = List.copyOf(rejectedFiles);
    }

    public void verifyComplete() {
        if (!completeScan || !rejectedFiles.isEmpty()) {
            throw new IllegalStateException(
                    "Workspace projection incomplete: scanned="
                            + scannedFiles
                            + ", projected="
                            + projectedFiles
                            + ", rejected="
                            + String.join("; ", rejectedFiles));
        }
    }
}
