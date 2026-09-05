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
    void resolvesEachModuleThroughLockedDownContainerWithoutFlatteningClasspaths() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        Files.writeString(project.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion></project>");
        Path module = Files.createDirectories(project.resolve("module-a"));
        Files.writeString(module.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion></project>");

        Path bin = Files.createDirectories(temporary.resolve("bin"));
        Path arguments = temporary.resolve("docker-arguments.txt");
        Path fakeDocker = bin.resolve("docker");
        Files.writeString(fakeDocker, """
                #!/usr/bin/env bash
                set -euo pipefail
                printf '%%s\\n' "$*" >> '%s'
                output_mount=''
                output=''
                for argument in "$@"; do
                  case "$argument" in
                    type=bind,src=*,dst=/workspace/out)
                      output_mount="${argument#type=bind,src=}"
                      output_mount="${output_mount%%,dst=/workspace/out}"
                      ;;
                    -Dmdep.outputFile=*) output="${argument#*=}" ;;
                  esac
                done
                test -n "$output_mount"
                test -n "$output"
                relative="${output#/workspace/out/}"
                host_output="$output_mount/$relative"
                index="${relative##*/}"
                index="${index%%.txt}"
                mkdir -p "$(dirname "$host_output")" \
                  "$output_mount/repository/shared" \
                  "$output_mount/repository/$index"
                printf x > "$output_mount/repository/shared/shared.jar"
                printf y > "$output_mount/repository/$index/module.jar"
                printf '/workspace/out/repository/%s/module.jar:/workspace/out/repository/shared/shared.jar:/workspace/out/repository/%s/module.jar\\n' \
                  "$index" "$index" > "$host_output"
                """.formatted(arguments));
        Files.setPosixFilePermissions(fakeDocker,
                PosixFilePermissions.fromString("rwxr-xr-x"));

        Path output = temporary.resolve("dependencies.tsv");
        ProcessBuilder process = resolver(project, output, bin);
        Process result = process.start();
        String log = new String(result.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, result.waitFor(), log);
        Path cache = Path.of(output + ".cache");
        List<String> rows = Files.readAllLines(output);
        assertEquals(4, rows.size());
        assertEquals("module-a\t" + cache.resolve("repository/0/module.jar").toRealPath(), rows.get(0));
        assertEquals("module-a\t" + cache.resolve("repository/shared/shared.jar").toRealPath(), rows.get(1));
        assertEquals(".\t" + cache.resolve("repository/1/module.jar").toRealPath(), rows.get(2));
        assertEquals(".\t" + cache.resolve("repository/shared/shared.jar").toRealPath(), rows.get(3));

        List<String> invocations = Files.readAllLines(arguments);
        assertEquals(2, invocations.size());
        for (String invoked : invocations) {
            assertTrue(invoked.contains("--read-only"));
            assertTrue(invoked.contains("--cap-drop=ALL"));
            assertTrue(invoked.contains("--security-opt=no-new-privileges"));
            assertTrue(invoked.contains("--pids-limit=256"));
            assertTrue(invoked.contains("maven:3.9.16-eclipse-temurin-17"));
            assertTrue(invoked.contains(
                    "org.apache.maven.plugins:maven-dependency-plugin:3.11.0:build-classpath"));
        }
        assertTrue(invocations.stream().anyMatch(item -> item.contains("/workspace/source/module-a/pom.xml")));
        assertTrue(invocations.stream().anyMatch(item -> item.contains("/workspace/source/pom.xml")));
    }

    @Test
    void requiresExplicitResolverConfigurationForMavenProjects() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        Files.writeString(project.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion></project>");
        Path output = temporary.resolve("dependencies.tsv");
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
    void rejectsResolverOutputOutsideIsolatedMounts() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        Files.writeString(project.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion></project>");
        Path bin = Files.createDirectories(temporary.resolve("bin"));
        Path fakeDocker = bin.resolve("docker");
        Files.writeString(fakeDocker, """
                #!/usr/bin/env bash
                set -euo pipefail
                output_mount=''
                output=''
                for argument in "$@"; do
                  case "$argument" in
                    type=bind,src=*,dst=/workspace/out)
                      output_mount="${argument#type=bind,src=}"
                      output_mount="${output_mount%%,dst=/workspace/out}" ;;
                    -Dmdep.outputFile=*) output="${argument#*=}" ;;
                  esac
                done
                relative="${output#/workspace/out/}"
                mkdir -p "$output_mount/$(dirname "$relative")"
                printf '/outside/dependency.jar\\n' > "$output_mount/$relative"
                """);
        Files.setPosixFilePermissions(fakeDocker,
                PosixFilePermissions.fromString("rwxr-xr-x"));
        Path output = temporary.resolve("dependencies.tsv");

        Process result = resolver(project, output, bin).start();
        result.getInputStream().readAllBytes();
        result.getErrorStream().readAllBytes();

        assertEquals(70, result.waitFor());
        assertEquals(0, Files.size(output));
    }

    private ProcessBuilder resolver(Path project, Path output, Path bin) {
        ProcessBuilder process = new ProcessBuilder(
                "bash", "scripts/resolve-maven-dependencies.sh",
                project.toString(), output.toString());
        process.redirectErrorStream(true);
        process.environment().put("PATH", bin + ":" + process.environment().get("PATH"));
        process.environment().put("MAVEN_DEPENDENCY_PLUGIN_VERSION", "3.11.0");
        process.environment().put("MAVEN_RESOLVER_IMAGE", "maven:3.9.16-eclipse-temurin-17");
        return process;
    }
}
