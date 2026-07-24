/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.app.orchestration;

import io.agentscope.saas.orchestration.OrchestrationEventDispatcher;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Default in-process delivery adapter; enterprise message-bus adapters replace this bean. */
@Component
public class LocalOrchestrationEventDispatcher implements OrchestrationEventDispatcher {

    private final ApplicationEventPublisher publisher;

    public LocalOrchestrationEventDispatcher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void dispatch(OutboxEvent event) {
        publisher.publishEvent(event);
    }
}
