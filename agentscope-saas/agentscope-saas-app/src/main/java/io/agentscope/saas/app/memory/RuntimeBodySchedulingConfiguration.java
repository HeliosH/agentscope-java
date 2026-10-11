/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.agentscope.saas.app.memory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Object-store recovery must not block execution lease/deadline reconciliation. */
@Configuration(proxyBeanMethods = false)
public class RuntimeBodySchedulingConfiguration {
    @Bean(name = "runtimeBodyRecoveryScheduler", defaultCandidate = false)
    public ThreadPoolTaskScheduler runtimeBodyRecoveryScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("runtime-body-recovery-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(3);
        return scheduler;
    }
}
