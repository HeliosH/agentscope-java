/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TaskComplexityRouterTest {

    private final TaskComplexityRouter router = new TaskComplexityRouter();

    @Test
    void selectsTheLightestSufficientExecutionPath() {
        assertThat(router.route((TaskComplexityRouter.Signals) null))
                .isEqualTo(TaskComplexityRouter.Route.DIRECT);
        assertThat(router.route(signals(false, false, 1, 0)))
                .isEqualTo(TaskComplexityRouter.Route.SIMPLE_TOOL);
        assertThat(router.route(signals(false, false, 4, 1)))
                .isEqualTo(TaskComplexityRouter.Route.PLANNED);
        assertThat(router.route(signals(false, true, 0, 0)))
                .isEqualTo(TaskComplexityRouter.Route.APPROVAL_REQUIRED);
    }

    @Test
    void extractsRoutingSignalsFromUserRequests() {
        assertThat(router.route("解释一下 Java record")).isEqualTo(TaskComplexityRouter.Route.DIRECT);
        assertThat(router.route("读取文件 settings.json"))
                .isEqualTo(TaskComplexityRouter.Route.SIMPLE_TOOL);
        assertThat(router.route("先规划，然后并行生成 report.pdf 和 summary.md"))
                .isEqualTo(TaskComplexityRouter.Route.PLANNED);
        assertThat(router.route("将报告发布到生产环境"))
                .isEqualTo(TaskComplexityRouter.Route.APPROVAL_REQUIRED);
    }

    private static TaskComplexityRouter.Signals signals(
            boolean explicitPlan, boolean highRisk, int toolCalls, int parallelism) {
        return new TaskComplexityRouter.Signals(
                explicitPlan, highRisk, false, false, toolCalls, 30, 0, parallelism, 0);
    }
}
