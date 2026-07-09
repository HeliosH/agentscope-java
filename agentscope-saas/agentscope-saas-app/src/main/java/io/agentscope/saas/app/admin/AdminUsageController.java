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
package io.agentscope.saas.app.admin;

import static io.agentscope.saas.app.admin.AdminSecurity.normalize;
import static io.agentscope.saas.app.admin.AdminSecurity.orgId;
import static io.agentscope.saas.app.admin.AdminSecurity.parseOptionalUuid;
import static io.agentscope.saas.app.admin.AdminSecurity.requireOrgAdmin;

import io.agentscope.saas.core.persistence.repo.UsageRecordRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Org-admin metering dashboard API backed by durable usage records. */
@RestController
@RequestMapping("/api/admin/usage")
public class AdminUsageController {

    private final UsageRecordRepository repository;

    public AdminUsageController(UsageRecordRepository repository) {
        this.repository = repository;
    }

    public record UsageSummaryView(
            String metric,
            String model,
            long records,
            long totalValue,
            OffsetDateTime firstRecordedAt,
            OffsetDateTime lastRecordedAt) {}

    @GetMapping("/summary")
    public Mono<ResponseEntity<List<UsageSummaryView>>> summary(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String metric,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        requireOrgAdmin(jwt);
        UUID orgId = orgId(jwt);
        UUID parsedUserId = parseOptionalUuid("userId", userId);
        String normalizedMetric = normalize(metric);
        OffsetDateTime fromTs = parseTime("from", from);
        OffsetDateTime toTs = parseTime("to", to);
        return Mono.fromCallable(
                        () ->
                                ResponseEntity.ok(
                                        repository
                                                .aggregateUsage(
                                                        orgId,
                                                        parsedUserId,
                                                        normalizedMetric,
                                                        fromTs,
                                                        toTs)
                                                .stream()
                                                .map(AdminUsageController::toView)
                                                .toList()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static UsageSummaryView toView(UsageRecordRepository.UsageAggregate aggregate) {
        return new UsageSummaryView(
                aggregate.getMetric(),
                aggregate.getModel(),
                aggregate.getRecords(),
                aggregate.getTotalValue(),
                aggregate.getFirstRecordedAt(),
                aggregate.getLastRecordedAt());
    }

    private static OffsetDateTime parseTime(String name, String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(normalized);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, name + " must be an ISO-8601 offset datetime");
        }
    }
}
