/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.domain.repository;

import io.agentscope.saas.domain.model.AuditLogEntity;
import java.util.List;
import java.util.UUID;

/** Domain persistence port for immutable administrative audit events. */
public interface AuditLogRepository {

    AuditLogEntity save(AuditLogEntity event);

    List<AuditLogEntity> findAdminAuditLogs(
            UUID orgId, UUID actor, String action, String resourcePrefix, int limit);
}
