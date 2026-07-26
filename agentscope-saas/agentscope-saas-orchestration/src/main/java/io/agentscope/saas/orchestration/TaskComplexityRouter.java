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

import java.util.Locale;
import java.util.regex.Pattern;

/** Deterministic first-pass routing before an optional model planner is invoked. */
public final class TaskComplexityRouter {

    public static final String ATTR_ROUTE = "taskComplexityRoute";

    private static final Pattern FILE_EXTENSION =
            Pattern.compile("\\b[^\\s/]+\\.[a-zA-Z0-9]{1,8}\\b");

    public Route route(Signals signals) {
        if (signals == null) {
            return Route.DIRECT;
        }
        if (signals.highRisk() || signals.externalWrite() || signals.sensitiveData()) {
            return Route.APPROVAL_REQUIRED;
        }
        if (signals.explicitPlan()
                || signals.estimatedToolCalls() > 3
                || signals.estimatedDurationSeconds() > 120
                || signals.expectedArtifacts() > 1
                || signals.parallelOpportunities() > 0
                || signals.distinctFiles() > 2) {
            return Route.PLANNED;
        }
        if (signals.estimatedToolCalls() > 0
                || signals.expectedArtifacts() > 0
                || signals.distinctFiles() > 0) {
            return Route.SIMPLE_TOOL;
        }
        return Route.DIRECT;
    }

    /**
     * Extracts conservative signals from a raw request. This is a routing boundary, not semantic
     * planning; the structured planner remains responsible for the actual DAG.
     */
    public Route route(String request) {
        if (request == null || request.isBlank()) {
            return Route.DIRECT;
        }
        String text = request.toLowerCase(Locale.ROOT);
        boolean explicitPlan =
                containsAny(
                        text,
                        "制定计划",
                        "先规划",
                        "任务拆解",
                        "分解任务",
                        "execution plan",
                        "make a plan",
                        "plan first");
        boolean externalWrite =
                containsAny(
                        text,
                        "发布到",
                        "部署到",
                        "发送邮件",
                        "对外发送",
                        "外发",
                        "publish to",
                        "deploy to",
                        "send email");
        boolean destructive =
                containsAny(
                        text,
                        "删除数据",
                        "清空数据",
                        "drop table",
                        "delete production",
                        "remove production");
        boolean sensitive =
                containsAny(
                        text,
                        "身份证",
                        "银行卡",
                        "客户隐私",
                        "生产密钥",
                        "个人敏感",
                        "credential",
                        "private key",
                        "personally identifiable");
        int files = (int) FILE_EXTENSION.matcher(text).results().count();
        boolean artifact =
                files > 0
                        || containsAny(
                                text,
                                "生成报告",
                                "生成文档",
                                "生成文件",
                                "导出",
                                "write report",
                                "create document",
                                "generate file",
                                "export");
        boolean multipleSteps =
                containsAny(
                        text,
                        "然后",
                        "之后",
                        "分别",
                        "同时",
                        "并行",
                        "多步骤",
                        "全流程",
                        " and then ",
                        "multiple steps",
                        "in parallel",
                        "end-to-end");
        boolean toolIntent =
                artifact
                        || containsAny(
                                text,
                                "搜索",
                                "查询",
                                "读取文件",
                                "修改文件",
                                "执行命令",
                                "search",
                                "look up",
                                "read file",
                                "edit file",
                                "run command");
        return route(
                new Signals(
                        explicitPlan,
                        destructive,
                        externalWrite,
                        sensitive,
                        multipleSteps ? 4 : toolIntent ? 1 : 0,
                        multipleSteps ? 180 : 0,
                        artifact ? Math.max(1, files) : 0,
                        containsAny(text, "并行", "同时", "分别", "in parallel") ? 1 : 0,
                        files));
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    public enum Route {
        DIRECT,
        SIMPLE_TOOL,
        PLANNED,
        APPROVAL_REQUIRED
    }

    public record Signals(
            boolean explicitPlan,
            boolean highRisk,
            boolean externalWrite,
            boolean sensitiveData,
            int estimatedToolCalls,
            long estimatedDurationSeconds,
            int expectedArtifacts,
            int parallelOpportunities,
            int distinctFiles) {}
}
