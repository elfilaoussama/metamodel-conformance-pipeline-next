package metamodel.conformance.pipeline.adapter.java;

import metamodel.conformance.pipeline.adapter.SourceObserver;
import metamodel.conformance.pipeline.model.ClassifierAbstraction;
import metamodel.conformance.pipeline.model.ClassifierKind;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.DiagnosticKind;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.Language;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.model.SourceUnit;
import metamodel.conformance.pipeline.model.UnresolvedParent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaDependencyAwareSourceSetIsolationTest {
    @TempDir Path temporary;

    @Test
    void sameModuleMainAndTestMayResolveSameFqnToDifferentBytecode() throws Exception {
        Path mainSource = source("src/main/java/app/MainChild.java", "MainChild");
        Path testSource = source("src/test/java/app/TestChild.java", "TestChild");
        Files.writeString(temporary.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <properties><maven.compiler.release>17</maven.compiler.release></properties>
                </project>
                """);

        Path mainJar = dependencyJar(temporary.resolve("dependency-main"), "onlyMain");
        Path testJar = dependencyJar(temporary.resolve("dependency-test"), "onlyTest");
        Path manifest = temporary.resolve("dependencies.tsv");
        Files.writeString(manifest,
                "src/main/java\t" + mainJar + "\n"
                        + "src/test/java\t" + testJar + "\n");
        JavaDependencyInputs inputs = JavaDependencyInputs.fromManifest(manifest);

        String mainId = "cls_" + "1".repeat(64);
        String testId = "cls_" + "2".repeat(64);
        ClassifierObservation mainClassifier = classifier(
                mainId, "app.MainChild", "src/main/java/app/MainChild.java");
        ClassifierObservation testClassifier = classifier(
                testId, "app.TestChild", "src/test/java/app/TestChild.java");
        Observation base = new Observation(
                "12", "spoon-java", "test", List.of(), Set.of(),
                List.of(
                        new SourceUnit(Language.JAVA, mainClassifier.sourcePath(), "a".repeat(64)),
                        new SourceUnit(Language.JAVA, testClassifier.sourcePath(), "b".repeat(64))),
                List.of(mainClassifier, testClassifier),
                List.of(), List.of(), List.of(),
                List.of(
                        new UnresolvedParent(mainId, "dep.Parent", mainClassifier.sourcePath(), 2),
                        new UnresolvedParent(testId, "dep.Parent", testClassifier.sourcePath(), 2)),
                List.of());
        SourceObserver delegate = (sourceRoot, externalParents) -> base;

        Observation observed = new JavaDependencyAwareSourceObserver(inputs, delegate)
                .observe(temporary, Set.of());

        assertTrue(observed.unresolvedParents().isEmpty(),
                () -> observed.unresolvedParents().toString());
        assertTrue(observed.completeEvidence().contains(EvidenceKind.HIERARCHY));
        assertTrue(observed.completeEvidence().contains(EvidenceKind.INHERITED_MEMBERS));

        Map<String, ClassifierObservation> classifiers = new HashMap<>();
        observed.classifiers().forEach(item -> classifiers.put(item.id(), item));
        ClassifierObservation main = classifiers.get(mainId);
        ClassifierObservation test = classifiers.get(testId);
        assertEquals(1, main.parentIds().size());
        assertEquals(1, test.parentIds().size());
        assertNotEquals(main.parentIds().get(0), test.parentIds().get(0));

        Map<String, MemberObservation> members = new HashMap<>();
        observed.members().forEach(item -> members.put(item.technicalKey(), item));
        Set<String> mainInherited = memberNames(main.inheritedMemberKeys(), members);
        Set<String> testInherited = memberNames(test.inheritedMemberKeys(), members);
        assertTrue(mainInherited.contains("onlyMain"), mainInherited::toString);
        assertTrue(!mainInherited.contains("onlyTest"), mainInherited::toString);
        assertTrue(testInherited.contains("onlyTest"), testInherited::toString);
        assertTrue(!testInherited.contains("onlyMain"), testInherited::toString);
        assertEquals(2, observed.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("dep.Parent")).count());

        assertTrue(Files.exists(mainSource));
        assertTrue(Files.exists(testSource));
    }

    @Test
    void missingTransitiveDependencyKeepsHierarchyEvidenceIncomplete() throws Exception {
        Path source = source("src/main/java/app/Top.java", "Top");
        Files.writeString(source, "package app;\npublic class Top extends dep.Child {}\n");
        Files.writeString(temporary.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <properties><maven.compiler.release>17</maven.compiler.release></properties>
                </project>
                """);

        Path missingClasses = Files.createDirectories(temporary.resolve("missing-classes"));
        Path missingSource = Files.createDirectories(temporary.resolve("missing-src/missing")).resolve("Base.java");
        Files.writeString(missingSource, "package missing; public class Base {}\n");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
                null, null, null, "--release", "17", "-d", missingClasses.toString(), missingSource.toString()));

        Path childClasses = Files.createDirectories(temporary.resolve("child-classes"));
        Path childSource = Files.createDirectories(temporary.resolve("child-src/dep")).resolve("Child.java");
        Files.writeString(childSource,
                "package dep; public class Child extends missing.Base { public int value; }\n");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
                null, null, null, "--release", "17",
                "-classpath", missingClasses.toString(), "-d", childClasses.toString(), childSource.toString()));
        Path childJar = temporary.resolve("child-only.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(childJar))) {
            JarEntry entry = new JarEntry("dep/Child.class");
            entry.setTime(0L);
            output.putNextEntry(entry);
            output.write(Files.readAllBytes(childClasses.resolve("dep/Child.class")));
            output.closeEntry();
        }

        Path manifest = temporary.resolve("dependencies.tsv");
        Files.writeString(manifest, "src/main/java\t" + childJar + "\n");
        JavaDependencyInputs inputs = JavaDependencyInputs.fromManifest(manifest);
        String id = "cls_" + "5".repeat(64);
        ClassifierObservation top = classifier(id, "app.Top", "src/main/java/app/Top.java");
        Observation base = new Observation(
                "12", "spoon-java", "test", List.of(), Set.of(),
                List.of(new SourceUnit(Language.JAVA, top.sourcePath(), "c".repeat(64))),
                List.of(top), List.of(), List.of(), List.of(),
                List.of(new UnresolvedParent(id, "dep.Child", top.sourcePath(), 2)), List.of());
        SourceObserver delegate = (sourceRoot, externalParents) -> base;

        Observation observed = new JavaDependencyAwareSourceObserver(inputs, delegate)
                .observe(temporary, Set.of());

        assertTrue(!observed.completeEvidence().contains(EvidenceKind.HIERARCHY));
        assertTrue(!observed.completeEvidence().contains(EvidenceKind.INHERITED_MEMBERS));
        assertTrue(observed.diagnostics().stream()
                .anyMatch(item -> item.kind() == DiagnosticKind.EVIDENCE_INCOMPLETE));
    }

    private Path source(String relative, String typeName) throws Exception {
        Path file = temporary.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file,
                "package app;\npublic class " + typeName + " extends dep.Parent {}\n");
        return file;
    }

    private static ClassifierObservation classifier(String id, String name, String path) {
        return new ClassifierObservation(
                id, name, "app", ClassifierKind.CLASS, path, 2, 2,
                List.of(), List.of(), List.of(), ClassifierAbstraction.CONCRETE);
    }

    private static Set<String> memberNames(
            List<String> keys,
            Map<String, MemberObservation> members) {
        Set<String> names = new TreeSet<>();
        for (String key : keys) {
            names.add(members.get(key).memberName());
        }
        return names;
    }

    private Path dependencyJar(Path root, String fieldName) throws Exception {
        Path source = Files.createDirectories(root.resolve("source/dep"));
        Path classes = Files.createDirectories(root.resolve("classes"));
        Path java = source.resolve("Parent.java");
        Files.writeString(java,
                "package dep; public class Parent { public int " + fieldName + "; }\n");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
                null, null, null,
                "--release", "17", "-d", classes.toString(), java.toString()));
        Path jar = root.resolve("dependency.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            JarEntry entry = new JarEntry("dep/Parent.class");
            entry.setTime(0L);
            output.putNextEntry(entry);
            output.write(Files.readAllBytes(classes.resolve("dep/Parent.class")));
            output.closeEntry();
        }
        return jar;
    }
}
