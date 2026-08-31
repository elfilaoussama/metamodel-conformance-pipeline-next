package metamodel.conformance.pipeline.alloy;

import metamodel.conformance.pipeline.TestObservations;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExactAlloyEncoderTest {
    @Test
    void encodesAnExactRepositoryGraph() {
        String alloy = new ExactAlloyEncoder().encode(TestObservations.acyclic());

        assertEquals(2, alloy.lines().filter(line -> line.startsWith("one sig C_")).count());
        assertTrue(alloy.contains("parents = (" + ExactAlloyEncoder.classifierAtom(TestObservations.B)
                + "->" + ExactAlloyEncoder.classifierAtom(TestObservations.A)));
        assertTrue(alloy.contains("abstract sig ParameterSequence"));
        assertTrue(alloy.contains("firstType: one TypeToken"));
        assertTrue(alloy.contains("remainingTypes: lone ParameterSequence"));
        assertTrue(alloy.contains("parameterSignature: lone ParameterSequence"));
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
    void canonicalizesSharedOrderedParameterSuffixesWithoutLosingOrder() {
        String duplicate = new ExactAlloyEncoder().encode(TestObservations.duplicateLocalMethods());
        String overloaded = new ExactAlloyEncoder().encode(TestObservations.overloadedMethods());

        assertEquals(2, duplicate.lines().filter(line -> line.startsWith("one sig S_")).count());
        assertTrue(duplicate.contains(ExactAlloyEncoder.sequenceAtom(
                List.of("java.lang.String", "int"))));
        assertTrue(duplicate.contains(ExactAlloyEncoder.sequenceAtom(List.of("int"))));
        assertNotEquals(ExactAlloyEncoder.sequenceAtom(List.of("java.lang.String", "int")),
                ExactAlloyEncoder.sequenceAtom(List.of("int", "java.lang.String")));
        assertTrue(overloaded.contains("remainingTypes"));
    }
}
