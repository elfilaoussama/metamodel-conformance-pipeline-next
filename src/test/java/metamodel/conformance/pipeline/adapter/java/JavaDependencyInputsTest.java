package metamodel.conformance.pipeline.adapter.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaDependencyInputsTest {
    @TempDir
    Path temporary;

    @Test
    void mapsArchivesToOwningSourceSetModuleWithoutFallbackGuessing() throws Exception {
        Path rootJar = Files.write(temporary.resolve("root.jar"), new byte[]{1}).toAbsolutePath().normalize();
        Path moduleJar = Files.write(temporary.resolve("module.jar"), new byte[]{2}).toAbsolutePath().normalize();
        Path nestedJar = Files.write(temporary.resolve("nested.jar"), new byte[]{3}).toAbsolutePath().normalize();
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
        assertEquals(inputs.allPaths(), List.copyOf(inputs));
        assertSame(inputs, JavaDependencyInputs.global(inputs));
    }

    @Test
    void rejectsNonCanonicalModuleIdentity() throws Exception {
        Path manifest = temporary.resolve("dependencies.tsv");
        Files.writeString(manifest, "../module\t/tmp/dependency.jar\n");
        assertThrows(Exception.class, () -> JavaDependencyInputs.fromManifest(manifest));
    }
}
