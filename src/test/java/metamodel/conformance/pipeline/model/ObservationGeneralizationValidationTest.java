package metamodel.conformance.pipeline.model;

import metamodel.conformance.pipeline.TestObservations;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ObservationGeneralizationValidationTest {
    @Test
    void rejectsUnresolvedProjectionMultiplicityMismatch() {
        ClassifierObservation parent = new ClassifierObservation(
                TestObservations.A, "example.A", ClassifierKind.INTERFACE,
                "example/A.java", 1, 2, List.of(), List.of());
        ClassifierObservation child = new ClassifierObservation(
                TestObservations.B, "example.B", ClassifierKind.CLASS,
                "example/B.java", 3, 7, List.of(TestObservations.A), List.of());
        GeneralizationObservation internal = new GeneralizationObservation(
                TestObservations.B, TestObservations.A, "example.A", GeneralizationKind.IMPLEMENTS, 0,
                GeneralizationResolutionStatus.RESOLVED_INTERNAL, "example/B.java", 3);
        GeneralizationObservation firstUnresolved = new GeneralizationObservation(
                TestObservations.B, null, "missing.Dup", GeneralizationKind.IMPLEMENTS, 1,
                GeneralizationResolutionStatus.UNRESOLVED, "example/B.java", 3);
        GeneralizationObservation secondUnresolved = new GeneralizationObservation(
                TestObservations.B, null, "missing.Dup", GeneralizationKind.IMPLEMENTS, 2,
                GeneralizationResolutionStatus.UNRESOLVED, "example/B.java", 3);
        UnresolvedParent onlyOneLegacyEntry = new UnresolvedParent(
                TestObservations.B, "missing.Dup", "example/B.java", 3);

        assertThrows(IllegalArgumentException.class, () -> new Observation(
                "5", "test-adapter", "1.0", List.of(), Set.of(),
                List.of(new SourceUnit(Language.JAVA, "example/B.java", "a".repeat(64))),
                List.of(parent, child), List.of(),
                List.of(internal, firstUnresolved, secondUnresolved),
                List.of(onlyOneLegacyEntry), List.of()));
    }

    @Test
    void legacyProjectionDoesNotInventDeclarationOrder() {
        ClassifierObservation parent = new ClassifierObservation(
                TestObservations.A, "example.A", ClassifierKind.INTERFACE,
                "example/A.java", 1, 2, List.of(), List.of());
        ClassifierObservation child = new ClassifierObservation(
                TestObservations.B, "example.B", ClassifierKind.CLASS,
                "example/B.java", 3, 7, List.of(TestObservations.A), List.of());

        Observation observation = new Observation(
                "5", "legacy-test", "1.0", List.of(),
                List.of(new SourceUnit(Language.JAVA, "example/B.java", "a".repeat(64))),
                List.of(parent, child), List.of());

        assertEquals(1, observation.generalizations().size());
        assertNull(observation.generalizations().get(0).declaredOrder());
    }

    @Test
    void multipleUnknownOrdersAreNotTreatedAsDuplicateObservedPositions() {
        ClassifierObservation parent = new ClassifierObservation(
                TestObservations.A, "example.A", ClassifierKind.INTERFACE,
                "example/A.java", 1, 2, List.of(), List.of());
        ClassifierObservation child = new ClassifierObservation(
                TestObservations.B, "example.B", ClassifierKind.CLASS,
                "example/B.java", 3, 7, List.of(TestObservations.A), List.of());
        GeneralizationObservation external = new GeneralizationObservation(
                TestObservations.B, null, "external.Base", GeneralizationKind.EXTENDS, null,
                GeneralizationResolutionStatus.EXTERNAL_BOUNDARY, "example/B.java", 3);
        GeneralizationObservation internal = new GeneralizationObservation(
                TestObservations.B, TestObservations.A, "example.A", GeneralizationKind.IMPLEMENTS, null,
                GeneralizationResolutionStatus.RESOLVED_INTERNAL, "example/B.java", 3);

        Observation observation = new Observation(
                "5", "unknown-order-test", "1.0", List.of("external.Base"), Set.of(EvidenceKind.HIERARCHY),
                List.of(new SourceUnit(Language.JAVA, "example/B.java", "a".repeat(64))),
                List.of(parent, child), List.of(), List.of(external, internal), List.of(), List.of());

        assertEquals(2, observation.generalizations().size());
        assertNull(observation.generalizations().get(0).declaredOrder());
        assertNull(observation.generalizations().get(1).declaredOrder());
    }
}
