package metamodel.conformance.pipeline.alloy;

import metamodel.conformance.pipeline.decision.Decision;
import metamodel.conformance.pipeline.decision.DecisionStatus;
import metamodel.conformance.pipeline.model.ClassifierKind;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.ImplementationAvailability;
import metamodel.conformance.pipeline.model.Inheritability;
import metamodel.conformance.pipeline.model.Language;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.MemberVisibility;
import metamodel.conformance.pipeline.model.MethodBodyObservation;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.model.SourceUnit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImplementationBindingInvariantTest {
    private static final String CLASSIFIER = "cls_" + "a".repeat(64);
    private static final String MEMBER = "mem_" + "b".repeat(64);
    private static final String BODY = "body_" + "c".repeat(64);

    @Test
    void acceptsExactImplementationBinding() {
        Observation observation = observation(
                ImplementationAvailability.SOURCE_BODY, List.of(BODY), List.of(body()));

        Decision decision = decision(observation);

        assertEquals(DecisionStatus.CONFORMANT, decision.status());
        assertTrue(decision.witnesses().isEmpty());
    }

    @Test
    void reportsMissingBindingAndOrphanBodyWithoutJavaSideRejection() {
        Observation observation = observation(
                ImplementationAvailability.SOURCE_BODY, List.of(), List.of(body()));

        Decision decision = decision(observation);

        assertEquals(DecisionStatus.NON_CONFORMANT, decision.status());
        Set<String> witnesses = decision.witnesses().stream()
                .flatMap(item -> item.technicalKeys().stream())
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(witnesses.contains(MEMBER));
        assertTrue(witnesses.contains(BODY));
    }

    private static Decision decision(Observation observation) {
        return new AlloyInvariantEvaluator().evaluateAll(
                        observation, new ExactAlloyEncoder().encode(observation)).stream()
                .filter(item -> item.invariantId().equals("implementation-binding-consistency"))
                .findFirst().orElseThrow();
    }

    private static Observation observation(
            ImplementationAvailability availability,
            List<String> bodyKeys,
            List<MethodBodyObservation> bodies) {
        MemberObservation member = new MemberObservation(
                MEMBER, null, MemberKind.METHOD, Inheritability.INHERITABLE,
                MemberVisibility.PUBLIC, "run", "Sample.java", 2, 4, List.of(),
                availability, bodyKeys);
        ClassifierObservation classifier = new ClassifierObservation(
                CLASSIFIER, "Sample", "<default>", ClassifierKind.CLASS,
                "Sample.java", 1, 5, List.of(), List.of(MEMBER), List.of());
        return new Observation(
                "9", "test", "1", List.of(),
                Set.of(
                        EvidenceKind.DECLARATION_OWNERSHIP,
                        EvidenceKind.METHOD_BODIES,
                        EvidenceKind.IMPLEMENTATION_BINDINGS),
                List.of(new SourceUnit(Language.JAVA, "Sample.java", "0".repeat(64))),
                List.of(classifier), List.of(member), bodies, List.of(), List.of());
    }

    private static MethodBodyObservation body() {
        return new MethodBodyObservation(BODY, "Sample.java", 2, 4);
    }
}
