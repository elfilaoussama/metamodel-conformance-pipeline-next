package metamodel.conformance.pipeline.adapter.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaDependencyInputsTest {
    @TempDir Path temporary;

    @Test
    void mapsArchivesToExactSourceSetsWithoutFallbackGuessing() throws Exception {
        Path rootJar = Files.write(temporary.resolve("root.jar"), new byte[]{1}).toAbsolutePath().normalize();
        Path mainJar = Files.write(temporary.resolve("main.jar"), new byte[]{2}).toAbsolutePath().normalize();
        Path testJar = Files.write(temporary.resolve("test.jar"), new byte[]{3}).toAbsolutePath().normalize();
        Path nestedJar = Files.write(temporary.resolve("nested.jar"), new byte[]{4}).toAbsolutePath().normalize();
        Path manifest = temporary.resolve("dependencies.tsv");
        Files.writeString(manifest, String.join("\n",
                "src/main/java\t" + rootJar,
                "module-a/src/main/java\t" + mainJar,
                "module-a/src/main/java\t" + mainJar,
                "module-a/src/test/java\t" + testJar,
                "module-b/sub/src/main/java\t" + nestedJar) + "\n");

        JavaDependencyInputs inputs = JavaDependencyInputs.fromManifest(manifest);

        assertTrue(inputs.scoped());
        assertEquals(List.of(rootJar), inputs.pathsForSourceSet("src/main/java"));
        assertEquals(List.of(mainJar), inputs.pathsForSourceSet("module-a/src/main/java"));
        assertEquals(List.of(testJar), inputs.pathsForSourceSet("module-a/src/test/java"));
        assertEquals(List.of(nestedJar), inputs.pathsForSourceSet("module-b/sub/src/main/java"));
        assertTrue(inputs.pathsForSourceSet("module-a/src/integrationTest/java").isEmpty());
        assertTrue(inputs.pathsForSourceSet("<root>").isEmpty());
        assertEquals(List.of(mainJar, testJar), inputs.pathsForModule("module-a"));
        assertEquals(Set.of(".", "module-a", "module-b/sub"), inputs.moduleKeys());
        assertEquals(Set.of("src/main/java", "module-a/src/main/java", "module-a/src/test/java", "module-b/sub/src/main/java"), inputs.sourceSetKeys());
        assertEquals(List.of(rootJar, mainJar, testJar, nestedJar), inputs.allPaths());
        assertEquals(inputs.allPaths(), List.copyOf(inputs));
        assertSame(inputs, JavaDependencyInputs.global(inputs));
    }

    @Test
    void rejectsModuleOnlyAndNonCanonicalManifestKeys() throws Exception {
        Path manifest = temporary.resolve("dependencies.tsv");
        Files.writeString(manifest, "module-a\t/tmp/dependency.jar\n");
        assertThrows(Exception.class, () -> JavaDependencyInputs.fromManifest(manifest));
        Files.writeString(manifest, "../module/src/main/java\t/tmp/dependency.jar\n");
        assertThrows(Exception.class, () -> JavaDependencyInputs.fromManifest(manifest));
        assertThrows(IllegalArgumentException.class, () -> JavaDependencyInputs.none().pathsForModule("../module"));
    }
}
