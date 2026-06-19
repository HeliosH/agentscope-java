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

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.saas.core.tenant.TenantContextHolder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskDecorator;

/**
 * Verifies {@link TenantAsyncConfig#tenantAwareTaskDecorator()} propagates the submitting thread's
 * org id onto the async worker thread and restores the worker's prior state afterward. This is the
 * fix for the silent RLS denial on {@code @Async} tenant-table writes (e.g. UsageService.record).
 */
class TenantAsyncTaskDecoratorTest {

    private final TaskDecorator decorator = new TenantAsyncConfig().tenantAwareTaskDecorator();

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
    }

    @Test
    void propagatesOrgIdToWorkerThread() throws Exception {
        String orgId = "00000000-0000-0000-0000-000000000001";
        TenantContextHolder.setOrgId(orgId);
        AtomicReference<String> seenOnWorker = new AtomicReference<>("unset");
        CountDownLatch done = new CountDownLatch(1);

        // Simulate the executor handing the decorated runnable to a worker thread.
        Runnable decorated =
                decorator.decorate(
                        () -> {
                            seenOnWorker.set(TenantContextHolder.getOrgId());
                            done.countDown();
                        });
        // Clear the submitting thread's holder to prove the value was captured, not read live.
        TenantContextHolder.clear();
        new Thread(decorated).start();

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seenOnWorker.get()).isEqualTo(orgId);
    }

    @Test
    void restoresWorkerPriorStateAfterRun() throws Exception {
        // Model the real @Async flow: a submitter thread (with its org id) hands the decorated
        // task to a worker thread (which has its own, different prior state). The decorator must
        // capture the submitter's id at decorate() time and restore the worker's prior state after.
        String submitterOrgId = "00000000-0000-0000-0000-000000000003";
        String workerOrgId = "00000000-0000-0000-0000-000000000002";
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> seenDuringRun = new AtomicReference<>();
        AtomicReference<String> afterRun = new AtomicReference<>();

        // Submitting thread: set its org id, then build the decorated runnable (capture happens
        // here).
        TenantContextHolder.setOrgId(submitterOrgId);
        Runnable decorated =
                decorator.decorate(() -> seenDuringRun.set(TenantContextHolder.getOrgId()));
        TenantContextHolder.clear();

        // Worker thread: has its own prior org id, runs the decorated task.
        Thread worker =
                new Thread(
                        () -> {
                            TenantContextHolder.setOrgId(workerOrgId);
                            decorated.run();
                            afterRun.set(TenantContextHolder.getOrgId());
                            done.countDown();
                        });
        worker.start();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        // Task ran with the submitter's captured id, not the worker's.
        assertThat(seenDuringRun.get()).isEqualTo(submitterOrgId);
        // Worker's pre-task value is restored, not left as the submitter's.
        assertThat(afterRun.get()).isEqualTo(workerOrgId);
    }

    @Test
    void nullSubmitterLeavesWorkerEmpty() throws Exception {
        // No tenant on the submitting thread → worker sees none (RLS denies, fail-closed).
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> seen = new AtomicReference<>("sentinel");
        Runnable decorated =
                decorator.decorate(
                        () -> {
                            seen.set(TenantContextHolder.getOrgId());
                            done.countDown();
                        });
        new Thread(decorated).start();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seen.get()).isNull();
    }
}
