/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.saas.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Incremental architecture guard for the DDD and MyBatis migration. */
class DataAccessArchitectureTest {

    @Test
    void applicationBusinessCodeCannotUseJdbcTemplate() throws IOException {
        Path sourceRoot = Path.of("src/main/java");
        Set<String> users =
                javaFiles(sourceRoot).stream()
                        .filter(DataAccessArchitectureTest::importsJdbcTemplate)
                        .map(sourceRoot::relativize)
                        .map(DataAccessArchitectureTest::portablePath)
                        .collect(Collectors.toSet());

        assertThat(users).isEmpty();
    }

    @Test
    void applicationJavaSqlUsageIsLimitedToTenantConnectionInfrastructure() throws IOException {
        Path sourceRoot = Path.of("src/main/java");
        Set<String> users =
                javaFiles(sourceRoot).stream()
                        .filter(file -> read(file).contains("import java.sql."))
                        .map(sourceRoot::relativize)
                        .map(DataAccessArchitectureTest::portablePath)
                        .collect(Collectors.toSet());

        assertThat(users)
                .containsExactly("io/agentscope/saas/app/config/TenantAwareDataSourceConfig.java");
    }

    @Test
    void domainRemainsFrameworkIndependent() throws IOException {
        Path domainRoot = Path.of("../agentscope-saas-domain/src/main/java");
        assertThat(javaFiles(domainRoot))
                .allSatisfy(
                        file ->
                                assertThat(read(file))
                                        .doesNotContain(
                                                "import org.springframework.",
                                                "import org.apache.ibatis.",
                                                "import jakarta.persistence.",
                                                "import javax.persistence."));
    }

    @Test
    void myBatisMappersRemainInsideDalModule() throws IOException {
        Path saasRoot = Path.of("..");
        Set<String> mapperSources =
                javaFiles(saasRoot).stream()
                        .filter(
                                file ->
                                        portablePath(saasRoot.relativize(file))
                                                .contains("/src/main/java/"))
                        .filter(
                                file ->
                                        read(file)
                                                .contains("import org.apache.ibatis.annotations."))
                        .map(saasRoot::relativize)
                        .map(DataAccessArchitectureTest::portablePath)
                        .collect(Collectors.toSet());

        assertThat(mapperSources)
                .allMatch(path -> path.startsWith("agentscope-saas-dal/src/main/java/"));
    }

    @Test
    void tenantTaskMapperCannotUseAdministrativeSession() throws IOException {
        Path dalRoot = Path.of("../agentscope-saas-dal/src/main/java");
        Set<String> durableTaskSources =
                javaFiles(dalRoot).stream()
                        .filter(
                                file ->
                                        Set.of("DurableTaskData.java", "DurableTaskMapper.java")
                                                .contains(file.getFileName().toString()))
                        .map(dalRoot::relativize)
                        .map(DataAccessArchitectureTest::portablePath)
                        .collect(Collectors.toSet());

        assertThat(durableTaskSources)
                .isNotEmpty()
                .allMatch(path -> path.startsWith("io/agentscope/saas/dal/mybatis/tenant/"));
        assertThat(
                        read(
                                Path.of(
                                        "src/main/java/io/agentscope/saas/app/config/"
                                                + "TenantMyBatisDataAccessConfig.java")))
                .contains(
                        "io.agentscope.saas.dal.mybatis.tenant",
                        "sqlSessionTemplateRef = \"tenantSqlSessionTemplate\"");
    }

    private static Set<Path> javaFiles(Path root) throws IOException {
        try (var files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .collect(Collectors.toSet());
        }
    }

    private static boolean importsJdbcTemplate(Path file) {
        return read(file).contains("import org.springframework.jdbc.core.JdbcTemplate;");
    }

    private static String portablePath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot inspect source file: " + file, e);
        }
    }
}
