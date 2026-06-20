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
package io.agentscope.saas.app.template;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Registry of starter agent templates, loaded once at startup from the classpath under
 * {@code templates/<id>/template.json}. Read-only global catalog (no org-scoping, no per-tenant
 * overrides) — ported from paw's {@code TemplateRegistry}, dropping the cwd-override source and
 * the {@code instantiate} workspace copy (the SaaS frontend reads templates only; agent creation
 * scaffolds the workspace itself).
 *
 * <p>{@link #instantiateInto} copies a template's files into a per-user {@link AbstractFilesystem}
 * with write-if-missing semantics, used when creating an agent from a template.
 */
@Component
public class TemplateRegistry {

    private static final Logger log = LoggerFactory.getLogger(TemplateRegistry.class);
    private static final int PREVIEW_CHARS = 600;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final RuntimeContext FS_RC = RuntimeContext.empty();

    private final ResourcePatternResolver resolver;
    private final Map<String, ClasspathTemplate> classpathTemplates = new LinkedHashMap<>();

    public TemplateRegistry() {
        this.resolver = new PathMatchingResourcePatternResolver();
    }

    @PostConstruct
    void scanClasspath() {
        try {
            Resource[] manifests = resolver.getResources("classpath*:templates/*/template.json");
            for (Resource manifest : manifests) {
                try (InputStream in = manifest.getInputStream()) {
                    TemplateMetadata meta = MAPPER.readValue(in, TemplateMetadata.class);
                    if (meta == null || meta.id() == null || meta.id().isBlank()) {
                        log.warn("Skipping classpath template missing id: {}", safeUri(manifest));
                        continue;
                    }
                    URI rootUri = parentUri(manifest.getURI());
                    classpathTemplates.put(meta.id(), new ClasspathTemplate(meta, rootUri));
                } catch (Exception e) {
                    log.warn(
                            "Failed to read classpath template {}: {}",
                            safeUri(manifest),
                            e.getMessage());
                }
            }
            log.info(
                    "TemplateRegistry: discovered {} bundled template(s): {}",
                    classpathTemplates.size(),
                    classpathTemplates.keySet());
        } catch (IOException e) {
            log.warn("TemplateRegistry: failed to scan classpath templates: {}", e.getMessage());
        }
    }

    /** Lists all templates (id, name, description, tags, AGENTS.md preview). */
    public List<TemplateSummary> list() {
        List<TemplateSummary> out = new ArrayList<>();
        for (ClasspathTemplate t : classpathTemplates.values()) {
            out.add(summarize(t.metadata, classpathPreview(t)));
        }
        return out;
    }

    /** Full file listing for a template, or empty if unknown. */
    public Optional<TemplateDetail> get(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        ClasspathTemplate cp = classpathTemplates.get(id);
        if (cp == null) return Optional.empty();
        return Optional.of(detailFromClasspath(cp));
    }

    /**
     * Copies a template's files (except {@code template.json}) into the given workspace filesystem
     * with write-if-missing semantics. Returns {@code false} if the template id is unknown.
     */
    public boolean instantiateInto(String id, AbstractFilesystem fs) {
        if (id == null || id.isBlank()) return false;
        ClasspathTemplate cp = classpathTemplates.get(id);
        if (cp == null) return false;
        try {
            for (LoadedFile lf : loadClasspathFiles(cp)) {
                if ("template.json".equals(lf.relPath())) continue;
                String rel = lf.relPath();
                if (rel.endsWith("/.gitkeep")) continue;
                String abs = "/" + rel;
                if (fs.exists(FS_RC, abs)) continue;
                writeUtf8(fs, rel, new String(lf.content(), StandardCharsets.UTF_8));
            }
            return true;
        } catch (IOException e) {
            log.warn("Failed to instantiate template '{}': {}", id, e.getMessage());
            return false;
        }
    }

    // -----------------------------------------------------------------
    //  Detail / preview builders
    // -----------------------------------------------------------------

    private TemplateDetail detailFromClasspath(ClasspathTemplate t) {
        List<TemplateFile> files = new ArrayList<>();
        try {
            for (LoadedFile lf : loadClasspathFiles(t)) {
                if ("template.json".equals(lf.relPath())) continue;
                files.add(
                        new TemplateFile(
                                lf.relPath(), new String(lf.content(), StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            log.warn(
                    "Failed to enumerate classpath template '{}': {}",
                    t.metadata.id(),
                    e.getMessage());
        }
        files.sort(Comparator.comparing(TemplateFile::path));
        return new TemplateDetail(
                t.metadata.id(),
                t.metadata.displayName(),
                t.metadata.description(),
                t.metadata.tagsOrEmpty(),
                files);
    }

    private String classpathPreview(ClasspathTemplate t) {
        URL url = resourceUrl(t, "AGENTS.md");
        if (url == null) return "";
        try (InputStream in = url.openStream()) {
            return truncate(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return "";
        }
    }

    private List<LoadedFile> loadClasspathFiles(ClasspathTemplate t) throws IOException {
        URI rootUri = t.rootUri;
        String scheme = rootUri.getScheme();
        List<LoadedFile> out = new ArrayList<>();
        if ("file".equals(scheme)) {
            Path root = Paths.get(rootUri);
            if (!Files.isDirectory(root)) return out;
            try (Stream<Path> walk = Files.walk(root)) {
                List<Path> entries = walk.sorted().toList();
                for (Path p : entries) {
                    if (Files.isDirectory(p)) continue;
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    out.add(new LoadedFile(rel, Files.readAllBytes(p)));
                }
            }
        } else if ("jar".equals(scheme)) {
            String s = rootUri.toString();
            int bang = s.indexOf("!/");
            if (bang < 0) return out;
            URI jarFile = URI.create(s.substring(0, bang + 2));
            String inJarPath = s.substring(bang + 2);
            if (!inJarPath.endsWith("/")) inJarPath = inJarPath + "/";
            try (FileSystem jfs = openOrCreateJarFs(jarFile)) {
                Path root = jfs.getPath(inJarPath);
                if (!Files.isDirectory(root)) return out;
                try (Stream<Path> walk = Files.walk(root)) {
                    List<Path> entries = walk.sorted().toList();
                    for (Path p : entries) {
                        if (Files.isDirectory(p)) continue;
                        String rel = root.relativize(p).toString().replace('\\', '/');
                        out.add(new LoadedFile(rel, Files.readAllBytes(p)));
                    }
                }
            }
        } else {
            Resource[] children =
                    resolver.getResources("classpath*:templates/" + t.metadata.id() + "/**");
            String prefix = "templates/" + t.metadata.id() + "/";
            for (Resource child : children) {
                if (!child.isReadable()) continue;
                URI uri = safeUri(child);
                if (uri == null) continue;
                String us = uri.toString();
                int idx = us.indexOf(prefix);
                if (idx < 0) continue;
                String rel = us.substring(idx + prefix.length());
                if (rel.isEmpty() || rel.endsWith("/")) continue;
                try (InputStream in = child.getInputStream()) {
                    out.add(new LoadedFile(rel, in.readAllBytes()));
                }
            }
        }
        return out;
    }

    private static FileSystem openOrCreateJarFs(URI jarFile) throws IOException {
        try {
            return FileSystems.getFileSystem(jarFile);
        } catch (Exception ignore) {
            return FileSystems.newFileSystem(jarFile, Map.of());
        }
    }

    private static URL resourceUrl(ClasspathTemplate t, String relPath) {
        return Thread.currentThread()
                .getContextClassLoader()
                .getResource("templates/" + t.metadata.id() + "/" + relPath);
    }

    private static URI parentUri(URI manifestUri) {
        String s = manifestUri.toString();
        int slash = s.lastIndexOf('/');
        return URI.create(s.substring(0, slash + 1));
    }

    private static URI safeUri(Resource r) {
        try {
            return r.getURI();
        } catch (IOException e) {
            return null;
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= PREVIEW_CHARS ? s : s.substring(0, PREVIEW_CHARS);
    }

    @SuppressWarnings("unused")
    private static void writeAtomic(Path target, byte[] content) throws IOException {
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, content);
        try {
            Files.move(
                    tmp,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeUtf8(
            io.agentscope.harness.agent.filesystem.AbstractFilesystem fs,
            String rel,
            String content) {
        List<io.agentscope.harness.agent.filesystem.model.FileUploadResponse> ur =
                fs.uploadFiles(
                        FS_RC,
                        java.util.List.of(
                                Map.entry(rel, content.getBytes(StandardCharsets.UTF_8))));
        if (ur.isEmpty() || !ur.get(0).isSuccess()) {
            throw new IllegalStateException(
                    "Failed to write template file "
                            + rel
                            + ": "
                            + (ur.isEmpty() ? "no response" : ur.get(0).error()));
        }
    }

    private static TemplateSummary summarize(TemplateMetadata meta, String preview) {
        return new TemplateSummary(
                meta.id(),
                meta.displayName(),
                meta.description(),
                meta.tagsOrEmpty(),
                preview != null ? preview : "");
    }

    // -----------------------------------------------------------------
    //  Internal records
    // -----------------------------------------------------------------

    private record ClasspathTemplate(TemplateMetadata metadata, URI rootUri) {}

    private record LoadedFile(String relPath, byte[] content) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TemplateMetadata(String id, String name, String description, List<String> tags) {
        String displayName() {
            return name != null && !name.isBlank() ? name : id;
        }

        List<String> tagsOrEmpty() {
            return tags != null ? tags : List.of();
        }
    }

    // -----------------------------------------------------------------
    //  Public records (API shape)
    // -----------------------------------------------------------------

    public record TemplateSummary(
            String id,
            String name,
            String description,
            List<String> tags,
            String agentsMdPreview) {}

    public record TemplateDetail(
            String id,
            String name,
            String description,
            List<String> tags,
            List<TemplateFile> files) {}

    public record TemplateFile(String path, String content) {}
}
