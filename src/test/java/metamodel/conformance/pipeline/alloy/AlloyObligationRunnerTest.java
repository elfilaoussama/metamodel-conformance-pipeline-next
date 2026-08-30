package metamodel.conformance.pipeline.alloy;

import metamodel.conformance.pipeline.TestObservations;
import metamodel.conformance.pipeline.decision.Decision;
import metamodel.conformance.pipeline.decision.DecisionStatus;
import metamodel.conformance.pipeline.model.ClassifierKind;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.Observation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AlloyObligationRunnerTest {
    private final ExactAlloyEncoder encoder = new ExactAlloyEncoder();
    private final AlloyObligationRunner runner = new AlloyObligationRunner();

    @Test
    void evaluatesAllCataloguedObligationsIndependently() {
        Observation observation = TestObservations.membersConformant();
        List<Decision> decisions = runner.evaluateAll(observation, encoder.encode(observation));

        assertEquals(DecisionStatus.CONFORMANT, decision(decisions, "O-02").status());
        assertEquals(DecisionStatus.CONFORMANT, decision(decisions, "O-03").status());
        assertEquals(DecisionStatus.CONFORMANT, decision(decisions, "O-08-local").status());
    }

    @Test
    void returnsAlloyMemberWitnessForExclusiveOwnershipViolation() {
        Observation observation = TestObservations.unownedMember();
        Decision decision = decision(runner.evaluateAll(observation, encoder.encode(observation)), "O-02");

        assertEquals(DecisionStatus.NON_CONFORMANT, decision.status());
        assertEquals(List.of(observation.members().get(0).technicalKey()), decision.witnessTechnicalKeys());
    }

    @Test
    void orderedParametersDistinguishOverloadsButDetectExactDuplicateKeys() {
        Observation duplicate = TestObservations.duplicateLocalMethods();
        Observation overload = TestObservations.overloadedMethods();

        Decision duplicateDecision = decision(
                runner.evaluateAll(duplicate, encoder.encode(duplicate)), "O-08-local");
        Decision overloadDecision = decision(
                runner.evaluateAll(overload, encoder.encode(overload)), "O-08-local");

        assertEquals(DecisionStatus.NON_CONFORMANT, duplicateDecision.status());
        assertEquals(2, duplicateDecision.witnessTechnicalKeys().size());
        assertEquals(DecisionStatus.CONFORMANT, overloadDecision.status());
    }

    @Test
    void detectsDuplicateLocalAttributeNames() {
        Observation observation = TestObservations.duplicateLocalAttributes();
        Decision decision = decision(
                runner.evaluateAll(observation, encoder.encode(observation)), "O-08-local");

        assertEquals(DecisionStatus.NON_CONFORMANT, decision.status());
        assertEquals(2, decision.witnessTechnicalKeys().size());
    }

    @Test
    void ownershipRelationMutationFlipsOnlyO02() {
        Observation base = TestObservations.membersConformant();
        ClassifierObservation secondOwner = new ClassifierObservation(
                TestObservations.B,
                "example.B",
                ClassifierKind.CLASS,
                "example/B.java",
                3,
                4,
                List.of(),
                List.of(base.members().get(0).technicalKey()));
        Observation mutated = new Observation(
                base.schemaVersion(), base.adapterId(), base.adapterVersion(), base.externalParents(),
                base.completeEvidence(), base.units(),
                List.of(base.classifiers().get(0), secondOwner), base.members(), base.unresolvedParents());

        List<Decision> before = runner.evaluateAll(base, encoder.encode(base));
        List<Decision> after = runner.evaluateAll(mutated, encoder.encode(mutated));

        assertEquals(DecisionStatus.CONFORMANT, decision(before, "O-02").status());
        assertEquals(DecisionStatus.NON_CONFORMANT, decision(after, "O-02").status());
        assertEquals(decision(before, "O-03").status(), decision(after, "O-03").status());
        assertEquals(decision(before, "O-08-local").status(), decision(after, "O-08-local").status());
    }

    @Test
    void missingConditionSpecificEvidenceDoesNotBlockOtherObligations() {
        Observation observation = TestObservations.incompleteLocalSignatures();
        List<Decision> decisions = runner.evaluateAll(observation, encoder.encode(observation));

        assertEquals(DecisionStatus.CONFORMANT, decision(decisions, "O-02").status());
        assertEquals(DecisionStatus.CONFORMANT, decision(decisions, "O-03").status());
        assertEquals(DecisionStatus.INDETERMINATE, decision(decisions, "O-08-local").status());
        assertFalse(decision(decisions, "O-08-local").message().isBlank());
    }

    private static Decision decision(List<Decision> decisions, String id) {
        return decisions.stream().filter(item -> id.equals(item.constraint())).findFirst().orElseThrow();
    }
}
