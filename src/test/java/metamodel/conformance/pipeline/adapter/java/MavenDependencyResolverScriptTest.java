package metamodel.conformance.pipeline.adapter.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenDependencyResolverScriptTest {
    @TempDir
    Path temporary;

    @Test
    void resolvesClasspathThroughLockedDownContainerInMavenOrder() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        Files.writeString(project.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion></project>");
        Path bin = Files.createDirectories(temporary.resolve("bin"));
        Path arguments = temporary.resolve("docker-arguments.txt");
        Path fakeDocker = bin.resolve("docker");
        Files.writeString(fakeDocker, """
                #!/usr/bin/env bash
                set -euo pipefail
                printf '%%s\\n' "$*" > '%s'
                output_mount=''
                for argument in "$@"; do
                  case "$argument" in
                    type=bind,src=*,dst=/workspace/out)
                      output_mount="${argument#type=bind,src=}"
                      output_mount="${output_mount%%,dst=/workspace/out}"
                      ;;
                  esac
                done
                test -n "$output_mount"
                mkdir -p "$output_mount/repository/first" "$output_mount/repository/second"
                printf x > "$output_mount/repository/first/first.jar"
                printf y > "$output_mount/repository/second/second.jar"
                printf '/workspace/out/repository/second/second.jar:/workspace/out/repository/first/first.jar:/workspace/out/repository/second/second.jar\\n' > "$output_mount/classpath.txt"
                """.formatted(arguments));
        Files.setPosixFilePermissions(fakeDocker,
                PosixFilePermissions.fromString("rwxr-xr-x"));

        Path output = temporary.resolve("dependencies.txt");
        ProcessBuilder process = new ProcessBuilder(
                "bash", "scripts/resolve-maven-dependencies.sh",
                project.toString(), output.toString());
        process.redirectErrorStream(true);
        process.environment().put("PATH", bin + ":" + process.environment().get("PATH"));
        process.environment().put("MAVEN_DEPENDENCY_PLUGIN_VERSION", "3.11.0");
        process.environment().put("MAVEN_RESOLVER_IMAGE", "maven:3.9.16-eclipse-temurin-17");
        Process result = process.start();
        String log = new String(result.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, result.waitFor(), log);
        Path cache = Path.of(output + ".cache");
        assertEquals(List.of(
                        cache.resolve("repository/second/second.jar").toRealPath().toString(),
                        cache.resolve("repository/first/first.jar").toRealPath().toString()),
                Files.readAllLines(output));
        String invoked = Files.readString(arguments);
        assertTrue(invoked.contains("--read-only"));
        assertTrue(invoked.contains("--cap-drop=ALL"));
        assertTrue(invoked.contains("--security-opt=no-new-privileges"));
        assertTrue(invoked.contains("--pids-limit=256"));
        assertTrue(invoked.contains("maven:3.9.16-eclipse-temurin-17"));
        assertTrue(invoked.contains(
                "org.apache.maven.plugins:maven-dependency-plugin:3.11.0:build-classpath"));
    }

    @Test
    void requiresExplicitResolverConfigurationForMavenProjects() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        Files.writeString(project.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion></project>");
        Path output = temporary.resolve("dependencies.txt");
        ProcessBuilder process = new ProcessBuilder(
                "bash", "scripts/resolve-maven-dependencies.sh",
                project.toString(), output.toString());
        process.environment().remove("MAVEN_DEPENDENCY_PLUGIN_VERSION");
        process.environment().remove("MAVEN_RESOLVER_IMAGE");
        Process result = process.start();
        result.getInputStream().readAllBytes();
        result.getErrorStream().readAllBytes();
        assertEquals(64, result.waitFor());
    }

    @Test
    void multiModuleTreesFailClosedRatherThanFlatteningClasspaths() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        Files.writeString(project.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion></project>");
        Path module = Files.createDirectories(project.resolve("module"));
        Files.writeString(module.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion></project>");
        Path output = temporary.resolve("dependencies.txt");
        Process result = new ProcessBuilder(
                "bash", "scripts/resolve-maven-dependencies.sh",
                project.toString(), output.toString()).start();
        result.getInputStream().readAllBytes();
        result.getErrorStream().readAllBytes();
        assertEquals(65, result.waitFor());
        assertTrue(Files.exists(output));
        assertEquals(0, Files.size(output));
    }
}
