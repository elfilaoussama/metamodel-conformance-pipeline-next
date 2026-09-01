package metamodel.conformance.pipeline.model;

import metamodel.conformance.pipeline.TestObservations;
import metamodel.conformance.pipeline.util.Hashing;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ObservationValidationTest {
    @Test
    void rejectsNonCanonicalSourcePaths() {
        for (String path : List.of(
                "/absolute/A.java", "C:/absolute/A.java", "a\\A.java",
                "a/../A.java", "a/./A.java", "a//A.java", "a/")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new SourceUnit(Language.JAVA, path, Hashing.sha256("source")), path);
        }
    }

    @Test
    void rejectsNonCanonicalDigestsAndTechnicalIdentifiers() {
        assertThrows(IllegalArgumentException.class,
                () -> new SourceUnit(Language.JAVA, "A.java", "ABC"));
        assertThrows(IllegalArgumentException.class,
                () -> new ClassifierObservation(
                        "A", "example.A", ClassifierKind.CLASS,
                        "A.java", 1, 1, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new MemberObservation(
                        "work", null, MemberKind.METHOD, "work",
                        "A.java", 1, 1, List.of()));
    }

    @Test
    void rejectsDuplicateSourceUnitPaths() {
        Observation base = TestObservations.acyclic();
        SourceUnit unit = base.units().get(0);

        assertThrows(IllegalArgumentException.class, () -> new Observation(
                base.schemaVersion(), base.adapterId(), base.adapterVersion(),
                base.externalParents(), base.completeEvidence(),
                List.of(unit, unit), List.of(), List.of(), List.of()));
    }

    @Test
    void rejectsProvenanceOutsideTheHashedSourceSet() {
        ClassifierObservation classifier = new ClassifierObservation(
                TestObservations.A, "example.A", ClassifierKind.CLASS,
                "missing/A.java", 1, 1, List.of());

        assertThrows(IllegalArgumentException.class, () -> new Observation(
                "5", "test-adapter", "1.0.0", List.of(), Set.of(EvidenceKind.HIERARCHY),
                List.of(new SourceUnit(
                        Language.JAVA, "present/A.java", Hashing.sha256("source"))),
                List.of(classifier), List.of(), List.of()));
    }
}
