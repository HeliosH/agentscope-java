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
package io.agentscope.saas.app.config;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.path;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Serves the single-page console and provides SPA-style fallback routing so client-side routes
 * resolve to {@code index.html}. API and actuator paths are excluded so they reach their handlers.
 * When the frontend has not been built (the {@code frontend} Maven profile is off), the
 * classpath {@code static/index.html} resource is simply absent and these routes 404 — the JSON API
 * remains fully usable via curl.
 */
@Configuration
public class WebConfig {

    @Bean
    public RouterFunction<ServerResponse> spaRouter() {
        Resource index = new ClassPathResource("static/index.html");
        return RouterFunctions.route(
                GET("/").or(GET("/login")).or(GET("/chat")).and(path("/api/**").negate()),
                request -> {
                    if (!index.exists()) {
                        return ServerResponse.notFound().build();
                    }
                    return ServerResponse.ok().header("Content-Type", "text/html").bodyValue(index);
                });
    }
}
