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

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Returns the safe, user-selectable subset of the deploy-time model catalog. */
@RestController
public class ModelCatalogController {

    public record CatalogResponse(String defaultModelId, List<ModelCatalog.ModelOption> models) {}

    private final ModelCatalog catalog;

    public ModelCatalogController(ModelCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/api/models")
    public CatalogResponse models() {
        return new CatalogResponse(catalog.getDefaultId(), catalog.getOptions());
    }
}
