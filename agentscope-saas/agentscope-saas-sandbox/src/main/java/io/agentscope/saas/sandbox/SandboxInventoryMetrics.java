/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.saas.sandbox;

import io.agentscope.saas.core.persistence.repo.SandboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.time.OffsetDateTime;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Periodically refreshed sandbox inventory gauges derived from the tracking table. */
@Component
public class SandboxInventoryMetrics {

    private static final String UNKNOWN = "unknown";

    private final SandboxRepository sandboxRepository;
    private final MultiGauge poolSize;
    private final MultiGauge expiredActive;

    public SandboxInventoryMetrics(MeterRegistry registry, SandboxRepository sandboxRepository) {
        this.sandboxRepository = sandboxRepository;
        this.poolSize =
                MultiGauge.builder("saas.sandbox.pool.size")
                        .description("Sandbox tracking rows grouped by backend type and status")
                        .register(registry);
        this.expiredActive =
                MultiGauge.builder("saas.sandbox.pool.expired_active")
                        .description("Active sandbox tracking rows whose lease has expired")
                        .register(registry);
    }

    public void refresh() {
        poolSize.register(
                sandboxRepository.countBySandboxTypeAndStatus().stream()
                        .map(
                                row ->
                                        MultiGauge.Row.of(
                                                Tags.of(
                                                        "type",
                                                        normalize(row.getSandboxType()),
                                                        "status",
                                                        normalize(row.getStatus())),
                                                row.getCount()))
                        .toList(),
                true);
        expiredActive.register(
                sandboxRepository.countExpiredActiveBySandboxType(OffsetDateTime.now()).stream()
                        .map(
                                row ->
                                        MultiGauge.Row.of(
                                                Tags.of("type", normalize(row.getSandboxType())),
                                                row.getCount()))
                        .toList(),
                true);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
