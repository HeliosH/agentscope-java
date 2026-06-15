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
package io.agentscope.saas.core.usage;

import io.agentscope.saas.app.persistence.entity.UsageRecordEntity;
import io.agentscope.saas.app.persistence.repo.UsageRecordRepository;
import io.agentscope.saas.core.tenant.TenantContext;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Records resource usage metrics (metering only — no billing). Writes are best-effort and run
 * asynchronously so they never block or fail an agent run.
 */
@Service
public class UsageService {

    private static final Logger log = LoggerFactory.getLogger(UsageService.class);

    private final UsageRecordRepository repository;

    public UsageService(UsageRecordRepository repository) {
        this.repository = repository;
    }

    /** Record a usage metric for the given tenant. */
    @Async
    public void record(TenantContext tenant, String metric, long value, String model) {
        if (tenant == null || tenant.orgId() == null || tenant.userId() == null) {
            return; // anonymous; nothing to attribute
        }
        try {
            UsageRecordEntity entity = new UsageRecordEntity();
            entity.setOrgId(UUID.fromString(tenant.orgId()));
            entity.setUserId(UUID.fromString(tenant.userId()));
            entity.setMetric(metric);
            entity.setValue(value);
            entity.setModel(model);
            repository.save(entity);
        } catch (Exception e) {
            log.warn("Failed to record usage metric {}={}: {}", metric, value, e.getMessage());
        }
    }
}
