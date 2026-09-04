package metamodel.conformance.pipeline.adapter.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenDependencyResolverScriptTest {
    @TempDir
    Path temporary;

    @Test
    void resolvesConfiguredClasspathInMavenOrderWithoutRepositorySpecificKnowledge() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        Files.writeString(project.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion></project>");
        Path jars = Files.createDirectories(temporary.resolve("jars"));
        Path first = Files.write(jars.resolve("first.jar"), new byte[]{1});
        Path second = Files.write(jars.resolve("second.jar"), new byte[]{2});
        Path bin = Files.createDirectories(temporary.resolve("bin"));
        Path arguments = temporary.resolve("maven-arguments.txt");
        Path fakeMaven = bin.resolve("mvn");
        Files.writeString(fakeMaven, """
                #!/usr/bin/env bash
                set -euo pipefail
                printf '%s\\n' "$*" > '%s'
                output=''
                for argument in "$@"; do
                  case "$argument" in
                    -Dmdep.outputFile=*) output="${argument#*=}" ;;
                  esac
                done
                printf '%s:%s:%s\\n' '%s' '%s' '%s' > "$output"
                """.formatted(
                        arguments,
                        second, first, second,
                        second, first, second));
        Files.setPosixFilePermissions(fakeMaven,
                PosixFilePermissions.fromString("rwxr-xr-x"));

        Path output = temporary.resolve("dependencies.txt");
        ProcessBuilder process = new ProcessBuilder(
                "bash", "scripts/resolve-maven-dependencies.sh",
                project.toString(), output.toString());
        process.redirectErrorStream(true);
        process.environment().put("PATH", bin + ":" + process.environment().get("PATH"));
        process.environment().put("MAVEN_DEPENDENCY_PLUGIN_VERSION", "3.11.0");
        Process result = process.start();
        String log = new String(result.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, result.waitFor(), log);
        assertEquals(List.of(second.toRealPath().toString(), first.toRealPath().toString()),
                Files.readAllLines(output));
        String invoked = Files.readString(arguments);
        assertTrue(invoked.contains(
                "org.apache.maven.plugins:maven-dependency-plugin:3.11.0:build-classpath"));
        assertFalse(invoked.contains("outputAbsoluteArtifactFilename"));
    }

    @Test
    void requiresExplicitResolverVersionForMavenProjects() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("project"));
        Files.writeString(project.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion></project>");
        Path output = temporary.resolve("dependencies.txt");
        ProcessBuilder process = new ProcessBuilder(
                "bash", "scripts/resolve-maven-dependencies.sh",
                project.toString(), output.toString());
        process.environment().remove("MAVEN_DEPENDENCY_PLUGIN_VERSION");
        Process result = process.start();
        result.getInputStream().readAllBytes();
        result.getErrorStream().readAllBytes();
        assertEquals(69, result.waitFor());
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
