package metamodel.conformance.pipeline.alloy;

import metamodel.conformance.pipeline.TestObservations;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
