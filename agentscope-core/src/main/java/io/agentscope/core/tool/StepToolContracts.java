/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.core.tool;

import java.util.LinkedHashMap;
import java.util.Map;

/** Contracts frozen before reasoning, checked again before any journal or external execution. */
public record StepToolContracts(Map<String, ToolContractSnapshot> tools, String hash) {
    public StepToolContracts {
        tools = Map.copyOf(tools);
        if (!java.util.Objects.equals(hash, StepSnapshot.fingerprint(tools)))
            throw new IllegalArgumentException("Tool catalog digest does not match its contracts");
    }

    public static StepToolContracts capture(Toolkit toolkit) {
        return capture(toolkit, toolkit.getToolNames());
    }

    public static StepToolContracts capture(Toolkit toolkit, java.util.Collection<String> names) {
        var tools = new LinkedHashMap<String, ToolContractSnapshot>();
        int bytes = 0;
        for (String name : names.stream().sorted().toList()) {
            if (toolkit.getTool(name) == null) continue;
            var contract = toolkit.captureToolContract(name);
            bytes +=
                    contract.descriptorJson()
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                            .length;
            if (tools.size() >= 1024 || bytes > 4194304)
                throw new IllegalArgumentException(
                        "Model step tool contracts exceed their bounded catalog");
            tools.put(name, contract);
        }
        return new StepToolContracts(tools, StepSnapshot.fingerprint(tools));
    }

    public StepToolContracts restrict(java.util.Collection<String> visible) {
        var selected = new LinkedHashMap<String, ToolContractSnapshot>();
        for (String name : visible.stream().sorted().toList())
            if (tools.containsKey(name)) selected.put(name, tools.get(name));
        return new StepToolContracts(selected, StepSnapshot.fingerprint(selected));
    }

    public void requireCurrent(String name, ToolContractSnapshot current) {
        var expected = tools.get(name);
        if (expected == null || !expected.hash().equals(current.hash()))
            throw new IllegalStateException("Tool contract changed after reasoning: " + name);
    }
}
