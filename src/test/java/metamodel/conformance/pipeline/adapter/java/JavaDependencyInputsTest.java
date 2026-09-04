package metamodel.conformance.pipeline.adapter.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaDependencyInputsTest {
    @TempDir
    Path temporary;

    @Test
    void mapsArchivesToOwningSourceSetModuleWithoutFallbackGuessing() throws Exception {
        Path rootJar = temporary.resolve("root.jar");
        Path moduleJar = temporary.resolve("module.jar");
        Path nestedJar = temporary.resolve("nested.jar");
        Files.write(rootJar, new byte[]{1});
        Files.write(moduleJar, new byte[]{2});
        Files.write(nestedJar, new byte[]{3});
        Path manifest = temporary.resolve("dependencies.tsv");
        Files.writeString(manifest, String.join("\n",
                ".\t" + rootJar,
                "module-a\t" + moduleJar,
                "module-a\t" + moduleJar,
                "module-b/sub\t" + nestedJar) + "\n");

        JavaDependencyInputs inputs = JavaDependencyInputs.fromManifest(manifest);

        assertTrue(inputs.scoped());
        assertEquals(List.of(rootJar), inputs.pathsForSourceSet("src/main/java"));
        assertEquals(List.of(moduleJar), inputs.pathsForSourceSet("module-a/src/test/java"));
        assertEquals(List.of(nestedJar), inputs.pathsForSourceSet("module-b/sub/src/main/java"));
        assertTrue(inputs.pathsForSourceSet("unknown/src/main/java").isEmpty());
        assertEquals(List.of(rootJar, moduleJar, nestedJar), inputs.allPaths());
    }

    @Test
    void rejectsNonCanonicalModuleIdentity() throws Exception {
        Path manifest = temporary.resolve("dependencies.tsv");
        Files.writeString(manifest, "../module\t/tmp/dependency.jar\n");
        assertThrows(Exception.class, () -> JavaDependencyInputs.fromManifest(manifest));
    }
}
