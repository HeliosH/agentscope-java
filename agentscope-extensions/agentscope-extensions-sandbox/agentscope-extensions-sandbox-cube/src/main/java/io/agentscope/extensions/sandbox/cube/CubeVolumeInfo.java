/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.extensions.sandbox.cube;

/** Cube persistent Volume metadata returned by the control-plane REST API. */
public record CubeVolumeInfo(String volumeId, String name, String token) {}
