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
package io.agentscope.saas.app.marketplace;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@code saas.marketplace.*} and exposes a {@link MarketplaceRegistry.MarketplaceProperties}
 * bean for the git-clone root. The clone root holds per-org git working copies so tenants never
 * share clone directories; defaults to a temp dir under {@code java.io.tmpdir}.
 */
@Configuration
@ConfigurationProperties(prefix = "saas.marketplace")
public class MarketplaceConfig {

    /** Root directory for git marketplace clones, partitioned by org id. */
    private String gitCloneRoot =
            Paths.get(System.getProperty("java.io.tmpdir"), "saas-marketplaces").toString();

    public String getGitCloneRoot() {
        return gitCloneRoot;
    }

    public void setGitCloneRoot(String gitCloneRoot) {
        this.gitCloneRoot = gitCloneRoot;
    }

    @Bean
    public MarketplaceRegistry.MarketplaceProperties marketplaceProperties() {
        return new MarketplaceRegistry.MarketplaceProperties(Path.of(gitCloneRoot));
    }
}
