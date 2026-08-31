package metamodel.conformance.pipeline.alloy;

import metamodel.conformance.pipeline.TestObservations;
import metamodel.conformance.pipeline.model.ClassifierKind;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.GeneralizationKind;
import metamodel.conformance.pipeline.model.GeneralizationObservation;
import metamodel.conformance.pipeline.model.GeneralizationResolutionStatus;
import metamodel.conformance.pipeline.model.Language;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.model.SourceUnit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExactAlloyEncoderTest {
    @Test
    void encodesAnExactRepositoryGraph() {
        String alloy = new ExactAlloyEncoder().encode(TestObservations.acyclic());

        assertEquals(2, alloy.lines().filter(line -> line.startsWith("one sig C_")).count());
        assertTrue(alloy.contains("parents = (" + ExactAlloyEncoder.classifierAtom(TestObservations.B)
                + "->" + ExactAlloyEncoder.classifierAtom(TestObservations.A)));
        assertTrue(alloy.contains("namespaceKeyRepresentative: one Member"));
        assertTrue(alloy.contains("sig InheritableMember in Member"));
        assertFalse(alloy.contains("abstract sig SignatureToken"));
        assertFalse(alloy.contains("abstract sig NameToken"));
        assertFalse(alloy.contains("Int -> lone TypeToken"));
        assertFalse(alloy.contains("abstract sig Parameter {"));
        assertTrue(alloy.contains("parents = ("));
        assertTrue(alloy.contains("run ObservationConsistency for exactly 2 Classifier"));
        assertFalse(alloy.contains("run AcyclicGeneralizationViolation"));
        assertTrue(alloy.contains("fun AcyclicGeneralizationViolations : set Classifier"));
        assertFalse(alloy.contains("example.A"));
    }

    @Test
    void projectsOnlyResolvedInternalGeneralizationsIntoAlloyParents() {
        ClassifierObservation parent = new ClassifierObservation(
                TestObservations.A, "example.A", ClassifierKind.INTERFACE,
                "example/A.java", 1, 2, List.of(), List.of());
        ClassifierObservation child = new ClassifierObservation(
                TestObservations.B, "example.B", ClassifierKind.CLASS,
                "example/B.java", 3, 7, List.of(TestObservations.A), List.of());
        Observation observation = new Observation(
                "5", "test-adapter", "1.0", List.of("external.Base"), Set.of(EvidenceKind.HIERARCHY),
                List.of(new SourceUnit(Language.JAVA, "example/B.java", "a".repeat(64))),
                List.of(parent, child), List.of(),
                List.of(
                        new GeneralizationObservation(
                                TestObservations.B, null, "external.Base", GeneralizationKind.EXTENDS, 0,
                                GeneralizationResolutionStatus.EXTERNAL_BOUNDARY, "example/B.java", 3),
                        new GeneralizationObservation(
                                TestObservations.B, TestObservations.A, "example.A", GeneralizationKind.IMPLEMENTS, 1,
                                GeneralizationResolutionStatus.RESOLVED_INTERNAL, "example/B.java", 3)),
                List.of(), List.of());

        String alloy = new ExactAlloyEncoder().encode(observation);
        String expected = ExactAlloyEncoder.classifierAtom(TestObservations.B)
                + "->" + ExactAlloyEncoder.classifierAtom(TestObservations.A);

        assertTrue(alloy.contains("parents = (" + expected + ")"));
        assertEquals(1, alloy.lines().filter(line -> line.startsWith("  parents = ")).count());
        assertFalse(alloy.contains("external.Base"));
    }

    @Test
    void rejectsDivergentLegacyAndCanonicalParentViewsBeforeEncoding() {
        ClassifierObservation parent = new ClassifierObservation(
                TestObservations.A, "example.A", ClassifierKind.CLASS,
                "example/A.java", 1, 2, List.of(), List.of());
        ClassifierObservation child = new ClassifierObservation(
                TestObservations.B, "example.B", ClassifierKind.CLASS,
                "example/B.java", 3, 4, List.of(TestObservations.A), List.of());

        assertThrows(IllegalArgumentException.class, () -> new Observation(
                "5", "test-adapter", "1.0", List.of(), Set.of(EvidenceKind.HIERARCHY),
                List.of(new SourceUnit(Language.JAVA, "example/B.java", "a".repeat(64))),
                List.of(parent, child), List.of(), List.of(), List.of(), List.of()));
    }

    @Test
    void encodesObservedInheritedMembershipSeparatelyFromFormalDerivation() {
        var observation = TestObservations.inheritedViewConformant();
        String alloy = new ExactAlloyEncoder().encode(observation);

        assertTrue(alloy.contains("observedInheritedMembers = ("
                + ExactAlloyEncoder.classifierAtom(TestObservations.B) + "->"
                + ExactAlloyEncoder.memberAtom(observation.members().get(0).technicalKey())));
        assertTrue(alloy.contains("fun formalInheritedMembers[c : Classifier]"));
        assertTrue(alloy.contains("fun InheritedViewConsistencyViolations : Classifier -> Member"));
        assertTrue(alloy.contains("fun LocalInheritedSeparationViolations : Classifier -> Member"));
    }

    @Test
    void projectsCompleteOrderedNamespaceKeysWithoutAuxiliaryAtoms() {
        var duplicateObservation = TestObservations.duplicateLocalMethods();
        var overloadedObservation = TestObservations.overloadedMethods();
        String duplicate = new ExactAlloyEncoder().encode(duplicateObservation);
        String overloaded = new ExactAlloyEncoder().encode(overloadedObservation);

        String duplicateRepresentative = duplicateObservation.members().stream()
                .map(member -> ExactAlloyEncoder.memberAtom(member.technicalKey()))
                .min(String::compareTo).orElseThrow();
        String duplicateProjection = duplicate.lines()
                .filter(line -> line.startsWith("  namespaceKeyRepresentative = "))
                .findFirst().orElseThrow();
        assertEquals(2, duplicateProjection.split("->" + duplicateRepresentative, -1).length - 1);

        String overloadedProjection = overloaded.lines()
                .filter(line -> line.startsWith("  namespaceKeyRepresentative = "))
                .findFirst().orElseThrow();
        for (var member : overloadedObservation.members()) {
            String atom = ExactAlloyEncoder.memberAtom(member.technicalKey());
            assertTrue(overloadedProjection.contains(atom + "->" + atom));
        }
        assertFalse(overloaded.contains("NameToken"));
        assertFalse(overloaded.contains("SignatureToken"));
    }
}
