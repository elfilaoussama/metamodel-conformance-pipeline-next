package metamodel.conformance.pipeline.alloy;

import metamodel.conformance.pipeline.decision.Decision;
import metamodel.conformance.pipeline.decision.DecisionStatus;
import metamodel.conformance.pipeline.model.ClassifierKind;
import metamodel.conformance.pipeline.model.ClassifierObservation;
import metamodel.conformance.pipeline.model.EvidenceKind;
import metamodel.conformance.pipeline.model.ImplementationBindingObservation;
import metamodel.conformance.pipeline.model.Inheritability;
import metamodel.conformance.pipeline.model.Language;
import metamodel.conformance.pipeline.model.MemberKind;
import metamodel.conformance.pipeline.model.MemberObservation;
import metamodel.conformance.pipeline.model.MemberScope;
import metamodel.conformance.pipeline.model.MemberVisibility;
import metamodel.conformance.pipeline.model.MethodAbstraction;
import metamodel.conformance.pipeline.model.MethodBodyObservation;
import metamodel.conformance.pipeline.model.Observation;
import metamodel.conformance.pipeline.model.SourceUnit;
import metamodel.conformance.pipeline.util.Hashing;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OverrideDisciplineInvariantTest {
    private static final String PARENT = "cls_" + Hashing.sha256("override-parent");
    private static final String CHILD = "cls_" + Hashing.sha256("override-child");
    private static final String PARENT_METHOD = "mem_" + Hashing.sha256("override-parent-method");
    private static final String CHILD_METHOD = "mem_" + Hashing.sha256("override-child-method");
    private static final String BODY = "body_" + Hashing.sha256("override-child-body");
    private static final String BINDING = "bind_" + Hashing.sha256("override-child-binding");
    private static final String PATH = "example/OverrideSample.java";

    @Test
    void equalReturnConcreteOverrideWithLocalBindingConforms() {
        Observation observation = observation(
                "java.lang.Number", "java.lang.Number",
                MemberScope.INSTANCE, MemberScope.INSTANCE,
                List.of("int"), List.of("int"),
                MethodAbstraction.CONCRETE, true);

        assertEquals(DecisionStatus.CONFORMANT, decision(observation).status());
    }

    @Test
    void covariantReturnIsRejectedByTheSelectedStrictResearchPolicy() {
        Observation observation = observation(
                "java.lang.Object", "java.lang.String",
                MemberScope.INSTANCE, MemberScope.INSTANCE,
                List.of("int"), List.of("int"),
                MethodAbstraction.CONCRETE, true);

        Decision decision = decision(observation);
        assertEquals(DecisionStatus.NON_CONFORMANT, decision.status());
        assertEquals(1, decision.witnesses().size());
        assertEquals(List.of(CHILD_METHOD, PARENT_METHOD),
                decision.witnesses().get(0).technicalKeys());
    }

    @Test
    void concreteOverrideWithoutLocalImplementationIsRejected() {
        Observation observation = observation(
                "java.lang.Number", "java.lang.Number",
                MemberScope.INSTANCE, MemberScope.INSTANCE,
                List.of("int"), List.of("int"),
                MethodAbstraction.CONCRETE, false);

        assertEquals(DecisionStatus.NON_CONFORMANT, decision(observation).status());
    }

    @Test
    void abstractOverrideNeedsNoLocalImplementationBinding() {
        Observation observation = observation(
                "java.lang.Number", "java.lang.Number",
                MemberScope.INSTANCE, MemberScope.INSTANCE,
                List.of("int"), List.of("int"),
                MethodAbstraction.ABSTRACT, false);

        assertEquals(DecisionStatus.CONFORMANT, decision(observation).status());
    }

    @Test
    void differentScopeDoesNotFormAnOverrideUnderTheSelectedContract() {
        Observation observation = observation(
                "java.lang.Number", "java.lang.String",
                MemberScope.INSTANCE, MemberScope.STATIC,
                List.of("int"), List.of("int"),
                MethodAbstraction.CONCRETE, false);

        assertEquals(DecisionStatus.CONFORMANT, decision(observation).status());
    }

    @Test
    void differentOrderedParameterKeyDoesNotFormAnOverride() {
        Observation observation = observation(
                "java.lang.Number", "java.lang.String",
                MemberScope.INSTANCE, MemberScope.INSTANCE,
                List.of("int"), List.of("long"),
                MethodAbstraction.CONCRETE, false);

        assertEquals(DecisionStatus.CONFORMANT, decision(observation).status());
    }

    private static Observation observation(
            String parentReturn,
            String childReturn,
            MemberScope parentScope,
            MemberScope childScope,
            List<String> parentParameters,
            List<String> childParameters,
            MethodAbstraction childAbstraction,
            boolean childImplemented) {
        MemberObservation parentMethod = new MemberObservation(
                PARENT_METHOD, null, MemberKind.METHOD,
                Inheritability.INHERITABLE, MemberVisibility.PUBLIC,
                "work", PATH, 3, 3, parentParameters,
                MethodAbstraction.ABSTRACT, parentScope, parentReturn);
        MemberObservation childMethod = new MemberObservation(
                CHILD_METHOD, null, MemberKind.METHOD,
                Inheritability.INHERITABLE, MemberVisibility.PUBLIC,
                "work", PATH, 7, 9, childParameters,
                childAbstraction, childScope, childReturn);
        ClassifierObservation parent = new ClassifierObservation(
                PARENT, "example.Parent", "example", ClassifierKind.CLASS,
                PATH, 1, 4, List.of(), List.of(PARENT_METHOD), List.of());
        ClassifierObservation child = new ClassifierObservation(
                CHILD, "example.Child", "example", ClassifierKind.CLASS,
                PATH, 5, 10, List.of(PARENT), List.of(CHILD_METHOD), List.of());
        List<MethodBodyObservation> bodies = childImplemented
                ? List.of(new MethodBodyObservation(BODY, PATH, 7, 9)) : List.of();
        List<ImplementationBindingObservation> bindings = childImplemented
                ? List.of(new ImplementationBindingObservation(BINDING, CHILD, CHILD_METHOD, BODY))
                : List.of();
        return new Observation(
                "12", "test-adapter", "1.0.0", List.of(),
                Set.of(
                        EvidenceKind.HIERARCHY,
                        EvidenceKind.DECLARATION_OWNERSHIP,
                        EvidenceKind.LOCAL_SIGNATURES,
                        EvidenceKind.INHERITABILITY,
                        EvidenceKind.METHOD_ABSTRACTION,
                        EvidenceKind.METHOD_SCOPE,
                        EvidenceKind.IMPLEMENTATION_BINDINGS,
                        EvidenceKind.METHOD_RETURN_TYPES),
                List.of(new SourceUnit(Language.JAVA, PATH, Hashing.sha256("override-source"))),
                List.of(parent, child),
                List.of(parentMethod, childMethod),
                bodies,
                bindings,
                List.of(),
                List.of());
    }

    private static Decision decision(Observation observation) {
        return new AlloyInvariantEvaluator().evaluateAll(
                        observation, new ExactAlloyEncoder().encode(observation)).stream()
                .filter(item -> item.invariantId().equals("override-discipline"))
                .findFirst().orElseThrow();
    }
}
