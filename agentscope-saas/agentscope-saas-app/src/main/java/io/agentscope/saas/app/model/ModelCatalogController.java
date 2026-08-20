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
package io.agentscope.saas.app.model;

import io.agentscope.saas.core.tenant.TenantContext;
import io.agentscope.saas.core.tenant.TenantResolver;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Returns the safe, user-selectable subset of the effective organization model catalog. */
@RestController
public class ModelCatalogController {

    public record CatalogResponse(String defaultModelId, List<ModelCatalog.ModelOption> models) {}

    private final ModelCatalog catalog;
    private final TenantResolver tenantResolver;

    public ModelCatalogController(ModelCatalog catalog, TenantResolver tenantResolver) {
        this.catalog = catalog;
        this.tenantResolver = tenantResolver;
    }

    @GetMapping("/api/models")
    public Mono<CatalogResponse> models(@AuthenticationPrincipal Jwt jwt) {
        TenantContext tenant = tenantResolver.resolve(jwt != null ? jwt.getClaims() : Map.of());
        UUID orgId = UUID.fromString(tenant.orgId());
        return Mono.fromCallable(
                        () ->
                                new CatalogResponse(
                                        catalog.getDefaultId(orgId), catalog.getOptions(orgId)))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
