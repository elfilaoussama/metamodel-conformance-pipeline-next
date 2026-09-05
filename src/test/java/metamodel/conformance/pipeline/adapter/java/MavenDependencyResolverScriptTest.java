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
    @TempDir Path temporary;

    @Test
    void resolvesExactMainAndTestSourceSetClasspathsWithoutFlattening() throws Exception {
        Path project = mavenModule(temporary.resolve("project"), "RootType");
        Path module = mavenModule(project.resolve("module-a"), "ModuleType");
        testSource(project, "RootTest");
        testSource(module, "ModuleTest");

        Path bin = Files.createDirectories(temporary.resolve("bin"));
        Path arguments = temporary.resolve("docker-arguments.txt");
        Path fakeDocker = bin.resolve("docker");
        Files.writeString(fakeDocker, """
                #!/usr/bin/env bash
                set -euo pipefail
                printf '%%s\\n' "$*" >> '%s'
                output_mount=''; output=''; scope=''
                for argument in "$@"; do
                  case "$argument" in
                    type=bind,src=*,dst=/workspace/out)
                      output_mount="${argument#type=bind,src=}"
                      output_mount="${output_mount%%,dst=/workspace/out}" ;;
                    -Dmdep.outputFile=*) output="${argument#*=}" ;;
                    -DincludeScope=*) scope="${argument#*=}" ;;
                  esac
                done
                relative="${output#/workspace/out/}"
                host_output="$output_mount/$relative"
                key="${relative##*/}"; key="${key%%.txt}"
                mkdir -p "$(dirname "$host_output")" "$output_mount/repository/shared" "$output_mount/repository/$key"
                printf x > "$output_mount/repository/shared/shared.jar"
                printf y > "$output_mount/repository/$key/scoped.jar"
                printf '/workspace/out/repository/%%s/scoped.jar:/workspace/out/repository/shared/shared.jar:/workspace/out/repository/%%s/scoped.jar\\n' \
                  "$key" "$key" > "$host_output"
                """.formatted(arguments));
        Files.setPosixFilePermissions(fakeDocker, PosixFilePermissions.fromString("rwxr-xr-x"));

        Path output = temporary.resolve("dependencies.tsv");
        Process result = resolver(project, output, bin).start();
        String log = new String(result.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, result.waitFor(), log);

        Path cache = Path.of(output + ".cache");
        List<String> rows = Files.readAllLines(output);
        assertEquals(8, rows.size());
        assertEquals("module-a/src/main/java\t" + cache.resolve("repository/0-main/scoped.jar").toRealPath(), rows.get(0));
        assertEquals("module-a/src/main/java\t" + cache.resolve("repository/shared/shared.jar").toRealPath(), rows.get(1));
        assertEquals("module-a/src/test/java\t" + cache.resolve("repository/0-test/scoped.jar").toRealPath(), rows.get(2));
        assertEquals("module-a/src/test/java\t" + cache.resolve("repository/shared/shared.jar").toRealPath(), rows.get(3));
        assertEquals("src/main/java\t" + cache.resolve("repository/1-main/scoped.jar").toRealPath(), rows.get(4));
        assertEquals("src/main/java\t" + cache.resolve("repository/shared/shared.jar").toRealPath(), rows.get(5));
        assertEquals("src/test/java\t" + cache.resolve("repository/1-test/scoped.jar").toRealPath(), rows.get(6));
        assertEquals("src/test/java\t" + cache.resolve("repository/shared/shared.jar").toRealPath(), rows.get(7));

        List<String> invocations = Files.readAllLines(arguments);
        assertEquals(4, invocations.size());
        assertEquals(2, invocations.stream().filter(item -> item.contains("-DincludeScope=compile")).count());
        assertEquals(2, invocations.stream().filter(item -> item.contains("-DincludeScope=test")).count());
        for (String invoked : invocations) {
            assertTrue(invoked.contains("--read-only"));
            assertTrue(invoked.contains("--cap-drop=ALL"));
            assertTrue(invoked.contains("--security-opt=no-new-privileges"));
            assertTrue(invoked.contains("--pids-limit=256"));
            assertTrue(invoked.contains("maven:3.9.16-eclipse-temurin-17"));
            assertTrue(invoked.contains("org.apache.maven.plugins:maven-dependency-plugin:3.11.0:build-classpath"));
        }
    }

    @Test
    void ignoresAggregatorPomWithoutOwnedJavaSources() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        mavenModule(project.resolve("module-a"), "ModuleType");
        Path bin = Files.createDirectories(temporary.resolve("bin"));
        Path arguments = temporary.resolve("docker-arguments.txt");
        Path fakeDocker = bin.resolve("docker");
        Files.writeString(fakeDocker, """
                #!/usr/bin/env bash
                set -euo pipefail
                printf '%%s\\n' "$*" >> '%s'
                output_mount=''; output=''
                for argument in "$@"; do
                  case "$argument" in
                    type=bind,src=*,dst=/workspace/out)
                      output_mount="${argument#type=bind,src=}"
                      output_mount="${output_mount%%,dst=/workspace/out}" ;;
                    -Dmdep.outputFile=*) output="${argument#*=}" ;;
                  esac
                done
                relative="${output#/workspace/out/}"
                mkdir -p "$output_mount/$(dirname "$relative")" "$output_mount/repository"
                printf x > "$output_mount/repository/only.jar"
                printf '/workspace/out/repository/only.jar\\n' > "$output_mount/$relative"
                """.formatted(arguments));
        Files.setPosixFilePermissions(fakeDocker, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path output = temporary.resolve("dependencies.tsv");
        Process result = resolver(project, output, bin).start();
        result.getInputStream().readAllBytes();
        assertEquals(0, result.waitFor());
        assertEquals(1, Files.readAllLines(arguments).size());
        assertTrue(Files.readString(arguments).contains("/workspace/source/module-a/pom.xml"));
        assertEquals("module-a/src/main/java", Files.readString(output).split("\\t", 2)[0]);
    }

    @Test
    void requiresExplicitResolverConfigurationForMavenProjects() throws Exception {
        Path project = mavenModule(temporary.resolve("project"), "Type");
        Path output = temporary.resolve("dependencies.tsv");
        ProcessBuilder process = new ProcessBuilder("bash", "scripts/resolve-maven-dependencies.sh", project.toString(), output.toString());
        process.environment().remove("MAVEN_DEPENDENCY_PLUGIN_VERSION");
        process.environment().remove("MAVEN_RESOLVER_IMAGE");
        Process result = process.start();
        result.getInputStream().readAllBytes(); result.getErrorStream().readAllBytes();
        assertEquals(64, result.waitFor());
    }

    @Test
    void rejectsResolverOutputOutsideIsolatedMounts() throws Exception {
        Path project = mavenModule(temporary.resolve("project"), "Type");
        Path bin = Files.createDirectories(temporary.resolve("bin"));
        Path fakeDocker = bin.resolve("docker");
        Files.writeString(fakeDocker, """
                #!/usr/bin/env bash
                set -euo pipefail
                output_mount=''; output=''
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
        Files.setPosixFilePermissions(fakeDocker, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path output = temporary.resolve("dependencies.tsv");
        Process result = resolver(project, output, bin).start();
        result.getInputStream().readAllBytes(); result.getErrorStream().readAllBytes();
        assertEquals(70, result.waitFor());
        assertEquals(0, Files.size(output));
    }

    private Path mavenModule(Path directory, String typeName) throws Exception {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>");
        Path source = Files.createDirectories(directory.resolve("src/main/java/example"));
        Files.writeString(source.resolve(typeName + ".java"), "package example; public class " + typeName + " {}\n");
        return directory;
    }

    private void testSource(Path directory, String typeName) throws Exception {
        Path source = Files.createDirectories(directory.resolve("src/test/java/example"));
        Files.writeString(source.resolve(typeName + ".java"), "package example; public class " + typeName + " {}\n");
    }

    private ProcessBuilder resolver(Path project, Path output, Path bin) {
        ProcessBuilder process = new ProcessBuilder("bash", "scripts/resolve-maven-dependencies.sh", project.toString(), output.toString());
        process.redirectErrorStream(true);
        process.environment().put("PATH", bin + ":" + process.environment().get("PATH"));
        process.environment().put("MAVEN_DEPENDENCY_PLUGIN_VERSION", "3.11.0");
        process.environment().put("MAVEN_RESOLVER_IMAGE", "maven:3.9.16-eclipse-temurin-17");
        return process;
    }
}
