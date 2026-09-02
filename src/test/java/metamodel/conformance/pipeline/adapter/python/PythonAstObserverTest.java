package metamodel.conformance.pipeline.adapter.python;

import metamodel.conformance.pipeline.model.DiagnosticKind;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.Language;
import metamodel.conformance.pipeline.model.Observation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PythonAstObserverTest {
    @TempDir
    Path temp;

    @Test
    void observesResolvableModuleLevelHierarchy() throws Exception {
        Files.writeString(temp.resolve("models.py"), """
                class Base:
                    pass

                class Child(Base):
                    pass
                """);

        Observation observation = new PythonAstObserver().observe(temp, Set.of());

        assertEquals("python-ast", observation.adapterId());
        assertEquals(1, observation.units().size());
        assertEquals(Language.PYTHON, observation.units().get(0).language());
        assertEquals(2, observation.classifiers().size());
        assertTrue(observation.completeEvidence().contains(EvidenceKind.HIERARCHY));
        assertFalse(observation.completeEvidence().contains(EvidenceKind.DECLARATION_OWNERSHIP));
        assertTrue(observation.unresolvedParents().isEmpty());

        var base = classifier(observation, "models.Base");
        var child = classifier(observation, "models.Child");
        assertEquals(java.util.List.of(base.id()), child.parentIds());
    }

    @Test
    void observesNestedAndLocalClassesInsteadOfDiscardingTheirHierarchy() throws Exception {
        Files.writeString(temp.resolve("models.py"), """
                class Base:
                    pass

                class Outer:
                    class Nested(Base):
                        pass

                def factory():
                    class Local(Base):
                        pass
                    return Local
                """);

        Observation observation = new PythonAstObserver().observe(temp, Set.of());

        assertTrue(observation.completeEvidence().contains(EvidenceKind.HIERARCHY));
        assertEquals(4, observation.classifiers().size());
        assertTrue(observation.diagnostics().stream()
                .noneMatch(item -> item.message().startsWith("nested or local Python class")));

        var base = classifier(observation, "models.Base");
        var nested = classifier(observation, "models.Outer.Nested");
        var local = classifier(observation, "models.factory.<locals>.Local");
        assertEquals(java.util.List.of(base.id()), nested.parentIds());
        assertEquals(java.util.List.of(base.id()), local.parentIds());
    }

    @Test
    void resolvesSiblingLocalClassInheritanceWithinFunctionScope() throws Exception {
        Files.writeString(temp.resolve("models.py"), """
                def factory():
                    class LocalBase:
                        pass

                    class LocalChild(LocalBase):
                        pass

                    return LocalChild
                """);

        Observation observation = new PythonAstObserver().observe(temp, Set.of());

        assertTrue(observation.completeEvidence().contains(EvidenceKind.HIERARCHY));
        var base = classifier(observation, "models.factory.<locals>.LocalBase");
        var child = classifier(observation, "models.factory.<locals>.LocalChild");
        assertEquals(java.util.List.of(base.id()), child.parentIds());
    }

    @Test
    void unresolvedExternalBaseKeepsHierarchyEvidenceIncomplete() throws Exception {
        Files.writeString(temp.resolve("models.py"), """
                from external_lib import ExternalBase

                class Child(ExternalBase):
                    pass
                """);

        Observation observation = new PythonAstObserver().observe(temp, Set.of());

        assertFalse(observation.completeEvidence().contains(EvidenceKind.HIERARCHY));
        assertEquals(1, observation.unresolvedParents().size());
        assertEquals("external_lib.ExternalBase", observation.unresolvedParents().get(0).targetName());
    }

    @Test
    void parseErrorForbidsCompleteEvidence() throws Exception {
        Files.writeString(temp.resolve("broken.py"), "class Broken(:\n    pass\n");

        Observation observation = new PythonAstObserver().observe(temp, Set.of());

        assertTrue(observation.completeEvidence().isEmpty());
        assertTrue(observation.diagnostics().stream()
                .anyMatch(item -> item.kind() == DiagnosticKind.PARSE_ERROR));
    }

    private static metamodel.conformance.pipeline.model.ClassifierObservation classifier(
            Observation observation, String qualifiedName) {
        return observation.classifiers().stream()
                .filter(item -> item.qualifiedName().equals(qualifiedName))
                .findFirst().orElseThrow();
    }
}
