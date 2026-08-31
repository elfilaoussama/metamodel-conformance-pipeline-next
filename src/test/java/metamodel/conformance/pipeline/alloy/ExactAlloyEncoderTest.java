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
        assertTrue(alloy.contains("parents = " + ExactAlloyEncoder.classifierAtom(TestObservations.B)
                + "->" + ExactAlloyEncoder.classifierAtom(TestObservations.A)));
        assertTrue(alloy.contains("abstract sig Parameter"));
        assertTrue(alloy.contains("parameterOwner: one Member"));
        assertTrue(alloy.contains("parameterPosition: one PositionToken"));
        assertFalse(alloy.contains("Int -> lone TypeToken"));
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

        assertTrue(alloy.contains("observedInheritedMembers = "
                + ExactAlloyEncoder.classifierAtom(TestObservations.B) + "->"
                + ExactAlloyEncoder.memberAtom(observation.members().get(0).technicalKey())));
        assertTrue(alloy.contains("fun formalInheritedMembers[c : Classifier]"));
        assertTrue(alloy.contains("fun InheritedViewConsistencyViolations : Classifier -> Member"));
        assertTrue(alloy.contains("fun LocalInheritedSeparationViolations : Classifier -> Member"));
    }
}
