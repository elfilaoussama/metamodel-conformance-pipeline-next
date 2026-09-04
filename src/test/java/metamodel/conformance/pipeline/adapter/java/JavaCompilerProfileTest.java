package metamodel.conformance.pipeline.adapter.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaCompilerProfileTest {
    @TempDir
    Path temporary;

    @Test
    void derivesReleaseFromMavenProperty() throws Exception {
        Files.writeString(temporary.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <properties><maven.compiler.release>11</maven.compiler.release></properties>
                </project>
                """);

        assertEquals(11, JavaCompilerProfile.discover(temporary).release());
    }

    @Test
    void resolvesPropertyReferencesAndLegacySourceNotation() throws Exception {
        Files.writeString(temporary.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <properties>
                    <java.version>1.8</java.version>
                    <maven.compiler.source>${java.version}</maven.compiler.source>
                  </properties>
                </project>
                """);

        assertEquals(8, JavaCompilerProfile.discover(temporary).release());
    }

    @Test
    void usesOwningModuleMetadataForNestedSourceSets() throws Exception {
        Files.writeString(temporary.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <properties><maven.compiler.release>11</maven.compiler.release></properties>
                </project>
                """);
        Files.createDirectories(temporary.resolve("module"));
        Files.writeString(temporary.resolve("module/pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <properties><maven.compiler.release>17</maven.compiler.release></properties>
                </project>
                """);

        assertEquals(17, JavaCompilerProfile.discover(
                temporary, "module/src/main/java").release());
        assertEquals(11, JavaCompilerProfile.discover(
                temporary, "src/test/java").release());
    }

    @Test
    void fallsBackToTheRunningJdkWithoutBuildMetadata() {
        assertEquals(Runtime.version().feature(), JavaCompilerProfile.discover(temporary).release());
    }
}
