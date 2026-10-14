/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.agentscope.harness.agent.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persistent mounts are verified caches of an explicit manifest, never a source of task inputs. */
public final class VolumeWorkspaceCache {
    private VolumeWorkspaceCache() {}

    private static final String PRUNE =
            """
            import base64,json,os,shutil,sys
            with open(sys.argv[1], 'rb') as f: manifest=json.loads(base64.b64decode(f.read()))
            root=manifest['root']
            if not os.path.isabs(root) or os.path.realpath(root)!=root or os.getcwd()!=root:
                raise RuntimeError('SCOPED_WORKSPACE_ROOT_CHANGED')
            keep=set(manifest['paths'])
            parents={'inputs','outputs'}
            for path in keep:
                bits=path.split('/')
                for i in range(1,len(bits)): parents.add('/'.join(bits[:i]))
            for directory, dirs, files in os.walk(root,topdown=True,followlinks=False):
                for name in list(dirs):
                    path=os.path.join(directory,name)
                    rel=os.path.relpath(path,root)
                    if os.path.islink(path):
                        os.unlink(path); dirs.remove(name)
                    elif rel not in parents:
                        shutil.rmtree(path); dirs.remove(name)
                for name in files:
                    path=os.path.join(directory,name)
                    rel=os.path.relpath(path,root)
                    if rel not in keep or os.path.islink(path) or not os.path.isfile(path): os.unlink(path)
            for directory in ('inputs','outputs'): os.makedirs(os.path.join(root,directory),exist_ok=True)
            """;

    public static List<WorkspaceRestorePlan.WorkspaceFile> prepare(
            RuntimeContext context,
            Sandbox sandbox,
            WorkspaceRestorePlan plan,
            WorkspaceTransferResources resources)
            throws Exception {
        var state = sandbox.getState();
        if (state.getWorkspaceScopeKey() == null || state.getWorkspaceBaseRoot() == null)
            throw new IllegalStateException("Persistent workspace is not Run scoped");
        String root = state.getWorkspaceSpec().getRoot();
        var base = java.nio.file.Path.of(state.getWorkspaceBaseRoot()).normalize();
        var actual = java.nio.file.Path.of(root).normalize();
        var expected =
                base.resolve(".agentscope/runs")
                        .resolve(
                                io.agentscope.core.tool.StepSnapshot.fingerprint(
                                        state.getWorkspaceScopeKey()));
        if (!actual.equals(expected))
            throw new IllegalStateException(
                    "Persistent workspace must stay inside its Run directory");
        String encoded =
                Base64.getEncoder()
                        .encodeToString(
                                JsonUtils.getJsonCodec()
                                        .toJson(
                                                Map.of(
                                                        "root",
                                                        root,
                                                        "paths",
                                                        plan.files().stream()
                                                                .map(
                                                                        WorkspaceRestorePlan
                                                                                        .WorkspaceFile
                                                                                ::path)
                                                                .toList()))
                                        .getBytes(StandardCharsets.UTF_8));
        String temporary = "/tmp/agentscope-volume-cache-" + UUID.randomUUID();
        try {
            for (int offset = 0; offset < encoded.length(); offset += 4000)
                exec(
                        sandbox,
                        context,
                        "printf '%s' "
                                + quote(
                                        encoded.substring(
                                                offset, Math.min(encoded.length(), offset + 4000)))
                                + (offset == 0 ? " > " : " >> ")
                                + quote(temporary));
            exec(sandbox, context, "python3 -c " + quote(PRUNE) + " " + quote(temporary));
        } finally {
            exec(sandbox, context, "rm -f -- " + quote(temporary));
        }
        var cached = new HashSet<Integer>();
        for (int offset = 0; offset < plan.files().size(); offset += 32) {
            StringBuilder command = new StringBuilder();
            for (int i = offset; i < Math.min(plan.files().size(), offset + 32); i++) {
                var file = plan.files().get(i);
                String path = quote(file.path());
                command.append("if test -f ")
                        .append(path)
                        .append(" && test ! -L ")
                        .append(path)
                        .append(" && [ \"$(wc -c < ")
                        .append(path)
                        .append(")\" -eq ")
                        .append(quote(Long.toString(file.size())))
                        .append(
                                " ] && [ \"$(if command -v sha256sum >/dev/null 2>&1; then"
                                        + " sha256sum -- ")
                        .append(path)
                        .append("; else shasum -a 256 -- ")
                        .append(path)
                        .append("; fi | cut -d ' ' -f1)\" = ")
                        .append(quote(file.sha256()))
                        .append(" ]; then printf '")
                        .append(i)
                        .append("\\n'; fi\n");
            }
            String output = exec(sandbox, context, command.toString());
            for (String line : output.split("\\R"))
                if (!line.isBlank()) {
                    int index = Integer.parseInt(line.trim());
                    if (index < offset || index >= Math.min(plan.files().size(), offset + 32))
                        throw new IllegalStateException("Invalid volume cache receipt");
                    cached.add(index);
                }
        }
        var missing = new ArrayList<WorkspaceRestorePlan.WorkspaceFile>();
        for (int i = 0; i < plan.files().size(); i++) {
            var file = plan.files().get(i);
            if (cached.contains(i)) {
                // A matching volume copy cannot hide a missing or corrupt authoritative object.
                try (var permit = resources.acquire()) {
                    file.transferTo(
                            java.io.OutputStream.nullOutputStream(), resources.bufferBytes());
                }
            } else missing.add(file);
        }
        return List.copyOf(missing);
    }

    private static String exec(Sandbox sandbox, RuntimeContext context, String command)
            throws Exception {
        var result = sandbox.exec(context, command, 30);
        if (!result.ok())
            throw new IllegalStateException("SCOPED_WORKSPACE_CACHE_FAILED: " + result.stderr());
        return result.stdout() == null ? "" : result.stdout();
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
