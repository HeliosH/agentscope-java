/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.extensions.sandbox.cube;

import java.nio.file.Path;

/** A Cube {@code volumeMounts} descriptor persisted with sandbox state. */
public record CubeVolumeMount(
        String volumeId, String mountPath, boolean readOnly, boolean managed, String driver) {

    public CubeVolumeMount {
        if (volumeId == null || !volumeId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Invalid Cube volume id: " + volumeId);
        }
        if (mountPath == null
                || mountPath.isBlank()
                || !Path.of(mountPath).isAbsolute()
                || !Path.of(mountPath).normalize().toString().equals(mountPath)
                || "/".equals(mountPath)) {
            throw new IllegalArgumentException("Invalid Cube volume mount path: " + mountPath);
        }
        driver = driver == null || driver.isBlank() ? null : driver.trim();
    }

    /** Creates a reference to a deployment-provisioned Volume. */
    public static CubeVolumeMount existing(String volumeId, String mountPath, boolean readOnly) {
        return new CubeVolumeMount(volumeId, mountPath, readOnly, false, null);
    }

    /** Creates a Volume that the Cube client ensures exists before sandbox creation. */
    public static CubeVolumeMount managed(
            String volumeId, String mountPath, boolean readOnly, String driver) {
        return new CubeVolumeMount(volumeId, mountPath, readOnly, true, driver);
    }
}
