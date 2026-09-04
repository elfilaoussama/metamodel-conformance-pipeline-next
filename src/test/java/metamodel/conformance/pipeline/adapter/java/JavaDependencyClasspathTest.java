package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.model.Language;
import metamodel.conformance.pipeline.util.Hashing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaDependencyClasspathTest {
    @TempDir
    Path temporary;

    @Test
    void fingerprintsProvenanceWithoutReorderingSemanticClasspath() throws Exception {
        Path second = jar("second.jar", "shared/Type.class", new byte[]{2});
        Path first = jar("first.jar", "shared/Type.class", new byte[]{1});

        JavaDependencyClasspath.Result result = JavaDependencyClasspath.resolve(List.of(second, first));

        assertEquals(2, result.entries().size());
        assertEquals(List.of(second.toRealPath(), first.toRealPath()), result.paths());
        assertEquals(second.toRealPath(), result.ownerOfType("shared.Type").path());
        assertTrue(result.units().stream().allMatch(unit -> unit.language() == Language.JAVA_ARCHIVE));
        assertTrue(result.units().stream().allMatch(unit -> unit.path().startsWith("dependencies/")));
        assertEquals(List.of(Hashing.sha256(first), Hashing.sha256(second)).stream().sorted().toList(),
                result.units().stream().map(unit -> unit.sha256()).sorted().toList());
        assertEquals(result.units().stream().map(unit -> unit.path()).sorted().toList(),
                result.units().stream().map(unit -> unit.path()).toList());
    }

    @Test
    void indexesNestedAndMultiReleaseTypeNamesAgainstTheirArchive() throws Exception {
        Path jar = temporary.resolve("dependency.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            add(output, "example/Outer$Inner.class", new byte[]{1});
            add(output, "META-INF/versions/11/example/Versioned.class", new byte[]{2});
        }

        JavaDependencyClasspath.Result result = JavaDependencyClasspath.resolve(List.of(jar));

        assertEquals(jar.toRealPath(), result.ownerOfType("example.Outer.Inner").path());
        assertEquals(jar.toRealPath(), result.ownerOfType("example.Versioned").path());
    }

    @Test
    void rejectsDirectoriesAndNonJarInputs() throws Exception {
        assertThrows(Exception.class, () -> JavaDependencyClasspath.resolve(List.of(temporary)));
        Path text = temporary.resolve("dependency.txt");
        Files.writeString(text, "not a jar");
        assertThrows(Exception.class, () -> JavaDependencyClasspath.resolve(List.of(text)));
        Path fakeJar = temporary.resolve("fake.jar");
        Files.writeString(fakeJar, "not a jar");
        assertThrows(Exception.class, () -> JavaDependencyClasspath.resolve(List.of(fakeJar)));
    }

    private Path jar(String name, String entryName, byte[] bytes) throws Exception {
        Path jar = temporary.resolve(name);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            add(output, entryName, bytes);
        }
        return jar;
    }

    private static void add(JarOutputStream output, String entryName, byte[] bytes) throws Exception {
        JarEntry entry = new JarEntry(entryName);
        entry.setTime(0L);
        output.putNextEntry(entry);
        output.write(bytes);
        output.closeEntry();
    }
}
