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

        var base = observation.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("models.Base"))
                .findFirst().orElseThrow();
        var child = observation.classifiers().stream()
                .filter(item -> item.qualifiedName().equals("models.Child"))
                .findFirst().orElseThrow();
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
}
